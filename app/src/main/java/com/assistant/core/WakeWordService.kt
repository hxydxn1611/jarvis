package com.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Hands-Free Background Hotword Acoustic Array for J.A.R.V.I.S.
 * Continuously monitors ambient audio for "Hey Jarvis" or "Jarvis",
 * even when the screen is locked, enabling Tony Stark-level zero-touch interaction.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 1002
        private val WAKE_WORD_REGEX = Regex("""\b(hey\s+|ok\s+|hello\s+)?jarvis\b""", RegexOption.IGNORE_CASE)

        @Volatile
        var isListening = false
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var brain: AssistantBrain
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = AssistantBrain(this)
        VoiceManager.init(this)

        acquireWakeLock()
        startForegroundWithNotification()
        startAcousticListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (speechRecognizer == null || !isListening) {
            startAcousticListening()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jarvis::AmbientAcousticArray"
            )?.apply {
                acquire(10 * 60 * 1000L) // 10 minutes rolling lock
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock", e)
        }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Ambient Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hands-free continuous wake-word acoustic array"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Acoustic Array Active")
            .setContentText("Acoustic sensor online // Listening for 'Hey Jarvis'")
            .setSmallIcon(R.drawable.arc_reactor_bubble)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAcousticListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available on device")
            return
        }

        speechRecognizer?.destroy()
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        isListening = false
                        // Calm 1200ms delay to avoid loop thrashing
                        scope.launch {
                            delay(1200)
                            if (!VoiceManager.isCurrentlySpeaking()) {
                                restartRecognizer()
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                        handleDetectedMatches(matches)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        // Ignored to avoid intermediate false-positives
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            restartRecognizer()
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer initialization failed in background service", e)
            isListening = false
        }
    }

    private fun restartRecognizer() {
        if (VoiceManager.isCurrentlySpeaking()) {
            VoiceManager.onTurnComplete {
                scope.launch {
                    delay(500)
                    restartRecognizer()
                }
            }
            return
        }

        try {
            startAcousticListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart acoustic listener", e)
        }
    }

    private fun handleDetectedMatches(candidates: List<String>) {
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val customSignatures = prefs.getStringSet("custom_wake_signatures", emptySet()) ?: emptySet()

        var matched = false
        var command = ""

        val wakeRegexes = listOf(
            Regex("""\b(hey|hi|hello|ok|okay|yo|listen|wake\s+up)?\s*(jarvis|jervis|jarves|javis|jarviz|java|javus|javes|tarvis|starvis|service|harvest|travis|charvis|charles|chavez|gervais)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(jarvis|jervis|jarves|javis|jarviz|java|javus|javes|tarvis|starvis)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bj\.a\.r\.v\.i\.s\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwake\s+up\b""", RegexOption.IGNORE_CASE)
        )

        for (candidate in candidates) {
            val text = candidate.trim()
            for (custom in customSignatures) {
                if (text.contains(custom, ignoreCase = true)) {
                    matched = true
                    command = text.substringAfter(custom, "").trim().removePrefix(",").trim()
                    break
                }
            }
            if (matched) break

            for (regex in wakeRegexes) {
                val match = regex.find(text)
                if (match != null) {
                    matched = true
                    command = text.substring(match.range.last + 1).trim().removePrefix(",").trim()
                    break
                }
            }
            if (matched) break
        }

        if (matched) {
            SoundEffectManager.playWakeChime()

            if (command.isNotBlank()) {
                scope.launch {
                    val apiKey = prefs.getString("gemini_api_key", null)
                    val (_, actions) = brain.processCommandStream(command, apiKey) { chunk ->
                        VoiceManager.streamSentence(chunk)
                    }
                    if (actions.isNotEmpty()) {
                        val dm = DeviceManager(this@WakeWordService)
                        for (action in actions) {
                            when (action.type) {
                                ActionType.FLASHLIGHT_TOGGLE -> dm.toggleTorch(action.value != "false")
                                ActionType.CALL_PHONE -> action.target?.let { dm.makeCall(it) }
                                ActionType.PLAY_YOUTUBE, ActionType.PLAY_SPOTIFY -> action.target?.let { GhostPilot.playYouTube(this@WakeWordService, it) }
                                ActionType.SEND_WHATSAPP -> action.target?.let { GhostPilot.sendWhatsApp(this@WakeWordService, it, action.value ?: "") }
                                ActionType.CAST_TO_TV -> {
                                    val query = action.target ?: "Trending"
                                    val targetIp = action.value ?: SmartTvManager.activeTv?.ip ?: ""
                                    SmartTvManager.castToTv(targetIp, query) { _, _ -> }
                                }
                                ActionType.CONTROL_TV -> {
                                    val cmd = action.value ?: "VOL_UP"
                                    val targetIp = SmartTvManager.activeTv?.ip ?: ""
                                    SmartTvManager.sendTvKey(targetIp, cmd) { _, _ -> }
                                }
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
                                else -> AssistantAccessibilityService.instance?.executeActions(listOf(action))
                            }
                        }
                    }
                    VoiceManager.onTurnComplete {
                        scope.launch {
                            delay(700)
                            restartRecognizer()
                        }
                    }
                }
            } else {
                val bringToFront = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("AUTO_LISTEN", true)
                }
                startActivity(bringToFront)

                VoiceManager.speak("At your service, Sir.") {
                    scope.launch {
                        delay(600)
                        restartRecognizer()
                    }
                }
            }
        } else {
            scope.launch {
                delay(800)
                restartRecognizer()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
    }
}
