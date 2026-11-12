package com.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var hasStartedSpeakingCurrentTurn = false

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

        // Authentic British male baritone pitch & speech rate
        tts?.setPitch(0.84f)
        tts?.setSpeechRate(1.02f)
    }

    // --- Sub-Second Streaming Audio Queue ---

    fun resetSpeechTurn() {
        hasStartedSpeakingCurrentTurn = false
        stopSpeaking()
    }

    fun streamSentence(sentence: String) {
        if (!isTtsReady || sentence.isBlank()) return
        val clean = sentence.trim()

        if (!hasStartedSpeakingCurrentTurn) {
            // First chunk: Flush any previous audio and play immediately (<400ms)
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_STREAM_FIRST")
            hasStartedSpeakingCurrentTurn = true
        } else {
            // Subsequent chunks: Queue seamlessly behind the current sentence
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, "JARVIS_STREAM_QUEUE")
        }
    }

    fun speak(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE_UTTERANCE")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun listen(context: Context, onResult: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        // Stop any active speech before listening to prevent microphone feedback
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

            // INSTANT CUTOFF: Stop listening 400ms after user finishes talking (removes 1.5s lag)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
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
