package com.assistant.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingAssistantService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private lateinit var brain: AssistantBrain

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = AssistantBrain(this)
        VoiceManager.init(this)
        setupFloatingView()
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = (event.rawX - initialTouchX).toInt()
                    val diffY = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) {
                        isClick = false
                    }
                    params.x = initialX + diffX
                    params.y = initialY + diffY
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleClicked()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {}
    }

    private fun onBubbleClicked() {
        triggerHapticFeedback()
        VoiceManager.resetSpeechTurn()

        VoiceManager.listen(
            context = this,
            onResult = { spokenText ->
                serviceScope.launch {
                    val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                    val apiKey = prefs.getString("gemini_api_key", null)
                    val (_, actions) = brain.processCommandStream(spokenText, apiKey) { chunk ->
                        VoiceManager.streamSentence(chunk)
                    }
                    if (actions.isNotEmpty()) {
                        val dm = DeviceManager(this@FloatingAssistantService)
                        for (action in actions) {
                            when (action.type) {
                                ActionType.FLASHLIGHT_TOGGLE -> dm.toggleTorch(action.value != "false")
                                ActionType.CALL_PHONE -> action.target?.let { dm.makeCall(it) }
                                ActionType.PLAY_YOUTUBE, ActionType.PLAY_SPOTIFY -> action.target?.let { GhostPilot.playYouTube(this@FloatingAssistantService, it) }
                                ActionType.SEND_WHATSAPP -> action.target?.let { GhostPilot.sendWhatsApp(this@FloatingAssistantService, it, action.value ?: "") }
                                ActionType.OPEN_APP -> {
                                    val pkg = action.target
                                    if (pkg != null) {
                                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                                        if (launchIntent != null) {
                                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            startActivity(launchIntent)
                                        }
                                    }
                                }
                                ActionType.COMMUNICATION_UPDATES -> {
                                    val report = AssistantNotificationService.getChannelUpdatesReport(this@FloatingAssistantService)
                                    val spoken = AssistantNotificationService.formatSpokenUpdates(report)
                                    VoiceManager.speak(spoken)
                                }
                                ActionType.SPEAK -> action.value?.let { VoiceManager.speak(it) }
                                else -> AssistantAccessibilityService.instance?.executeActions(listOf(action))
                            }
                        }
                    }
                }
            },
            onError = { _ -> }
        )
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {}
        }
    }
}
