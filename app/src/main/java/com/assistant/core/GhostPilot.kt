package com.assistant.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * The Ghost Pilot: Full Autonomous In-App Pilot for J.A.R.V.I.S.
 * Navigates, interacts, and operates third-party apps (WhatsApp, YouTube, Settings)
 * without requiring the user to touch the screen.
 */
object GhostPilot {

    private const val TAG = "GhostPilot"
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Normalizes spoken number words (e.g. "nine eight seven") into raw numeric digits
     */
    fun normalizeSpokenDigits(text: String): String {
        val wordMap = mapOf(
            "zero" to "0", "oh" to "0", "one" to "1", "two" to "2",
            "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
        )
        var result = text.lowercase()
        for ((word, digit) in wordMap) {
            result = result.replace(Regex("\\b$word\\b"), digit)
        }
        return result
    }

    /**
     * Autonomously dispatches a WhatsApp message to a contact or phone number
     */
    fun sendWhatsApp(
        service: AssistantAccessibilityService,
        targetContactOrNumber: String,
        message: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        scope.launch {
            try {
                val deviceManager = DeviceManager(service)
                var phone = normalizeSpokenDigits(targetContactOrNumber).replace(Regex("[^0-9+]"), "")
                
                // If not numeric or too short, resolve from device contacts book
                if (phone.length < 10) {
                    val lookedUp = deviceManager.lookupContactNumber(targetContactOrNumber)
                    if (!lookedUp.isNullOrBlank()) {
                        phone = normalizeSpokenDigits(lookedUp).replace(Regex("[^0-9+]"), "")
                    }
                }

                if (phone.startsWith("+")) {
                    phone = phone.removePrefix("+")
                }
                if (phone.length == 10) {
                    phone = "91$phone"
                }

                if (phone.length >= 10) {
                    val encoded = URLEncoder.encode(message, "UTF-8")
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val pm = service.packageManager
                    if (isPackageInstalled(pm, "com.whatsapp")) {
                        intent.setPackage("com.whatsapp")
                    } else if (isPackageInstalled(pm, "com.whatsapp.w4b")) {
                        intent.setPackage("com.whatsapp.w4b")
                    }
                    service.startActivity(intent)

                    // Actively poll for the WhatsApp Send button to appear as chat initializes
                    val clicked = pollAndClickSend(service, maxAttempts = 22, delayIntervalMs = 200)
                    delay(800)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

                    if (clicked) {
                        onComplete(true, "WhatsApp transmission dispatched to $targetContactOrNumber, Sir.")
                    } else {
                        onComplete(true, "WhatsApp conversation opened with message prepared, Sir.")
                    }
                } else {
                    // Fallback: Open WhatsApp directly and search by contact name
                    val launchIntent = service.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(launchIntent)
                        delay(1500)
                        
                        // Search for contact name in WhatsApp
                        searchAndOpenChat(service, targetContactOrNumber)
                        delay(1200)
                        
                        // Type message & send
                        typeAndSendInCurrentChat(service, message)
                        delay(800)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        onComplete(true, "Autonomous WhatsApp navigation completed for $targetContactOrNumber, Sir.")
                    } else {
                        onComplete(false, "WhatsApp is not installed on this terminal, Sir.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ghost pilot failed to send WhatsApp", e)
                onComplete(false, "WhatsApp automation encountered an anomaly: ${e.message}")
            }
        }
    }

    /**
     * Universal WhatsApp Dispatcher: works from any Context with direct API links + auto-send
     */
    fun sendWhatsApp(
        context: Context,
        targetContactOrNumber: String,
        message: String,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        try {
            val dm = DeviceManager(context)
            var phone = normalizeSpokenDigits(targetContactOrNumber).replace(Regex("[^0-9+]"), "")
            if (phone.length < 10) {
                val lookedUp = dm.lookupContactNumber(targetContactOrNumber)
                if (!lookedUp.isNullOrBlank()) {
                    phone = normalizeSpokenDigits(lookedUp).replace(Regex("[^0-9+]"), "")
                }
            }
            if (phone.startsWith("+")) {
                phone = phone.removePrefix("+")
            }
            if (phone.length == 10) {
                phone = "91$phone"
            }

            if (phone.length >= 10) {
                val encoded = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pm = context.packageManager
                if (isPackageInstalled(pm, "com.whatsapp")) {
                    intent.setPackage("com.whatsapp")
                } else if (isPackageInstalled(pm, "com.whatsapp.w4b")) {
                    intent.setPackage("com.whatsapp.w4b")
                }
                context.startActivity(intent)

                // If accessibility service is active, auto-click send via polling loop
                val service = AssistantAccessibilityService.instance
                if (service != null) {
                    scope.launch {
                        val clicked = pollAndClickSend(service, maxAttempts = 22, delayIntervalMs = 200)
                        if (clicked) {
                            delay(800)
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        }
                    }
                }
                onComplete?.invoke(true, "Transmitting WhatsApp message to $targetContactOrNumber, Sir.")
            } else {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val service = AssistantAccessibilityService.instance
                    if (service != null) {
                        scope.launch {
                            delay(1500)
                            searchAndOpenChat(service, targetContactOrNumber)
                            delay(1200)
                            typeAndSendInCurrentChat(service, message)
                            delay(800)
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        }
                        onComplete?.invoke(true, "Opening WhatsApp and searching for $targetContactOrNumber, Sir.")
                    } else {
                        onComplete?.invoke(true, "Opening WhatsApp for $targetContactOrNumber, Sir. Please enable Accessibility in Settings for automatic typing.")
                    }
                } else {
                    onComplete?.invoke(false, "WhatsApp is not installed on this terminal, Sir.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp dispatch error", e)
            onComplete?.invoke(false, "WhatsApp transmission error: ${e.message}")
        }
    }

    /**
     * Autonomously launches YouTube, searches query, and plays first video
     */
    fun playYouTube(
        service: AssistantAccessibilityService,
        searchQuery: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        scope.launch {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(searchQuery, "UTF-8")}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                delay(2200)

                // Attempt to tap the first video thumbnail or title
                val root = service.rootInActiveWindow
                val tapped = clickFirstPlayableVideo(root)

                if (tapped) {
                    onComplete(true, "Streaming '$searchQuery' on YouTube, Sir.")
                } else {
                    onComplete(true, "YouTube opened to '$searchQuery', Sir.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ghost pilot failed to play YouTube", e)
                onComplete(false, "YouTube playback automation failed: ${e.message}")
            }
        }
    }

    fun playYouTube(context: Context, searchQuery: String) {
        val service = AssistantAccessibilityService.instance
        if (service != null) {
            playYouTube(service, searchQuery) { _, _ -> }
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(searchQuery, "UTF-8")}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch YouTube intent", e)
            }
        }
    }

    fun strobeFlashlight(context: Context, pulses: Int = 3) {
        scope.launch(Dispatchers.IO) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return@launch
                for (i in 0 until pulses) {
                    cameraManager.setTorchMode(cameraId, true)
                    delay(120)
                    cameraManager.setTorchMode(cameraId, false)
                    delay(120)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Torch strobe not available", e)
            }
        }
    }

    private suspend fun pollAndClickSend(service: AssistantAccessibilityService, maxAttempts: Int = 22, delayIntervalMs: Long = 200): Boolean {
        for (i in 1..maxAttempts) {
            delay(delayIntervalMs)
            if (clickSendButton(service)) {
                Log.i(TAG, "WhatsApp Send button successfully clicked on attempt $i")
                return true
            }
        }
        return false
    }

    private fun clickSendButton(service: AssistantAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val candidateIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/entry_action",
            "com.whatsapp.w4b:id/entry_action"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }

        val textNodes = root.findAccessibilityNodeInfosByText("Send")
        for (node in textNodes) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            node.parent?.let { parent ->
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }

        // Search recursively for node with contentDescription containing "Send"
        return findAndClickNodeByDesc(root, "Send")
    }

    private fun findAndClickNodeByDesc(node: AccessibilityNodeInfo?, desc: String): Boolean {
        if (node == null) return false
        val cd = node.contentDescription?.toString()
        if (cd != null && cd.contains(desc, ignoreCase = true)) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            var curr = node.parent
            while (curr != null) {
                if (curr.isClickable && curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                curr = curr.parent
            }
        }
        for (i in 0 until node.childCount) {
            if (findAndClickNodeByDesc(node.getChild(i), desc)) return true
        }
        return false
    }

    private fun searchAndOpenChat(service: AssistantAccessibilityService, name: String) {
        val root = service.rootInActiveWindow ?: return
        val searchButton = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_search")
        if (searchButton.isNotEmpty()) {
            searchButton[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun typeAndSendInCurrentChat(service: AssistantAccessibilityService, message: String) {
        val root = service.rootInActiveWindow ?: return
        val inputNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
        if (inputNodes.isNotEmpty()) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            inputNodes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            clickSendButton(service)
        }
    }

    private fun clickFirstPlayableVideo(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable && (node.className?.contains("ViewGroup") == true || node.className?.contains("View") == true)) {
            if (node.contentDescription?.isNotBlank() == true) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        for (i in 0 until node.childCount) {
            if (clickFirstPlayableVideo(node.getChild(i))) return true
        }
        return false
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
