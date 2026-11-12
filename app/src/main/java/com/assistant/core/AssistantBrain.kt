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
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun processCommand(command: String, apiKey: String?): List<AssistantAction> {
        val cleanCmd = command.trim()
        if (cleanCmd.isBlank()) return emptyList()

        // Mode 1: Cloud AI Reasoning (if API key is supplied)
        if (!apiKey.isNullOrBlank()) {
            try {
                val cloudActions = queryGemini(cleanCmd, apiKey)
                if (cloudActions.isNotEmpty()) {
                    return cloudActions
                }
            } catch (e: Exception) {
                Log.e("AssistantBrain", "Cloud reasoning failed, falling back to offline", e)
            }
        }

        // Mode 2: Built-in Offline Action Engine (No API key needed)
        return interpretOffline(cleanCmd)
    }

    private suspend fun queryGemini(command: String, apiKey: String): List<AssistantAction> =
        withContext(Dispatchers.IO) {
            val visibleText = AssistantAccessibilityService.instance?.dumpVisibleText() ?: emptyList()
            val screenContext = visibleText.take(25).joinToString(", ")

            val prompt = """
                You are a phone automation agent. Convert the user command into a JSON array of actions.
                Current screen visible elements: [$screenContext]
                User command: "$command"
                
                Respond ONLY with a valid JSON array of objects with fields:
                - type: (OPEN_APP, CLICK_TEXT, CLICK_ID, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, GLOBAL_HOME, GLOBAL_BACK, GLOBAL_RECENTS, GLOBAL_NOTIFICATIONS, TAKE_SCREENSHOT, SPEAK)
                - target: (package name for OPEN_APP, or text/id for CLICK)
                - value: (text for TYPE_TEXT or SPEAK)
                
                Example:
                [{"type":"OPEN_APP","target":"com.whatsapp"},{"type":"SPEAK","value":"Opening WhatsApp, Sir."}]
            """.trimIndent()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

            val jsonBody = JsonObject().apply {
                val contents = JsonArray()
                val contentObj = JsonObject()
                val parts = JsonArray()
                val partObj = JsonObject()
                partObj.addProperty("text", prompt)
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
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            parseGeminiResponse(responseBody)
        }

    private fun parseGeminiResponse(rawJson: String): List<AssistantAction> {
        return try {
            val root = gson.fromJson(rawJson, JsonObject::class.java)
            val candidates = root.getAsJsonArray("candidates")
            val firstCandidate = candidates.get(0).asJsonObject
            val content = firstCandidate.getAsJsonObject("content")
            val parts = content.getAsJsonArray("parts")
            val text = parts.get(0).asJsonObject.get("text").asString

            val cleanJson = text.substringAfter("[").substringBeforeLast("]")
            val wrapped = "[$cleanJson]"
            gson.fromJson(wrapped, Array<AssistantAction>::class.java).toList()
        } catch (e: Exception) {
            Log.e("AssistantBrain", "Failed to parse LLM response", e)
            emptyList()
        }
    }

    private fun interpretOffline(cmd: String): List<AssistantAction> {
        val lower = cmd.lowercase().trim()
        val actions = mutableListOf<AssistantAction>()

        when {
            lower.contains("screenshot") -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Taking screenshot."))
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
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Opening $appName."))
                    actions.add(AssistantAction(ActionType.OPEN_APP, target = pkg))
                } else {
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Searching for $appName."))
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
                    "On screen I see: " + texts.take(4).joinToString(", ")
                } else {
                    "The active screen has no readable text, Sir."
                }
                actions.add(AssistantAction(ActionType.SPEAK, value = summary))
            }
            lower.contains("unread messages") || lower.contains("check messages") -> {
                val notifs = AssistantNotificationService.getRecent(3)
                if (notifs.isNotEmpty()) {
                    val msg = notifs.joinToString("; ") { "${it.title} says ${it.text}" }
                    actions.add(AssistantAction(ActionType.SPEAK, value = "Recent messages: $msg"))
                } else {
                    actions.add(AssistantAction(ActionType.SPEAK, value = "No recent notifications."))
                }
            }
            else -> {
                actions.add(AssistantAction(ActionType.SPEAK, value = "Command acknowledged: $cmd"))
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

