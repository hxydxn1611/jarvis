package com.assistant.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AssistantAccess"
        var instance: AssistantAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var deviceManager: DeviceManager

    private var hasAlertedLowBattery = false
    private var lastSoftwareUpdateText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        deviceManager = DeviceManager(this)
        Log.i(TAG, "Jarvis Accessibility Service connected & active.")
        startProactiveAuditor()
    }

    private fun startProactiveAuditor() {
        serviceScope.launch(Dispatchers.Default) {
            while (isRunning) {
                delay(30_000)
                try {
                    val level = deviceManager.getBatteryLevel()
                    if (level in 1..15 && !hasAlertedLowBattery) {
                        hasAlertedLowBattery = true
                        SoundEffectManager.playAlertChime()
                        VoiceManager.speak("Pardon the interruption, Sir. Power reserves have depleted to $level percent. Recommend connecting to an external power supply.")
                    } else if (level > 25) {
                        hasAlertedLowBattery = false
                    }

                    val updateNotif = AssistantNotificationService.checkSystemSoftwareUpdate()
                    if (updateNotif != null && updateNotif.text != lastSoftwareUpdateText) {
                        lastSoftwareUpdateText = updateNotif.text
                        SoundEffectManager.playAlertChime()
                        delay(400)
                        VoiceManager.speak("Pardon the interruption, Sir. A Samsung One UI system software update has arrived: ${updateNotif.text}")
                    }

                    val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                    val bridgeUrl = prefs.getString("bridge_url", "")
                    if (!bridgeUrl.isNullOrBlank()) {
                        val jobResult = withContext(Dispatchers.IO) {
                            deviceManager.dispatchDesktopCommand(bridgeUrl, "Get-Content -Path .jarvis_last_job.txt -ErrorAction SilentlyContinue")
                        }
                        if (jobResult.isNotBlank() && jobResult.contains("[JOB_COMPLETED:")) {
                            val jobTitle = jobResult.substringAfter("[JOB_COMPLETED:").substringBefore("]")
                            withContext(Dispatchers.IO) {
                                deviceManager.dispatchDesktopCommand(bridgeUrl, "Remove-Item .jarvis_last_job.txt -ErrorAction SilentlyContinue")
                            }
                            SoundEffectManager.playConfirmChirp()
                            VoiceManager.speak("Sir, your workstation has completed the background task: $jobTitle.")
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- Screen Reading / Perception ---

    fun dumpVisibleText(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val textList = mutableListOf<String>()
        collectText(root, textList)
        return textList
    }

    fun readVisibleEmails(limit: Int = 3): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val emails = mutableListOf<String>()

        fun scanNode(node: AccessibilityNodeInfo) {
            if (emails.size >= limit) return
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            if (desc.isNotBlank() && (desc.contains("unread", ignoreCase = true) || desc.contains("sender", ignoreCase = true) || desc.contains("subject", ignoreCase = true))) {
                val clean = desc.replace(Regex("""\b(unread|read|attachment|star|starred)\b""", RegexOption.IGNORE_CASE), "").trim()
                if (clean.length > 8 && !emails.contains(clean)) {
                    emails.add(clean)
                }
            } else if (text.isNotBlank() && text.length > 8) {
                if (!emails.contains(text) && 
                    !text.equals("Search in mail", ignoreCase = true) && 
                    !text.equals("Primary", ignoreCase = true) &&
                    !text.equals("Social", ignoreCase = true) &&
                    !text.equals("Promotions", ignoreCase = true) &&
                    !text.startsWith("http")) {
                    emails.add(text)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { scanNode(it) }
            }
        }

        scanNode(root)
        return emails.take(limit)
    }

    private fun collectText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { list.add(it) }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, list)
            }
        }
    }

    // --- Action Execution ---

    fun executeActions(actions: List<AssistantAction>, onComplete: ((Boolean) -> Unit)? = null) {
        serviceScope.launch {
            for (action in actions) {
                when (action.type) {
                    ActionType.OPEN_APP -> {
                        action.target?.let { pkg ->
                            val intent = packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                delay(1200)
                            }
                        }
                    }
                    ActionType.CLICK_TEXT -> {
                        action.target?.let { text ->
                            clickNodeByText(text)
                            delay(500)
                        }
                    }
                    ActionType.CLICK_ID -> {
                        action.target?.let { id ->
                            clickNodeById(id)
                            delay(500)
                        }
                    }
                    ActionType.TYPE_TEXT -> {
                        action.value?.let { text ->
                            typeTextIntoFocusedOrTarget(text, action.target)
                            delay(500)
                        }
                    }
                    ActionType.CALL_PHONE -> {
                        action.target?.let { number ->
                            deviceManager.makeCall(number)
                            delay(800)
                        }
                    }
                    ActionType.SEND_EMAIL -> {
                        val recipient = action.target ?: ""
                        val subject = action.value ?: "Message from Jarvis"
                        val body = action.extra ?: ""
                        deviceManager.sendEmail(recipient, subject, body)
                        delay(800)
                    }
                    ActionType.SET_ALARM -> {
                        val hour = action.target?.toIntOrNull() ?: 7
                        val minute = action.value?.toIntOrNull() ?: 0
                        val msg = action.extra ?: "Alarm"
                        deviceManager.setAlarm(hour, minute, msg)
                        delay(600)
                    }
                    ActionType.PLAY_SPOTIFY -> {
                        action.target?.let { query ->
                            deviceManager.playMusic(query)
                            delay(800)
                        }
                    }
                    ActionType.FLASHLIGHT_TOGGLE -> {
                        val enable = action.value != "false"
                        deviceManager.toggleTorch(enable)
                    }
                    ActionType.SAVE_MEMORY -> {
                        if (!action.target.isNullOrBlank() && !action.value.isNullOrBlank()) {
                            MemoryCore.saveFact(this@AssistantAccessibilityService, action.target, action.value)
                        }
                    }
                    ActionType.SCROLL_DOWN -> {
                        performScroll(forward = true)
                        delay(600)
                    }
                    ActionType.SCROLL_UP -> {
                        performScroll(forward = false)
                        delay(600)
                    }
                    ActionType.GLOBAL_HOME -> {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        delay(400)
                    }
                    ActionType.GLOBAL_BACK -> {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(400)
                    }
                    ActionType.GLOBAL_RECENTS -> {
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                        delay(400)
                    }
                    ActionType.GLOBAL_NOTIFICATIONS -> {
                        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                        delay(400)
                    }
                    ActionType.TAKE_SCREENSHOT -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                        }
                    }
                    ActionType.DISPATCH_DESKTOP_COMMAND -> {
                        action.target?.let { cmd ->
                            val prefs = getSharedPreferences("AssistantPrefs", android.content.Context.MODE_PRIVATE)
                            val bridgeUrl = prefs.getString("bridge_url", "") ?: ""
                            if (bridgeUrl.isNotBlank()) {
                                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    deviceManager.dispatchDesktopCommand(bridgeUrl, cmd)
                                }
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    VoiceManager.speak(result)
                                }
                            } else {
                                VoiceManager.speak("Workstation bridge is not configured, Sir. You can enter your PC bridge URL in Jarvis settings.")
                            }
                        }
                    }
                    ActionType.INSPECT_SCREEN -> {
                        val elements = dumpVisibleText().take(20).joinToString(", ")
                        VoiceManager.speak("Screen inspection indicates: $elements, Sir.")
                    }
                    ActionType.MORNING_BRIEFING -> {
                        val time = deviceManager.getFormattedSystemTime()
                        val batt = deviceManager.getBatteryLevel()
                        val update = AssistantNotificationService.checkSystemSoftwareUpdate()?.text ?: "Up to date"
                        val brief = "Good morning, Sir. It is $time. Power reserves stand at $batt percent. Samsung One UI system status: $update. Standing by for instructions."
                        VoiceManager.speak(brief)
                    }
                    ActionType.SEND_WHATSAPP -> {
                        val target = action.target ?: ""
                        val msg = action.value ?: ""
                        GhostPilot.sendWhatsApp(this@AssistantAccessibilityService, target, msg) { _, speech ->
                            VoiceManager.speak(speech)
                        }
                    }
                    ActionType.PLAY_YOUTUBE -> {
                        val query = action.target ?: action.value ?: ""
                        GhostPilot.playYouTube(this@AssistantAccessibilityService, query) { _, speech ->
                            VoiceManager.speak(speech)
                        }
                    }
                    ActionType.TOGGLE_SENTRY_MODE -> {
                        val enable = action.value == "true"
                        SecuritySentinel.setSentryMode(this@AssistantAccessibilityService, enable)
                    }
                    ActionType.SPEAK -> {
                        action.value?.let { text ->
                            VoiceManager.speak(text)
                        }
                    }
                    ActionType.CAST_TO_TV -> {
                        val query = action.target ?: "Trending"
                        val targetIp = action.value ?: SmartTvManager.activeTv?.ip ?: ""
                        SmartTvManager.castToTv(targetIp, query) { _, msg ->
                            VoiceManager.speak(msg)
                        }
                    }
                    ActionType.CONTROL_TV -> {
                        val cmd = action.value ?: "VOL_UP"
                        val targetIp = SmartTvManager.activeTv?.ip ?: ""
                        SmartTvManager.sendTvKey(targetIp, cmd) { _, msg ->
                            VoiceManager.speak(msg)
                        }
                    }
                    ActionType.SCAN_WIFI -> {
                        SmartTvManager.scanLocalNetwork(this@AssistantAccessibilityService) { tvs ->
                            VoiceManager.speak("WiFi network scan found ${tvs.size} Smart TV displays, Sir.")
                        }
                    }
                    ActionType.SHOW_TV_REMOTE -> {
                        val intent = Intent(this@AssistantAccessibilityService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("SHOW_TV_REMOTE", true)
                        }
                        startActivity(intent)
                    }
                    ActionType.SMART_VIEW -> {
                        SmartTvManager.launchScreenMirroring(this@AssistantAccessibilityService)
                    }
                    else -> {}
                }
            }
            onComplete?.invoke(true)
        }
    }

    private fun clickNodeByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            if (performClickOnNodeOrParent(node)) {
                return true
            }
        }
        return false
    }

    private fun clickNodeById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (performClickOnNodeOrParent(node)) {
                return true
            }
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun typeTextIntoFocusedOrTarget(text: String, targetHint: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null

        if (!targetHint.isNullOrBlank()) {
            val matching = root.findAccessibilityNodeInfosByText(targetHint)
            targetNode = matching.firstOrNull { it.isEditable || it.isFocusable }
        }

        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    private fun performScroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val scrollable = findScrollable(node.getChild(i))
                if (scrollable != null) return scrollable
            }
            return null
        }

        val scrollable = findScrollable(root)
        return scrollable?.performAction(action) ?: false
    }

    fun dispatchTapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
