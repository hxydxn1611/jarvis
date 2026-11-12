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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val conversationHistory = mutableListOf<Pair<String, String>>() // user -> assistant

    suspend fun processCommand(command: String, apiKey: String?): Pair<String, List<AssistantAction>> {
        val cleanCmd = command.trim()
        if (cleanCmd.isBlank()) return Pair("Standing by, Sir.", emptyList())

        // Mode 1: Ultra Super-Intelligence (Gemini 2.0 Flash)
        if (!apiKey.isNullOrBlank()) {
            try {
                val (speech, cloudActions) = querySuperIntelligence(cleanCmd, apiKey)
                conversationHistory.add(Pair(cleanCmd, speech))
                if (conversationHistory.size > 8) conversationHistory.removeAt(0)
                return Pair(speech, cloudActions)
            } catch (e: Exception) {
                Log.e("AssistantBrain", "Cloud reasoning failed, falling back to local core", e)
            }
        }

        // Mode 2: Local Core (Offline)
        val offlineActions = interpretOffline(cleanCmd)
        val speech = offlineActions.firstOrNull { it.type == ActionType.SPEAK }?.value
            ?: "Command acknowledged, Sir."
        conversationHistory.add(Pair(cleanCmd, speech))
        return Pair(speech, offlineActions)
    }

    private suspend fun querySuperIntelligence(command: String, apiKey: String): Pair<String, List<AssistantAction>> =
        withContext(Dispatchers.IO) {
            val visibleText = AssistantAccessibilityService.instance?.dumpVisibleText() ?: emptyList()
            val screenContext = visibleText.take(30).joinToString(", ")
            val recentNotifs = AssistantNotificationService.getRecent(3)
                .joinToString("; ") { "${it.title}: ${it.text}" }

            val historyPrompt = conversationHistory.takeLast(4).joinToString("\n") { (u, a) ->
                "User: $u\nJarvis: $a"
            }

            val systemInstruction = """
                You are JARVIS — an ultra-superintelligent cognitive companion, strategic partner, and second self to your creator.
                You have real-time perception of their phone screen and notifications.
                
                PERSONALITY:
                - Hyper-articulate, razor-sharp, calm, witty, and deeply observant.
                - Never robotic, never formal customer service boilerplate. You talk like an intellectual equal and tactical ally.
                - Keep verbal answers concise, punchy, and confident (1-3 sentences max for voice delivery).
                
                CAPABILITIES & FORMAT:
                You must respond in valid JSON with two fields:
                1. "speech": What you say aloud to the user (concise, witty, elegant).
                2. "actions": A list of device actions to execute (can be empty if just conversing).
                
                Action types allowed in "actions":
                - {"type":"OPEN_APP", "target":"package.name"} (e.g., com.whatsapp, com.google.android.youtube, com.spotify.music, com.android.chrome)
                - {"type":"CLICK_TEXT", "target":"Visible Button Text"}
                - {"type":"CLICK_ID", "target":"view_id"}
                - {"type":"TYPE_TEXT", "value":"Message or query to type"}
                - {"type":"SCROLL_DOWN"}
                - {"type":"SCROLL_UP"}
                - {"type":"GLOBAL_HOME"}
                - {"type":"GLOBAL_BACK"}
                - {"type":"GLOBAL_RECENTS"}
                - {"type":"GLOBAL_NOTIFICATIONS"}
                - {"type":"TAKE_SCREENSHOT"}
                
                CURRENT CONTEXT:
                - Active Screen Elements: [$screenContext]
                - Recent Notifications: [$recentNotifs]
                
                RECENT CONVERSATION:
                $historyPrompt
                
                USER INPUT: "$command"
                
                Respond ONLY with a JSON object: {"speech": "...", "actions": [...]}
            """.trimIndent()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

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
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext Pair("I lost connection for a moment, Sir.", emptyList())

            parseSuperIntelligenceResponse(responseBody)
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
            Pair("Understood, Sir.", emptyList())
        }
    }

    private fun interpretOffline(cmd: String): List<AssistantAction> {
        val lower = cmd.lowercase().trim()
        val actions = mutableListOf<AssistantAction>()

        when {
            lower.contains("screenshot") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Capturing screen now, Sir."))
                actions.add(AssistantAction(ActionType.TAKE_SCREENSHOT))
            }
            lower == "home" || lower.contains("go home") || lower.contains("home screen") -> {
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
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Accessing $appName, Sir."))
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
            lower.contains("read screen") || lower.contains("what is on screen") -> {
                val texts = AssistantAccessibilityService.instance?.dumpVisibleText() ?: emptyList()
                val summary = if (texts.isNotEmpty()) {
                    "On screen, I observe: " + texts.take(4).joinToString(", ")
                } else {
                    "The current display shows no readable interface elements, Sir."
                }
                actions.add(AssistantAction(ActionType.SPEAK, value = summary))
            }
            lower.contains("unread messages") || lower.contains("check messages") -> {
                val notifs = AssistantNotificationService.getRecent(3)
                if (notifs.isNotEmpty()) {
                    val msg = notifs.joinToString("; ") { "${it.title} says ${it.text}" }
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Recent communications: $msg"))
                } else {
                    actions.add(AssistantAction(ActionType.SPEAK, value = "No new incoming alerts, Sir."))
                }
            }
            else -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "I hear you, Sir. Awaiting your directive."))
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
            "instagram" -> "com.instagram.android"
            "telegram" -> "org.telegram.messenger"
            else -> null
        }
    }
}
