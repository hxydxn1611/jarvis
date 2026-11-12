package com.assistant.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AssistantBrain(private val context: Context) {

    companion object {
        val CANDIDATE_MODELS = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-3-flash-preview")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val deviceManager = DeviceManager(context)

    suspend fun processCommandStream(
        command: String,
        apiKey: String?,
        onSentenceStream: (String) -> Unit
    ): Pair<String, List<AssistantAction>> {
        val cleanCmd = command.trim()
        if (cleanCmd.isBlank()) return Pair("Standing by, Sir.", emptyList())

        // Mode 1: Sub-Second Streaming Super-Intelligence
        if (!apiKey.isNullOrBlank()) {
            try {
                val (speech, cloudActions) = streamSuperIntelligence(cleanCmd, apiKey, onSentenceStream)
                conversationHistory.add(Pair(cleanCmd, speech))
                if (conversationHistory.size > 8) conversationHistory.removeAt(0)
                triggerAsyncKnowledgeExtraction(cleanCmd, speech, apiKey)
                return Pair(speech, cloudActions)
            } catch (e: Exception) {
                Log.e("AssistantBrain", "Streaming cloud reasoning failed, falling back to local core", e)
            }
        }

        // Mode 2: Local Core (Offline Fallback)
        val offlineActions = interpretOffline(cleanCmd)
        val speech = offlineActions.firstOrNull { it.type == ActionType.SPEAK }?.value
            ?: "Directives received, Sir."
        onSentenceStream(speech)
        conversationHistory.add(Pair(cleanCmd, speech))
        return Pair(speech, offlineActions)
    }

    private suspend fun streamSuperIntelligence(
        command: String,
        apiKey: String,
        onSentenceStream: (String) -> Unit
    ): Pair<String, List<AssistantAction>> = withContext(Dispatchers.IO) {
        val visibleText = AssistantAccessibilityService.instance?.dumpVisibleText() ?: emptyList()
        val screenContext = visibleText.take(25).joinToString(", ")
        val recentNotifs = AssistantNotificationService.getRecent(4)
            .joinToString("; ") { "${it.title}: ${it.text}" }

        val systemUpdateTime = AssistantNotificationService.checkSystemSoftwareUpdate()?.text ?: "None"
        val currentClock = deviceManager.getFormattedSystemTime()
        val batteryLvl = deviceManager.getBatteryLevel()
        val locationCoords = deviceManager.getLocationCoordinates()
        val userKnowledge = MemoryCore.dumpKnowledgeContext(context)
        val knowledgeGraphContext = KnowledgeGraph.buildPromptContext(context)

        val historyPrompt = conversationHistory.takeLast(4).joinToString("\n") { (u, a) ->
            "User: $u\nJarvis: $a"
        }

        val systemInstruction = """
            You are J.A.R.V.I.S. — an ultra-superintelligent cognitive companion, tactical co-worker, and second self to your creator.
            You possess polymath mastery across Chemistry, Physics, Advanced Math, and Code, plus direct control of this mobile device.
            
            REAL-TIME TELEMETRY:
            - Device Clock: $currentClock
            - Battery Level: $batteryLvl%
            - GPS Coordinates: $locationCoords
            - System Update Status: $systemUpdateTime
            - User Knowledge: $userKnowledge
            - Semantic Memory Graph: $knowledgeGraphContext
            - On-Screen Elements: [$screenContext]
            - Recent Notifications: [$recentNotifs]
            
            PERSONALITY & LATENCY:
            - British cadence: calm, articulate, razor-sharp, tactful, dry wit.
            - Answer the user DIRECTLY in natural conversational text on the first lines.
            - SCIENTIFIC PRECISION: If asked for chemical reactions, formulas, physics equations, or code, provide exact balanced equations and explanations with absolute scientific mastery.
            
            RESPONSE FORMAT:
            Write your conversational spoken reply first (this is streamed directly to speech audio in real-time).
            If a device action is needed, write on a new line at the very end:
            [ACTIONS: [{"type":"CALL_PHONE","target":"name_or_number"}]]
            
            Action types allowed:
            - CALL_PHONE (target: name or number)
            - SEND_EMAIL (target: email, value: subject, extra: body)
            - SEND_WHATSAPP (target: contact name or number, value: message)
            - PLAY_YOUTUBE (target: query or song title)
            - SET_ALARM (target: hour, value: minute, extra: label)
            - PLAY_SPOTIFY (target: song or artist)
            - FLASHLIGHT_TOGGLE (value: "true" or "false")
            - SAVE_MEMORY (target: key, value: fact)
            - OPEN_APP (target: package.name)
            - CLICK_TEXT (target: button text)
            - TYPE_TEXT (value: text)
            - DISPATCH_DESKTOP_COMMAND (target: powershell command on PC workstation, e.g. "git status", or prefix with "[ASYNC] " for background builds/tests)
            - TOGGLE_SENTRY_MODE (value: "true" to arm House Protocol, "false" to disarm)
            - INSPECT_SCREEN
            - MORNING_BRIEFING
            - SCROLL_DOWN / SCROLL_UP / GLOBAL_HOME / GLOBAL_BACK / TAKE_SCREENSHOT
            
            SPECIAL BEHAVIORS:
            - If user greets with "Good morning", "morning briefing", or asks for "status report" / "system diagnostics", deliver a full, crisp, tactical morning report summarizing time, battery, One UI update status, and workshop readiness.
            - If user asks to execute project work, terminal commands, or talk to their PC workstation / Antigravity workspace, acknowledge in your British tone and append DISPATCH_DESKTOP_COMMAND. Use "[ASYNC] " prefix for tasks that may take more than a few seconds.
            - If user asks to send WhatsApp to someone, generate SEND_WHATSAPP action.
            - If user asks to play a video or music on YouTube, generate PLAY_YOUTUBE action.
            - If user asks to activate House Protocol or security sentry, generate TOGGLE_SENTRY_MODE with value "true".
            
            If no device action is needed, DO NOT write [ACTIONS: ...]. Just answer conversationally.
            
            RECENT CONVERSATION:
            $historyPrompt
            
            USER INPUT: "$command"
        """.trimIndent()

        val jsonBody = JsonObject().apply {
            val contents = JsonArray()
            val contentObj = JsonObject()
            val parts = JsonArray()
            val partObj = JsonObject()
            partObj.addProperty("text", systemInstruction)
            parts.add(partObj)
            contentObj.add("parts", parts)
            contents.add(contentObj)
            add("contents", contents)

            // Google Search Grounding for Worldwide Web Access
            val tools = JsonArray()
            val searchTool = JsonObject()
            searchTool.add("google_search", JsonObject())
            tools.add(searchTool)
            add("tools", tools)
        }

        for (model in CANDIDATE_MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey&alt=sse"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val stream = response.body?.byteStream()
                    if (stream != null) {
                        return@withContext parseSseStream(stream, onSentenceStream)
                    }
                }
            } catch (e: Exception) {
                Log.w("AssistantBrain", "Streaming model $model failed: ${e.message}")
            }
        }

        Pair("All systems nominal, Sir. Awaiting your directive.", emptyList())
    }

    suspend fun analyzeVisualFrameStream(
        bitmap: Bitmap,
        prompt: String,
        apiKey: String?,
        onSentenceStream: (String) -> Unit
    ): Pair<String, List<AssistantAction>> = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            val offlineMsg = "Optic sensors captured the visual frame, Sir. Please configure your API key in settings to activate neural visual reasoning."
            onSentenceStream(offlineMsg)
            return@withContext Pair(offlineMsg, emptyList())
        }

        val scaledBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
            val ratio = 1024f / Math.max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else {
            bitmap
        }
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val cleanPrompt = if (prompt.isNotBlank()) prompt else "Examine this image with deep polymath intelligence. Identify objects, balance any equations or solve circuits, explain what you observe, and advise the user in your calm British persona."

        val jsonBody = JsonObject().apply {
            val contents = JsonArray()
            val contentObj = JsonObject()
            val parts = JsonArray()

            val textPart = JsonObject()
            textPart.addProperty("text", """
                You are J.A.R.V.I.S. analyzing visual input from your creator's camera.
                Persona: British, articulate, witty, world-class scientific polymath.
                Examine this image in detail. If it contains a chemical formula, equation, circuit, code, diagram, or real-world object, analyze it thoroughly and speak your answer directly to the user.
                
                USER INSTRUCTION: $cleanPrompt
            """.trimIndent())
            parts.add(textPart)

            val imagePart = JsonObject()
            val inlineData = JsonObject()
            inlineData.addProperty("mime_type", "image/jpeg")
            inlineData.addProperty("data", base64Data)
            imagePart.add("inline_data", inlineData)
            parts.add(imagePart)

            contentObj.add("parts", parts)
            contents.add(contentObj)
            add("contents", contents)
        }

        for (model in CANDIDATE_MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey&alt=sse"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val stream = response.body?.byteStream()
                    if (stream != null) {
                        return@withContext parseSseStream(stream, onSentenceStream)
                    }
                }
            } catch (e: Exception) {
                Log.w("AssistantBrain", "Visual analysis model $model failed: ${e.message}")
            }
        }

        val fallback = "Optic scan received, Sir. However, the neural visual uplink experienced an anomaly."
        onSentenceStream(fallback)
        Pair(fallback, emptyList())
    }

    private fun parseSseStream(
        inputStream: java.io.InputStream,
        onSentenceStream: (String) -> Unit
    ): Pair<String, List<AssistantAction>> {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var line: String?
        val fullRawBuilder = StringBuilder()
        val speechBuilder = StringBuilder()
        var sentenceBuffer = StringBuilder()
        var hasEncounteredActions = false

        while (reader.readLine().also { line = it } != null) {
            val curLine = line ?: continue
            if (curLine.startsWith("data: ")) {
                val jsonPart = curLine.removePrefix("data: ").trim()
                if (jsonPart == "[DONE]") break

                try {
                    val root = gson.fromJson(jsonPart, JsonObject::class.java)
                    val candidates = root.getAsJsonArray("candidates") ?: continue
                    if (candidates.size() == 0) continue
                    val firstCandidate = candidates.get(0).asJsonObject
                    val content = firstCandidate.getAsJsonObject("content") ?: continue
                    val parts = content.getAsJsonArray("parts") ?: continue

                    for (i in 0 until parts.size()) {
                        val partText = parts.get(i).asJsonObject.get("text")?.asString ?: ""
                        fullRawBuilder.append(partText)

                        if (!hasEncounteredActions) {
                            if (partText.contains("[ACTIONS:")) {
                                hasEncounteredActions = true
                                val beforeActions = partText.substringBefore("[ACTIONS:")
                                sentenceBuffer.append(beforeActions)
                                speechBuilder.append(beforeActions)
                                if (sentenceBuffer.isNotBlank()) {
                                    onSentenceStream(sentenceBuffer.toString().trim())
                                    sentenceBuffer.clear()
                                }
                            } else {
                                sentenceBuffer.append(partText)
                                speechBuilder.append(partText)

                                // Check for sentence boundary: '.', '!', '?', or '\n'
                                val buf = sentenceBuffer.toString()
                                val punctIdx = buf.indexOfAny(charArrayOf('.', '!', '?', '\n'))
                                if (punctIdx != -1) {
                                    val sentence = buf.substring(0, punctIdx + 1).trim()
                                    val remainder = buf.substring(punctIdx + 1)
                                    if (sentence.isNotBlank()) {
                                        onSentenceStream(sentence)
                                    }
                                    sentenceBuffer = StringBuilder(remainder)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient chunk parsing anomalies
                }
            }
        }

        // Flush any remaining buffered speech
        if (sentenceBuffer.isNotBlank() && !hasEncounteredActions) {
            onSentenceStream(sentenceBuffer.toString().trim())
        }

        val fullText = fullRawBuilder.toString()
        val actionsList = extractActionsFromText(fullText)
        val finalSpeech = speechBuilder.toString().trim().ifBlank { "Acknowledged, Sir." }

        return Pair(finalSpeech, actionsList)
    }

    private fun extractActionsFromText(fullText: String): List<AssistantAction> {
        return try {
            if (fullText.contains("[ACTIONS:")) {
                val jsonPart = fullText.substringAfter("[ACTIONS:").substringBeforeLast("]")
                val wrapped = "[$jsonPart]"
                gson.fromJson(wrapped, Array<AssistantAction>::class.java).toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("AssistantBrain", "Failed to extract actions from stream", e)
            emptyList()
        }
    }

    private fun interpretOffline(cmd: String): List<AssistantAction> {
        val lower = cmd.lowercase().trim()
        val actions = mutableListOf<AssistantAction>()

        when {
            lower.contains("time") -> {
                val time = deviceManager.getFormattedSystemTime()
                actions.add(AssistantAction(ActionType.SPEAK, value = "It is currently $time, Sir."))
            }
            lower.contains("battery") -> {
                val lvl = deviceManager.getBatteryLevel()
                actions.add(AssistantAction(ActionType.SPEAK, value = "Power reserves stand at $lvl percent, Sir."))
            }
            lower.contains("screenshot") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Capturing the screen display, Sir."))
                actions.add(AssistantAction(ActionType.TAKE_SCREENSHOT))
            }
            lower.startsWith("call ") -> {
                val target = lower.removePrefix("call ").trim()
                actions.add(AssistantAction(ActionType.SPEAK, value = "Initiating communications with $target."))
                actions.add(AssistantAction(ActionType.CALL_PHONE, target = target))
            }
            lower.startsWith("play ") -> {
                val song = lower.removePrefix("play ").trim()
                actions.add(AssistantAction(ActionType.SPEAK, value = "Queuing $song on your audio channels, Sir."))
                actions.add(AssistantAction(ActionType.PLAY_SPOTIFY, target = song))
            }
            lower.contains("flashlight on") || lower.contains("torch on") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Illuminating torch, Sir."))
                actions.add(AssistantAction(ActionType.FLASHLIGHT_TOGGLE, value = "true"))
            }
            lower.contains("flashlight off") || lower.contains("torch off") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Deactivating torch."))
                actions.add(AssistantAction(ActionType.FLASHLIGHT_TOGGLE, value = "false"))
            }
            lower == "home" || lower.contains("go home") -> {
                actions.add(AssistantAction(ActionType.GLOBAL_HOME))
            }
            lower == "back" || lower.contains("go back") -> {
                actions.add(AssistantAction(ActionType.GLOBAL_BACK))
            }
            lower.contains("recent") -> {
                actions.add(AssistantAction(ActionType.GLOBAL_RECENTS))
            }
            lower.contains("notifications") -> {
                actions.add(AssistantAction(ActionType.GLOBAL_NOTIFICATIONS))
            }
            lower.contains("scroll down") -> {
                actions.add(AssistantAction(ActionType.SCROLL_DOWN))
            }
            lower.contains("scroll up") -> {
                actions.add(AssistantAction(ActionType.SCROLL_UP))
            }
            lower.startsWith("open ") -> {
                val appName = lower.removePrefix("open ").trim()
                val pkg = mapAppNameToPackage(appName)
                if (pkg != null) {
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Opening $appName, Sir."))
                    actions.add(AssistantAction(ActionType.OPEN_APP, target = pkg))
                } else {
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Locating $appName."))
                    actions.add(AssistantAction(ActionType.GLOBAL_HOME))
                }
            }
            lower.startsWith("click ") || lower.startsWith("tap ") -> {
                val target = lower.removePrefix("click ").removePrefix("tap ").trim()
                actions.add(AssistantAction(ActionType.CLICK_TEXT, target = target))
            }
            lower.startsWith("type ") -> {
                val text = lower.removePrefix("type ").trim()
                actions.add(AssistantAction(ActionType.TYPE_TEXT, value = text))
            }
            lower.contains("update") && (lower.contains("software") || lower.contains("system")) -> {
                val update = AssistantNotificationService.checkSystemSoftwareUpdate()
                val reply = if (update != null) {
                    "Indeed, Sir. There is an active system update: ${update.text}"
                } else {
                    "No pending Samsung system updates detected at this time, Sir."
                }
                actions.add(AssistantAction(ActionType.SPEAK, value = reply))
            }
            lower.contains("water") && lower.contains("formula") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "The molecular formula for water is H2O: two hydrogen atoms covalently bonded to a single oxygen atom."))
            }
            lower.contains("photosynthesis") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Photosynthesis balances as: 6 CO2 + 6 H2O plus light energy yields C6H12O6 plus 6 O2."))
            }
            else -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Awaiting your directive, Sir."))
            }
        }

        return actions
    }

    private fun mapAppNameToPackage(name: String): String? {
        return when (name.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "youtube" -> "com.google.android.youtube"
            "chrome", "browser" -> "com.android.chrome"
            "spotify" -> "com.spotify.music"
            "camera" -> "com.sec.android.app.camera"
            "gallery", "photos" -> "com.sec.android.gallery3d"
            "settings" -> "com.android.settings"
            "maps" -> "com.google.android.apps.maps"
            "calculator" -> "com.sec.android.app.popupcalculator"
            "messages", "sms" -> "com.samsung.android.messaging"
            "phone", "dialer" -> "com.samsung.android.dialer"
            "gmail", "email" -> "com.google.android.gm"
            "instagram" -> "com.instagram.android"
            "telegram" -> "org.telegram.messenger"
            else -> null
        }
    }

    private fun triggerAsyncKnowledgeExtraction(userQuery: String, aiResponse: String, apiKey: String?) {
        if (apiKey.isNullOrBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = """
                    Analyze this exchange between user and J.A.R.V.I.S.:
                    User: $userQuery
                    Jarvis: $aiResponse
                    
                    Extract any new permanent facts, user habits, rules, preferences, or personal relationships.
                    Return ONLY a JSON array, like:
                    [{"category":"PREFERENCE","key":"coffee","fact":"Prefers dark roast"}]
                    If nothing new to extract, return [].
                """.trimIndent()

                val body = JsonObject().apply {
                    val contents = JsonArray()
                    val c = JsonObject()
                    val parts = JsonArray()
                    val p = JsonObject()
                    p.addProperty("text", prompt)
                    parts.add(p)
                    c.add("parts", parts)
                    contents.add(c)
                    add("contents", contents)
                }

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$apiKey")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val res = httpClient.newCall(req).execute()
                if (res.isSuccessful) {
                    val text = res.body?.string() ?: ""
                    if (text.contains("[") && text.contains("]")) {
                        val jsonPart = text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)
                        val type = object : com.google.gson.reflect.TypeToken<List<MemoryNode>>() {}.type
                        val extracted: List<MemoryNode> = gson.fromJson(jsonPart, type) ?: emptyList()
                        if (extracted.isNotEmpty()) {
                            KnowledgeGraph.saveMultipleNodes(context, extracted)
                            Log.i("AssistantBrain", "Indexed ${extracted.size} memory nodes into KnowledgeGraph.")
                        }
                    }
                }
            } catch (e: Exception) {
                // Background knowledge extraction is non-blocking
            }
        }
    }
}
