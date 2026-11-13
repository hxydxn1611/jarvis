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
                var phone = targetContactOrNumber.replace(Regex("[^0-9+]"), "")
                
                // If not numeric or too short, resolve from device contacts book
                if (phone.length < 10) {
                    val lookedUp = deviceManager.lookupContactNumber(targetContactOrNumber)
                    if (!lookedUp.isNullOrBlank()) {
                        phone = lookedUp.replace(Regex("[^0-9+]"), "")
                    }
                }

                // If 10 digits without country code, default to international format (+91 for India)
                if (phone.length == 10 && !phone.startsWith("91")) {
                    phone = "91$phone"
                }
                if (phone.startsWith("+")) {
                    phone = phone.removePrefix("+")
                }

                if (phone.length >= 10) {
                    val encoded = URLEncoder.encode(message, "UTF-8")
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setPackage("com.whatsapp")
                    }
                    service.startActivity(intent)

                    // Wait for WhatsApp to open and load the message input field
                    delay(1800)

                    // Find and click the WhatsApp send button
                    val clicked = clickSendButton(service)
                    delay(1000)
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
            var phone = targetContactOrNumber.replace(Regex("[^0-9+]"), "")
            if (phone.length < 10) {
                val lookedUp = dm.lookupContactNumber(targetContactOrNumber)
                if (!lookedUp.isNullOrBlank()) {
                    phone = lookedUp.replace(Regex("[^0-9+]"), "")
                }
            }
            if (phone.length == 10 && !phone.startsWith("91")) {
                phone = "91$phone"
            }
            if (phone.startsWith("+")) {
                phone = phone.removePrefix("+")
            }

            if (phone.length >= 10) {
                val encoded = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encoded")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                context.startActivity(intent)

                // If accessibility service is active, auto-click send after chat opens
                val service = AssistantAccessibilityService.instance
                if (service != null) {
                    scope.launch {
                        delay(1200)
                        clickSendButton(service)
                    }
                }
                onComplete?.invoke(true, "Transmitting WhatsApp message to $targetContactOrNumber, Sir.")
            } else {
                // Pre-fill message via WhatsApp share intent
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(sendIntent)
                    onComplete?.invoke(true, "Opening WhatsApp for $targetContactOrNumber with message queued, Sir.")
                } catch (e2: Exception) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        onComplete?.invoke(true, "Opening WhatsApp, Sir.")
                    } else {
                        onComplete?.invoke(false, "WhatsApp is not installed on this terminal, Sir.")
                    }
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

    private fun clickSendButton(service: AssistantAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        // WhatsApp Send button typically has content description "Send" or id "send"
        val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (sendNodes.isNotEmpty()) {
            return sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val textNodes = root.findAccessibilityNodeInfosByText("Send")
        if (textNodes.isNotEmpty()) {
            return textNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Search recursively for node with contentDescription "Send"
        return findAndClickNodeByDesc(root, "Send")
    }

    private fun findAndClickNodeByDesc(node: AccessibilityNodeInfo?, desc: String): Boolean {
        if (node == null) return false
        if (node.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
}
