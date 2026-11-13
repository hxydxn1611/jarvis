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
import android.speech.tts.Voice
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
    private var lastQueuedUtteranceId: String? = null
    private val speechCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var turnCompletionCallback: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts == null) {
            try {
                val hasGoogleTts = try {
                    context.packageManager.getPackageInfo("com.google.android.tts", 0) != null
                } catch (e: Exception) {
                    false
                }
                Log.i(TAG, "Initializing System TextToSpeech Engine (hasGoogleTts=$hasGoogleTts)")
                tts = if (hasGoogleTts) {
                    TextToSpeech(context.applicationContext, this, "com.google.android.tts")
                } else {
                    TextToSpeech(context.applicationContext, this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS initialization failed, falling back to default engine", e)
                try {
                    tts = TextToSpeech(context.applicationContext, this)
                } catch (e2: Exception) {
                    Log.e(TAG, "Default TTS initialization failed", e2)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureBritishMaleVoice()
            setupUtteranceListener()
            isTtsReady = true
            Log.i(TAG, "Jarvis British Male TTS Engine Online.")
        }
    }

    private fun configureBritishMaleVoice() {
        val ukLocale = Locale.UK
        tts?.language = ukLocale

        // Strictly search for British / English MALE voice
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                val femaleBlacklist = listOf(
                    "female", "woman", "girl", "gba", "gbc", "gbe", 
                    "sfa", "sfc", "sfe", "ioa", "ioc", "ioe", 
                    "_f0", "-f0", "#female", "zira", "eva", "victoria", "fis", "f0"
                )

                val maleWhitelist = listOf(
                    "rjs", "gbb", "gbd", "sfb", "sfd", "iom", "iol",
                    "male", "man", "#male", "_m0", "-m0", "_m", "-m",
                    "en-gb-x-rjs", "en-gb-x-gbd", "wavenet-b", "wavenet-d", 
                    "standard-b", "standard-d", "b-local", "d-local"
                )

                // 1. UK Male Voice (Highest Priority - True British Jarvis)
                val ukMale = voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    val isUk = voice.locale.country.equals("GB", ignoreCase = true) || 
                               name.contains("en-gb") || name.contains("en_gb")
                    val isMale = maleWhitelist.any { name.contains(it) }
                    val isNotFemale = femaleBlacklist.none { name.contains(it) }
                    isUk && isMale && isNotFemale
                }

                // 2. Any English Male Voice (fallback)
                val anyEnglishMale = voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    val isEnglish = voice.locale.language.equals("en", ignoreCase = true)
                    val isMale = maleWhitelist.any { name.contains(it) }
                    val isNotFemale = femaleBlacklist.none { name.contains(it) }
                    isEnglish && isMale && isNotFemale
                }

                // 3. Fallback: Any voice strictly NOT on the female blacklist
                val nonFemaleUk = voices.firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    val isUk = voice.locale.country.equals("GB", ignoreCase = true) || name.contains("en-gb")
                    val isNotFemale = femaleBlacklist.none { name.contains(it) }
                    isUk && isNotFemale
                }

                val selectedVoice = ukMale ?: anyEnglishMale ?: nonFemaleUk
                if (selectedVoice != null) {
                    tts?.voice = selectedVoice
                    Log.i(TAG, "Selected Male TTS Voice: ${selectedVoice.name} (${selectedVoice.locale})")
                }
            }
        }

        // Deep baritone British pitch (0.68f) and natural speech tempo
        tts?.setPitch(0.68f)
        tts?.setSpeechRate(0.98f)
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

                val isLast = utteranceId != null && utteranceId == lastQueuedUtteranceId
                if (isLast || (speechCallbacks.isEmpty() && tts?.isSpeaking != true)) {
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
        lastQueuedUtteranceId = null
        stopSpeaking()
    }

    fun streamSentence(sentence: String) {
        if (!isTtsReady || sentence.isBlank()) return
        val clean = sentence.trim()

        tts?.setPitch(0.68f)
        tts?.setSpeechRate(0.98f)

        val utteranceId = "STREAM_${System.currentTimeMillis()}_${clean.hashCode()}"
        lastQueuedUtteranceId = utteranceId
        if (!hasStartedSpeakingCurrentTurn) {
            // First chunk: Flush any previous audio and play immediately (<400ms)
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            hasStartedSpeakingCurrentTurn = true
        } else {
            // Subsequent chunks: Queue seamlessly behind current sentence
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.setPitch(0.68f)
            tts?.setSpeechRate(0.98f)
            val utteranceId = "UTTER_${System.currentTimeMillis()}"
            lastQueuedUtteranceId = utteranceId
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

    fun listen(
        context: Context,
        onPartialResult: ((String) -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        // Stop any active speech before listening to prevent acoustic feedback
        stopSpeaking()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition unavailable.")
            return
        }

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val deviceTag = Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, deviceTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, deviceTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

                // Generous 1600ms silence length so user is not cut off mid-thought
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    try {
                        onError?.invoke("Code: $error")
                    } finally {
                        try { recognizer.destroy() } catch (e: Exception) {}
                    }
                }
                override fun onResults(results: Bundle?) {
                    try {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            onResult(matches[0])
                        } else {
                            onError?.invoke("No speech detected.")
                        }
                    } finally {
                        try { recognizer.destroy() } catch (e: Exception) {}
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partials.isNullOrEmpty()) {
                        val best = partials[0]
                        if (best.isNotBlank()) {
                            onPartialResult?.invoke(best)
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer listen error", e)
            onError?.invoke(e.message ?: "Speech recognizer failure")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        isSpeaking = false
    }
}
