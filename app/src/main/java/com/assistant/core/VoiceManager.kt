package com.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureBritishMaleVoice()
            isTtsReady = true
            Log.i(TAG, "Jarvis British Male TTS Engine Initialized.")
        }
    }

    private fun configureBritishMaleVoice() {
        val ukLocale = Locale.UK
        tts?.language = ukLocale

        // Look for British Male voice in installed system voice list
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                val maleUkVoice = voices.firstOrNull { voice ->
                    val isUk = voice.locale.country.equals("GB", ignoreCase = true) || voice.locale.language.equals("en", ignoreCase = true)
                    val isMale = voice.name.contains("male", ignoreCase = true) ||
                                 voice.name.contains("en-gb-x-rjs#male", ignoreCase = true) ||
                                 voice.name.contains("en-gb-x-gba", ignoreCase = true)
                    isUk && isMale
                } ?: voices.firstOrNull { voice ->
                    voice.locale.country.equals("GB", ignoreCase = true) && !voice.name.contains("female", ignoreCase = true)
                }

                if (maleUkVoice != null) {
                    tts?.voice = maleUkVoice
                }
            }
        }

        // Set authentic British male baritone pitch & smooth speech rate
        tts?.setPitch(0.84f)
        tts?.setSpeechRate(1.02f)
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE_UTTERANCE")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun listen(context: Context, onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        // Stop any active speech before listening to prevent microphone feedback collision
        stopSpeaking()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition unavailable.")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onError?.invoke("Code: $error")
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
