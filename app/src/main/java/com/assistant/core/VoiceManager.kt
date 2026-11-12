package com.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object VoiceManager : TextToSpeech.OnInitListener {

    private const val TAG = "VoiceManager"
    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set

    @Volatile
    var isSpeaking = false
        private set

    private var hasStartedSpeakingCurrentTurn = false
    private val speechCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var turnCompletionCallback: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureBritishMaleVoice()
            setupUtteranceListener()
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

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    val cb = speechCallbacks.remove(utteranceId)
                    cb?.invoke()
                }

                // If no more utterances queued
                if (speechCallbacks.isEmpty() && tts?.isSpeaking != true) {
                    isSpeaking = false
                    val finalCb = turnCompletionCallback
                    turnCompletionCallback = null
                    finalCb?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId != null) {
                    speechCallbacks.remove(utteranceId)
                }
                isSpeaking = false
                val finalCb = turnCompletionCallback
                turnCompletionCallback = null
                finalCb?.invoke()
            }
        })
    }

    fun isCurrentlySpeaking(): Boolean {
        return isSpeaking || (tts?.isSpeaking == true)
    }

    // --- Sub-Second Streaming Audio Queue ---

    fun resetSpeechTurn() {
        hasStartedSpeakingCurrentTurn = false
        speechCallbacks.clear()
        turnCompletionCallback = null
        stopSpeaking()
    }

    fun streamSentence(sentence: String) {
        if (!isTtsReady || sentence.isBlank()) return
        val clean = sentence.trim()

        val utteranceId = "STREAM_${System.currentTimeMillis()}_${clean.hashCode()}"
        if (!hasStartedSpeakingCurrentTurn) {
            // First chunk: Flush any previous audio and play immediately (<400ms)
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            hasStartedSpeakingCurrentTurn = true
        } else {
            // Subsequent chunks: Queue seamlessly behind the current sentence
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (isTtsReady && text.isNotBlank()) {
            val utteranceId = "UTTER_${System.currentTimeMillis()}"
            if (onDone != null) {
                speechCallbacks[utteranceId] = onDone
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            onDone?.invoke()
        }
    }

    fun onTurnComplete(onDone: (() -> Unit)? = null) {
        if (onDone == null) return
        if (isCurrentlySpeaking()) {
            turnCompletionCallback = onDone
        } else {
            onDone()
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
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

            // INSTANT CUTOFF: Stop listening 450ms after user finishes talking
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
        isSpeaking = false
    }
}
