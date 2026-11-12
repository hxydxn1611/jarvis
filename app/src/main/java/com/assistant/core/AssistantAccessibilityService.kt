package com.assistant.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        deviceManager = DeviceManager(this)
        Log.i(TAG, "Jarvis Accessibility Service connected & active.")
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
                    ActionType.SPEAK -> {
                        action.value?.let { text ->
                            VoiceManager.speak(text)
                        }
                    }
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
