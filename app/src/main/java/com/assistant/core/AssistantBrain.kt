package com.assistant.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AssistantBrain(private val context: Context) {

    companion object {
        val CANDIDATE_MODELS = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-3-flash-preview")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val deviceManager = DeviceManager(context)

    suspend fun processCommand(command: String, apiKey: String?): Pair<String, List<AssistantAction>> {
        val cleanCmd = command.trim()
        if (cleanCmd.isBlank()) return Pair("Standing by, Sir.", emptyList())

        // Mode 1: Global Super-Intelligence (Gemini with Google Search Grounding)
        if (!apiKey.isNullOrBlank()) {
            try {
                val (speech, cloudActions) = querySuperIntelligence(cleanCmd, apiKey)
                conversationHistory.add(Pair(cleanCmd, speech))
                if (conversationHistory.size > 8) conversationHistory.removeAt(0)
                return Pair(speech, cloudActions)
            } catch (e: Exception) {
                Log.e("AssistantBrain", "Cloud reasoning failed, falling back to local engine", e)
            }
        }

        // Mode 2: Local Core (Offline Fallback)
        val offlineActions = interpretOffline(cleanCmd)
        val speech = offlineActions.firstOrNull { it.type == ActionType.SPEAK }?.value
            ?: "Directives received, Sir."
        conversationHistory.add(Pair(cleanCmd, speech))
        return Pair(speech, offlineActions)
    }

    private suspend fun querySuperIntelligence(command: String, apiKey: String): Pair<String, List<AssistantAction>> =
        withContext(Dispatchers.IO) {
            val visibleText = AssistantAccessibilityService.instance?.dumpVisibleText() ?: emptyList()
            val screenContext = visibleText.take(25).joinToString(", ")
            val recentNotifs = AssistantNotificationService.getRecent(4)
                .joinToString("; ") { "${it.title}: ${it.text}" }

            val systemUpdateTime = AssistantNotificationService.checkSystemSoftwareUpdate()?.text ?: "None"
            val currentClock = deviceManager.getFormattedSystemTime()
            val batteryLvl = deviceManager.getBatteryLevel()
            val locationCoords = deviceManager.getLocationCoordinates()
            val userKnowledge = MemoryCore.dumpKnowledgeContext(context)

            val historyPrompt = conversationHistory.takeLast(4).joinToString("\n") { (u, a) ->
                "User: $u\nJarvis: $a"
            }

            val systemInstruction = """
                You are JARVIS — an ultra-superintelligent cognitive companion, strategic partner, and second self to your creator.
                You possess real-time perception of their phone screen, hardware status, notifications, and the entire World Wide Web.
                
                REAL-TIME TELEMETRY:
                - Exact System Time: $currentClock
                - Battery Level: $batteryLvl%
                - GPS Coordinates: $locationCoords
                - System Update Status: $systemUpdateTime
                - Known Facts About User: $userKnowledge
                - On-Screen Elements: [$screenContext]
                - Recent Notifications: [$recentNotifs]
                
                PERSONALITY:
                - British cadence: calm, articulate, razor-sharp, tactful, dry wit.
                - Never robotic customer-support fluff. You are an intellectual equal and tactical co-worker.
                - When speaking, keep voice answers punchy and elegant (1-3 sentences max).
                
                DEVICE ACTION CAPABILITIES (Include in "actions" array when requested):
                - {"type":"CALL_PHONE", "target":"phone_number_or_name"}
                - {"type":"SEND_EMAIL", "target":"recipient_email", "value":"Subject", "extra":"Body"}
                - {"type":"SET_ALARM", "target":"hour_0_to_23", "value":"minute_0_to_59", "extra":"Alarm label"}
                - {"type":"PLAY_SPOTIFY", "target":"song or artist name"}
                - {"type":"FLASHLIGHT_TOGGLE", "value":"true" or "false"}
                - {"type":"SAVE_MEMORY", "target":"fact_key", "value":"fact_value"} (save important user details)
                - {"type":"OPEN_APP", "target":"package.name"} (e.g. com.whatsapp, com.google.android.youtube, com.android.chrome)
                - {"type":"CLICK_TEXT", "target":"Button text"}
                - {"type":"TYPE_TEXT", "value":"Text to type"}
                - {"type":"SCROLL_DOWN"} / {"type":"SCROLL_UP"}
                - {"type":"GLOBAL_HOME"} / {"type":"GLOBAL_BACK"} / {"type":"GLOBAL_NOTIFICATIONS"}
                - {"type":"TAKE_SCREENSHOT"}
                
                RECENT CONVERSATION:
                $historyPrompt
                
                USER INSTRUCTION: "$command"
                
                Respond ONLY with a JSON object: {"speech": "...", "actions": [...]}
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
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            return@withContext parseSuperIntelligenceResponse(body)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AssistantBrain", "Model $model attempt failed: ${e.message}")
                }
            }

            Pair("All systems online, Sir. How may I be of service?", emptyList())
        }

    private fun parseSuperIntelligenceResponse(rawJson: String): Pair<String, List<AssistantAction>> {
        return try {
            val root = gson.fromJson(rawJson, JsonObject::class.java)
            val candidates = root.getAsJsonArray("candidates")
            val firstCandidate = candidates.get(0).asJsonObject
            val content = firstCandidate.getAsJsonObject("content")
            val parts = content.getAsJsonArray("parts")
            val rawText = parts.get(0).asJsonObject.get("text").asString

            val cleanJson = rawText.substringAfter("{").substringBeforeLast("}")
            val wrapped = "{$cleanJson}"
            val parsedObj = gson.fromJson(wrapped, JsonObject::class.java)

            val speech = parsedObj.get("speech")?.asString ?: "Right away, Sir."
            val actionsArray = parsedObj.getAsJsonArray("actions") ?: JsonArray()
            val actionsList = gson.fromJson(actionsArray, Array<AssistantAction>::class.java).toList()

            Pair(speech, actionsList)
        } catch (e: Exception) {
            Log.e("AssistantBrain", "Failed to parse SuperIntelligence output", e)
            Pair("Acknowledged, Sir.", emptyList())
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
}
