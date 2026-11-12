package com.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
 * Continuously monitors the ambient audio environment for "Hey Jarvis" or "Jarvis",
 * even when the screen is locked, enabling Tony Stark-level zero-touch interaction.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wakeword_channel"
        private const val NOTIFICATION_ID = 2002
        var isListening = false
            private set
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var brain: AssistantBrain

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        brain = AssistantBrain(this)
        VoiceManager.init(this)
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startAcousticListening()
    }

    private fun startAcousticListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available on device")
            return
        }

        speechRecognizer?.destroy()
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
                    // Re-arm listener after brief cooldown (unless destroyed)
                    scope.launch {
                        delay(600)
                        restartRecognizer()
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.lowercase() ?: ""
                    handleDetectedUtterance(spoken)
                    
                    scope.launch {
                        delay(800)
                        restartRecognizer()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = partialMatches?.firstOrNull()?.lowercase() ?: ""
                    if (partial.contains("jarvis") || partial.contains("hey jarvis")) {
                        // Immediate fast trigger
                        speechRecognizer?.stopListening()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        restartRecognizer()
    }

    private fun restartRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
        }
    }

    private fun handleDetectedUtterance(text: String) {
        if (text.contains("jarvis")) {
            SoundEffectManager.playWakeChime()
            
            // Extract trailing command if user said "Hey Jarvis what time is it"
            val command = text.substringAfter("jarvis").trim().removePrefix(",").trim()

            if (command.isNotBlank()) {
                scope.launch {
                    val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                    val apiKey = prefs.getString("gemini_api_key", null)
                    val (_, actions) = brain.processCommandStream(command, apiKey) { chunk ->
                        VoiceManager.streamSentence(chunk)
                    }
                    if (actions.isNotEmpty()) {
                        AssistantAccessibilityService.instance?.executeActions(actions)
                    }
                }
            } else {
                VoiceManager.speak("At your service, Sir.")
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Acoustic Array",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hands-free continuous wake-word detector"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Listening Array Active")
            .setContentText("Say 'Hey Jarvis' from anywhere to activate.")
            .setSmallIcon(R.drawable.arc_reactor_bubble)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
