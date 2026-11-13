package com.assistant.core

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.assistant.core.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var brain: AssistantBrain
    private val scope = CoroutineScope(Dispatchers.Main)
    private var coreAnimator: ObjectAnimator? = null

    // Ambient Hands-Free Wake Engine
    private var isHandsFreeEnabled = false
    private var ambientRecognizer: SpeechRecognizer? = null
    private var isAmbientListening = false
    private var isProcessingCommand = false
    private var activeTurnId = 0L
    private var isWakeAcknowledged = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS
    )

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            handleVisualScan(bitmap)
        } else {
            Toast.makeText(this, "Visual scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JarvisCrash", "Uncaught exception in ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        isHandsFreeEnabled = prefs.getBoolean("hands_free_wake_word", true)
        if (!prefs.contains("hands_free_wake_word")) {
            prefs.edit().putBoolean("hands_free_wake_word", true).apply()
        }
        if (prefs.getString("gemini_api_key", "").isNullOrBlank()) {
            prefs.edit().putString("gemini_api_key", AssistantBrain.BUNDLED_API_KEY).apply()
        }

        brain = AssistantBrain(this)
        VoiceManager.init(this)
        SmartTvManager.scanLocalNetwork(this)

        startCoreAnimation()
        setupInteractions()
        updateHandsFreePill()
        checkPermissionsState()
        requestRuntimePermissionsIfNeeded()

        if (intent?.getBooleanExtra("AUTO_LISTEN", false) == true) {
            intent?.removeExtra("AUTO_LISTEN")
            triggerVoiceListening()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("AUTO_LISTEN", false) == true) {
            intent?.removeExtra("AUTO_LISTEN")
            triggerVoiceListening()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
        isProcessingCommand = false
        VoiceManager.stopSpeaking()

        // Proactively scan WiFi mesh for Smart TVs and appliances in background
        if (SmartTvManager.activeTv == null) {
            SmartTvManager.scanLocalNetwork(this)
        }

        // Stop background wake service when app is in foreground to prevent mic collisions
        try {
            stopService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {}

        if (isHandsFreeEnabled) {
            startAmbientWakeWordListener()
        } else {
            binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
        }
    }

    override fun onPause() {
        super.onPause()
        stopAmbientWakeWordListener()

        // Hand over acoustic sensor to background service when app moves to background
        if (isHandsFreeEnabled) {
            try {
                val wakeIntent = Intent(this, WakeWordService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, wakeIntent)
                } else {
                    startService(wakeIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start background WakeWordService", e)
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val perms = requiredPermissions.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 || requestCode == 101) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (isHandsFreeEnabled && !isProcessingCommand) {
                    startAmbientWakeWordListener()
                }
            }
        }
    }

    private fun startCoreAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 1.0f, 0.6f)

        coreAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.viewCoreGlow, scaleX, scaleY, alpha).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun setupInteractions() {
        // Tap on Center Living Core to talk
        binding.coreContainer.setOnClickListener {
            isProcessingCommand = false
            VoiceManager.stopSpeaking()
            VoiceManager.resetSpeechTurn()
            triggerVoiceListening()
        }

        // Tap on Mic FAB
        binding.fabVoice.setOnClickListener {
            isProcessingCommand = false
            VoiceManager.stopSpeaking()
            VoiceManager.resetSpeechTurn()
            triggerVoiceListening()
        }

        // Tap on Vision Camera FAB
        binding.fabVision.setOnClickListener {
            triggerVisualScan()
        }

        // Hands-Free Toggle Pill
        binding.tvHandsFreePill.setOnClickListener {
            toggleHandsFree()
        }

        // Quick System Controls
        binding.chipCalibrateVoice.setOnClickListener {
            startVoiceCalibration()
        }
        binding.chipVoiceTts.setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {}
            }
        }
        binding.chipSmartTv.setOnClickListener {
            triggerSmartTvScan()
        }
        binding.chipDiagnostics.setOnClickListener {
            executeDiagnosticsProtocol()
        }

        // Send Text command via Keyboard
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = binding.etCommand.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    binding.etCommand.text?.clear()
                    handleUserInput(text)
                }
                true
            } else false
        }

        // Settings Dialog
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Fix Permissions Banner button
        binding.btnFixPerms.setOnClickListener {
            if (!AssistantAccessibilityService.isRunning) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Enable 'Jarvis Assistant' under Installed Apps", Toast.LENGTH_LONG).show()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }

    private fun toggleHandsFree() {
        isHandsFreeEnabled = !isHandsFreeEnabled
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("hands_free_wake_word", isHandsFreeEnabled).apply()

        updateHandsFreePill()
        if (isHandsFreeEnabled) {
            Toast.makeText(this, "Hands-Free Active: Say 'Hey Jarvis'", Toast.LENGTH_SHORT).show()
            startAmbientWakeWordListener()
        } else {
            Toast.makeText(this, "Hands-Free Off: Tap Core to speak", Toast.LENGTH_SHORT).show()
            stopAmbientWakeWordListener()
            binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
        }
    }

    private fun updateHandsFreePill() {
        if (isHandsFreeEnabled) {
            binding.tvHandsFreePill.text = "🎤 WAKE: ARMED"
            binding.tvHandsFreePill.setTextColor(0xFF00E5FF.toInt())
        } else {
            binding.tvHandsFreePill.text = "🎤 WAKE: OFF"
            binding.tvHandsFreePill.setTextColor(0xFF888888.toInt())
        }
    }

    // --- Foreground Hands-Free Ambient Listening Array ---

    private fun startAmbientWakeWordListener() {
        if (!isHandsFreeEnabled || isProcessingCommand || VoiceManager.isCurrentlySpeaking() || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        runOnUiThread {
            if (!isProcessingCommand) {
                binding.tvCoreState.text = "STANDBY // SAY 'HEY JARVIS'"
                setCoreActive(false)
            }

            try {
                ambientRecognizer?.cancel()
                ambientRecognizer?.destroy()
                ambientRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isAmbientListening = true
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {
                            if (rmsdB > 2.0f && !VoiceManager.isCurrentlySpeaking()) {
                                val boost = 1.0f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.22f
                                runOnUiThread {
                                    try {
                                        binding.viewCoreGlow.scaleX = boost
                                        binding.viewCoreGlow.scaleY = boost
                                    } catch (e: Exception) {}
                                }
                            }
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            isAmbientListening = false
                            if (isHandsFreeEnabled && !isProcessingCommand && !isFinishing && !isDestroyed) {
                                scope.launch {
                                    // Silence/Speech timeout restarts immediately (<300ms) to ensure continuous readiness
                                    val isSilence = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                                    val restartDelay = if (isSilence) 250L else 1200L
                                    delay(restartDelay)
                                    if (isHandsFreeEnabled && !isProcessingCommand && !VoiceManager.isCurrentlySpeaking()) {
                                        restartAmbientListener()
                                    }
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isAmbientListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                            if (matches.isNotEmpty()) {
                                handleAmbientSpeechMatches(matches)
                            } else if (isHandsFreeEnabled && !isProcessingCommand) {
                                scope.launch {
                                    delay(200)
                                    restartAmbientListener()
                                }
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                            if (partials.isNotEmpty()) {
                                val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                                val customSignatures = prefs.getStringSet("custom_wake_signatures", emptySet()) ?: emptySet()
                                val (matched, _) = evaluateWakeMatch(partials, customSignatures)
                                if (matched && !isProcessingCommand && !VoiceManager.isCurrentlySpeaking()) {
                                    handleAmbientSpeechMatches(partials)
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                launchAmbientListeningIntent()
            } catch (e: Exception) {
                Log.e(TAG, "Ambient recognizer init error", e)
            }
        }
    }

    private fun restartAmbientListener() {
        if (!isHandsFreeEnabled || isProcessingCommand || VoiceManager.isCurrentlySpeaking() || isFinishing || isDestroyed) return
        startAmbientWakeWordListener()
    }

    private fun launchAmbientListeningIntent() {
        runOnUiThread {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    val tag = Locale.getDefault().toLanguageTag()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
                }
                ambientRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
            }
        }
    }

    private fun stopAmbientWakeWordListener() {
        isAmbientListening = false
        runOnUiThread {
            try {
                ambientRecognizer?.cancel()
                ambientRecognizer?.destroy()
                ambientRecognizer = null
            } catch (e: Exception) {}
        }
    }

    private fun handleAmbientSpeechMatches(matches: List<String>) {
        if (isProcessingCommand || VoiceManager.isCurrentlySpeaking()) return
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val customSignatures = prefs.getStringSet("custom_wake_signatures", emptySet()) ?: emptySet()

        val (matched, command) = evaluateWakeMatch(matches, customSignatures)
        if (matched) {
            if (command.isNotBlank()) {
                isWakeAcknowledged = false
                stopAmbientWakeWordListener()
                handleUserInput(command)
            } else {
                acknowledgeWake()
            }
        } else if (isWakeAcknowledged) {
            val bestCandidate = matches.firstOrNull { it.trim().length >= 2 }?.trim() ?: ""
            if (bestCandidate.isNotBlank()) {
                isWakeAcknowledged = false
                stopAmbientWakeWordListener()
                handleUserInput(bestCandidate)
            }
        } else {
            // Any natural English speech spoken while dashboard is active
            val bestCandidate = matches.firstOrNull { it.trim().length >= 2 }?.trim() ?: ""
            if (bestCandidate.isNotBlank()) {
                stopAmbientWakeWordListener()
                handleUserInput(bestCandidate)
            } else {
                scope.launch {
                    delay(250)
                    restartAmbientListener()
                }
            }
        }
    }

    private fun acknowledgeWake() {
        isWakeAcknowledged = true
        runOnUiThread {
            setCoreActive(true)
            binding.tvCoreState.text = "⚡ WAKE ACTIVE // LISTENING..."
            binding.tvTranscript.text = "You: Hey Jarvis\n\nJarvis: Yes, Sir? I'm listening..."
            animateWakePulse()
        }

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 60, 60, 90)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        } catch (e: Exception) {}

        SoundEffectManager.playWakeChime()

        // 7-second standby recovery if user issues no follow-up directive
        scope.launch {
            delay(7000)
            if (isWakeAcknowledged && !isProcessingCommand) {
                isWakeAcknowledged = false
                runOnUiThread {
                    setCoreActive(false)
                    binding.tvCoreState.text = "STANDBY // SAY 'HEY JARVIS'"
                }
            }
        }
    }

    private fun animateWakePulse() {
        try {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.35f, 1.05f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.35f, 1.05f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 1.0f, 0.85f)
            ObjectAnimator.ofPropertyValuesHolder(binding.viewCoreGlow, scaleX, scaleY, alpha).apply {
                duration = 450
                start()
            }
        } catch (e: Exception) {}
    }

    private fun evaluateWakeMatch(candidates: List<String>, customSignatures: Set<String>): Pair<Boolean, String> {
        val wakeRegexes = listOf(
            Regex("""\b(hey|hi|hello|ok|okay|yo|listen|wake\s+up)?\s*(jarvis|jervis|jarves|javis|jarviz|java|javus|javes|tarvis|starvis|service|harvest|travis|charvis|charles|chavez|gervais)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(jarvis|jervis|jarves|javis|jarviz|java|javus|javes|tarvis|starvis)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bj\.a\.r\.v\.i\.s\b""", RegexOption.IGNORE_CASE),
            Regex("""\bwake\s+up\b""", RegexOption.IGNORE_CASE)
        )

        for (candidate in candidates) {
            val text = candidate.trim()
            // Check custom trained signatures first
            for (custom in customSignatures) {
                if (custom.isNotBlank() && text.contains(custom, ignoreCase = true)) {
                    val trailing = text.substringAfter(custom, "").trim().removePrefix(",").trim()
                    return Pair(true, trailing)
                }
            }
            // Check built-in regexes
            for (regex in wakeRegexes) {
                val match = regex.find(text)
                if (match != null) {
                    val trailing = text.substring(match.range.last + 1).trim().removePrefix(",").trim()
                    return Pair(true, trailing)
                }
            }
        }
        return Pair(false, "")
    }

    // --- Interactive Voice Training / Calibration ---

    private fun startVoiceCalibration() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true

        binding.tvCoreState.text = "CALIBRATING VOCAL SIGNATURE..."
        binding.tvTranscript.text = "Jarvis: Initiating acoustic calibration, Sir.\nPlease say 'Hey Jarvis' or your preferred callsign clearly now..."
        setCoreActive(true)

        VoiceManager.speak("Initiating acoustic calibration, Sir. Please say 'Hey Jarvis' clearly now.") {
            runOnUiThread {
                binding.tvTranscript.text = "Listening for your voice signature...\nSay 'Hey Jarvis' now."
                try {
                    val calibrator = SpeechRecognizer.createSpeechRecognizer(this)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    }
                    calibrator.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            runOnUiThread {
                                isProcessingCommand = false
                                calibrator.destroy()
                                binding.tvTranscript.text = "Jarvis: Calibration timed out, Sir. Tap 'Train Voice' to try again."
                                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                                if (isHandsFreeEnabled) startAmbientWakeWordListener()
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            runOnUiThread {
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                                calibrator.destroy()
                                if (matches.isNotEmpty()) {
                                    val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                                    val existing = prefs.getStringSet("custom_wake_signatures", emptySet())?.toMutableSet() ?: mutableSetOf()
                                    existing.addAll(matches.map { it.lowercase().trim() })
                                    prefs.edit()
                                        .putStringSet("custom_wake_signatures", existing)
                                        .putBoolean("hands_free_wake_word", true)
                                        .apply()

                                    isHandsFreeEnabled = true
                                    updateHandsFreePill()

                                    KnowledgeGraph.saveNode(
                                        this@MainActivity,
                                        "PREFERENCE",
                                        "VOICE_SIGNATURE",
                                        "User calibrated acoustic phrases: ${matches.joinToString()}"
                                    )

                                    SoundEffectManager.playConfirmChirp()
                                    binding.tvTranscript.text = "Jarvis: Voice profile locked and calibrated, Sir!\nHands-Free Wake is now armed."
                                    binding.tvCoreState.text = "VOICE CALIBRATED // WAKE ARMED"
                                    Toast.makeText(this@MainActivity, "Voice Calibrated & Hands-Free Armed!", Toast.LENGTH_SHORT).show()

                                    VoiceManager.speak("Voice signature locked and synchronized, Sir. Hands-Free is now armed and listening for you.") {
                                        runOnUiThread {
                                            isProcessingCommand = false
                                            setCoreActive(false)
                                            startAmbientWakeWordListener()
                                        }
                                    }
                                } else {
                                    isProcessingCommand = false
                                    startAmbientWakeWordListener()
                                }
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    calibrator.startListening(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Calibration start failed", e)
                    isProcessingCommand = false
                    startAmbientWakeWordListener()
                }
            }
        }
    }

    // --- Direct Native Device Execution Engine ---

    private fun tryHandleDirectCommand(input: String): Boolean {
        var clean = input.lowercase().trim()
        val prefixesToStrip = listOf(
            "hey jarvis", "ok jarvis", "okay jarvis", "hello jarvis", "hi jarvis",
            "jarvis please", "jarvis can you", "jarvis", "please"
        )
        for (prefix in prefixesToStrip) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix).trim().removePrefix(",").trim()
            }
        }
        val lower = clean

        // 1. Flashlight / Torch
        if (lower.contains("flashlight on") || lower.contains("torch on") || 
            lower.contains("turn on flashlight") || lower.contains("turn on torch") || 
            lower.contains("turn on the flashlight") || lower.contains("turn on the torch")) {
            setFlashlight(true)
            speakAndDisplay("Illuminating flashlight, Sir.")
            return true
        }
        if (lower.contains("flashlight off") || lower.contains("torch off") || 
            lower.contains("turn off flashlight") || lower.contains("turn off torch") || 
            lower.contains("turn off the flashlight") || lower.contains("turn off the torch")) {
            setFlashlight(false)
            speakAndDisplay("Deactivating flashlight, Sir.")
            return true
        }

        // 2. Open Applications (Dynamic matching against all installed apps)
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appName = lower.removePrefix("open ").removePrefix("launch ").removePrefix("start ").trim()
            val resolved = resolvePackageForApp(appName)
            if (resolved != null) {
                launchApp(resolved.first, resolved.second)
                return true
            }
            // If not a directly resolved local app, fall through to central AI reasoning
        }

        // 2b. Check / Read Incoming Emails
        if (lower.contains("check my email") || lower.contains("check my mail") || 
            lower.contains("read my email") || lower.contains("read my mail") || 
            lower.contains("any new email") || lower.contains("any new mail") ||
            lower.contains("do i have any email") || lower.contains("do i have emails")) {
            val emails = AssistantNotificationService.getRecentEmails(4)
            if (emails.isNotEmpty()) {
                val count = emails.size
                val summary = emails.joinToString("; ") { "From ${it.title}: ${it.text}" }
                speakAndDisplay("You have $count recent email notification${if (count > 1) "s" else ""}, Sir: $summary.")
            } else {
                speakAndDisplay("No new email notifications logged on your device at this moment, Sir. Opening Gmail for you.")
                launchApp("com.google.android.gm", "Gmail")
            }
            return true
        }

        // 3. Make Phone Calls
        if (lower.startsWith("call ") || lower.startsWith("make a call to ") || lower.startsWith("dial ")) {
            val target = lower.removePrefix("make a call to ").removePrefix("call ").removePrefix("dial ").trim()
            if (target.isNotBlank()) {
                speakAndDisplay("Initiating call to $target, Sir.")
                DeviceManager(this).makeCall(target)
                return true
            }
        }

        // 4. Play YouTube / Music
        if (lower.startsWith("play ")) {
            val song = lower.removePrefix("play ").trim()
            speakAndDisplay("Queuing $song on your audio channels, Sir.")
            GhostPilot.playYouTube(this, song)
            return true
        }

        // 5. Volume adjustment
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("raise volume")) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            speakAndDisplay("Raising audio volume, Sir.")
            return true
        }
        if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("lower volume")) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            speakAndDisplay("Lowering audio volume, Sir.")
            return true
        }

        // 6. WhatsApp Transmission (Any contact or phone number)
        val waPatterns = listOf(
            Regex("""(?:send\s+)?(?:a\s+)?(?:message|msg)\s+(?:on|in)?\s*whatsapp\s+(?:to\s+)?(.+?)\s+(?:saying|that|telling|with message)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""whatsapp\s+(?:to\s+)?(.+?)\s+(?:saying|that|telling)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:send\s+)?(?:a\s+)?whatsapp\s+(?:to\s+)?(.+?)\s+(?:saying|that|telling)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""message\s+(.+?)\s+on\s+whatsapp\s+(?:saying|that)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""whatsapp\s+([a-zA-Z0-9_+]+)\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pat in waPatterns) {
            val match = pat.find(lower)
            if (match != null) {
                val target = match.groupValues[1].trim()
                val msg = match.groupValues[2].trim()
                speakAndDisplay("Transmitting WhatsApp message to $target, Sir.")
                GhostPilot.sendWhatsApp(this, target, msg)
                return true
            }
        }

        // 7. Wireless Smart TV Jump & Casting (DIAL, Samsung Tizen, Roku, DLNA)
        if (lower.startsWith("cast ") || lower.contains("on tv") || lower.contains("to tv") || lower.startsWith("play on tv")) {
            var query = lower.removePrefix("cast ").removePrefix("play on tv").removePrefix("play ")
            query = query.replace("to tv", "").replace("on tv", "").replace("to my tv", "").replace("on my tv", "").trim()
            val targetIp = SmartTvManager.activeTv?.ip ?: ""
            val tvName = SmartTvManager.activeTv?.name ?: "Smart TV"
            speakAndDisplay("Broadcasting '$query' to $tvName, Sir.")
            SmartTvManager.castToTv(targetIp, query) { success, feedback ->
                Log.i(TAG, feedback)
            }
            return true
        }

        if (lower.contains("tv volume up") || lower.contains("increase tv volume") || lower.contains("raise tv volume")) {
            val tvIp = SmartTvManager.activeTv?.ip ?: ""
            SmartTvManager.sendTvKey(tvIp, "VOL_UP") { _, msg -> speakAndDisplay(msg) }
            return true
        }

        if (lower.contains("tv volume down") || lower.contains("decrease tv volume") || lower.contains("lower tv volume")) {
            val tvIp = SmartTvManager.activeTv?.ip ?: ""
            SmartTvManager.sendTvKey(tvIp, "VOL_DOWN") { _, msg -> speakAndDisplay(msg) }
            return true
        }

        if (lower.contains("mute tv")) {
            val tvIp = SmartTvManager.activeTv?.ip ?: ""
            SmartTvManager.sendTvKey(tvIp, "MUTE") { _, msg -> speakAndDisplay(msg) }
            return true
        }

        if (lower.contains("scan wifi") || lower.contains("scan network") || lower.contains("find tv") || lower.contains("find my tv")) {
            triggerSmartTvScan()
            return true
        }

        return false
    }

    private fun speakAndDisplay(text: String) {
        runOnUiThread {
            binding.tvTranscript.text = "Jarvis: $text"
            binding.tvCoreState.text = "SPEAKING..."
            SoundEffectManager.playConfirmChirp()
        }
        VoiceManager.speak(text) {
            runOnUiThread {
                isProcessingCommand = false
                setCoreActive(false)
                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                if (isHandsFreeEnabled) startAmbientWakeWordListener()
            }
        }
    }

    private fun launchApp(pkg: String, displayName: String) {
        speakAndDisplay("Opening $displayName, Sir.")
        scope.launch {
            delay(400)
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app $pkg", e)
            } finally {
                isProcessingCommand = false
            }
        }
    }

    private fun resolvePackageForApp(name: String): Pair<String, String>? {
        val clean = name.lowercase().trim()
        val directMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatapp" to "com.whatsapp",
            "watsapp" to "com.whatsapp",
            "what app" to "com.whatsapp",
            "what's app" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "you tube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "camera" to "com.sec.android.app.camera",
            "gallery" to "com.sec.android.gallery3d",
            "photos" to "com.google.android.apps.photos",
            "calculator" to "com.sec.android.app.popupcalculator",
            "calc" to "com.sec.android.app.popupcalculator",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "clock" to "com.sec.android.app.clockpackage",
            "phone" to "com.samsung.android.dialer",
            "dialer" to "com.samsung.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging"
        )

        val targetPkg = directMap[clean]
        if (targetPkg != null) {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                return Pair(targetPkg, clean.replaceFirstChar { it.uppercase() })
            }
        }

        // Dynamic search across all installed launcher activities
        try {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolveInfos) {
                val label = ri.loadLabel(pm).toString()
                val pkg = ri.activityInfo.packageName
                val lowerLabel = label.lowercase()
                val lowerPkg = pkg.lowercase()
                if (lowerLabel == clean || lowerLabel.contains(clean) || clean.contains(lowerLabel) || lowerPkg.contains(clean)) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        return Pair(pkg, label)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic app launch error", e)
        }
        return null
    }

    private fun setFlashlight(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(id, enable)
                    return
                }
            }
            val firstId = cameraManager.cameraIdList.firstOrNull()
            if (firstId != null) {
                cameraManager.setTorchMode(firstId, enable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    private fun executeDirectOrAccessibilityActions(actions: List<AssistantAction>) {
        for (action in actions) {
            when (action.type) {
                ActionType.OPEN_APP -> {
                    action.target?.let { appNameOrPkg ->
                        val resolved = resolvePackageForApp(appNameOrPkg)
                        if (resolved != null) {
                            launchApp(resolved.first, resolved.second)
                        } else {
                            val intent = packageManager.getLaunchIntentForPackage(appNameOrPkg)
                            if (intent != null) {
                                launchApp(appNameOrPkg, appNameOrPkg)
                            } else {
                                speakAndDisplay("Application '$appNameOrPkg' is not installed, Sir.")
                            }
                        }
                    }
                }
                ActionType.FLASHLIGHT_TOGGLE -> {
                    setFlashlight(action.value != "false")
                }
                ActionType.CALL_PHONE -> {
                    action.target?.let { DeviceManager(this).makeCall(it) }
                }
                ActionType.PLAY_YOUTUBE, ActionType.PLAY_SPOTIFY -> {
                    action.target?.let { GhostPilot.playYouTube(this, it) }
                }
                ActionType.SEND_WHATSAPP -> {
                    action.target?.let { target ->
                        GhostPilot.sendWhatsApp(this, target, action.value ?: "")
                    }
                }
                ActionType.CAST_TO_TV -> {
                    val query = action.target ?: "Trending"
                    val targetIp = action.value ?: SmartTvManager.activeTv?.ip ?: ""
                    SmartTvManager.castToTv(targetIp, query) { _, msg ->
                        speakAndDisplay(msg)
                    }
                }
                ActionType.CONTROL_TV -> {
                    val cmd = action.value ?: "VOL_UP"
                    val targetIp = SmartTvManager.activeTv?.ip ?: ""
                    SmartTvManager.sendTvKey(targetIp, cmd) { _, msg ->
                        speakAndDisplay(msg)
                    }
                }
                ActionType.SCAN_WIFI -> {
                    triggerSmartTvScan()
                }
                ActionType.SET_ALARM -> {
                    val hour = action.target?.toIntOrNull() ?: 7
                    val min = action.value?.toIntOrNull() ?: 0
                    DeviceManager(this).setAlarm(hour, min, action.extra ?: "Alarm")
                }
                else -> {
                    // Actions requiring Accessibility (screen clicking, typing, scrolling)
                    AssistantAccessibilityService.instance?.executeActions(listOf(action))
                }
            }
        }
    }

    private fun triggerSmartTvScan() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true
        binding.tvCoreState.text = "SCANNING WIFI MESH..."
        binding.tvTranscript.text = "Jarvis: Scanning local WiFi mesh for Smart TVs and DLNA displays..."

        val (ssid, ip) = SmartTvManager.getWifiInfo(this)
        SmartTvManager.scanLocalNetwork(this) { tvs ->
            val count = tvs.size
            val rep = if (count > 0) {
                val listStr = tvs.joinToString("\n") { "• ${it.name} [${it.ip}] (${it.protocol})" }
                "Jarvis: Wireless mesh active on SSID '$ssid' ($ip).\nDiscovered $count Smart TV device(s):\n$listStr\n\nYou can say 'Cast [video] to TV' or 'Turn up TV volume'."
            } else {
                "Jarvis: Wireless mesh active on SSID '$ssid' ($ip).\nNo Smart TVs detected via SSDP/DIAL on this subnet. Ensure your TV is powered on and connected to '$ssid'."
            }
            speakAndDisplay(if (count > 0) "Wireless mesh established with $count Smart TV device(s), Sir." else "WiFi scan complete. No active Smart TVs detected on $ssid, Sir.")
            runOnUiThread {
                binding.tvTranscript.text = rep
            }
        }
    }

    private fun executeDiagnosticsProtocol() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true
        binding.tvCoreState.text = "RUNNING DIAGNOSTICS..."

        val dm = DeviceManager(this)
        val battery = dm.getBatteryLevel()
        val time = dm.getFormattedSystemTime()
        val hasAccess = AssistantAccessibilityService.isRunning
        val hasHandsFree = isHandsFreeEnabled
        val (wifiSsid, localIp) = SmartTvManager.getWifiInfo(this)
        val tvStatus = SmartTvManager.activeTv?.let { "${it.name} (${it.ip})" } ?: "NO TV LOCKED"
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val bridgeUrl = prefs.getString("bridge_url", "") ?: ""
        val hasKey = !prefs.getString("gemini_api_key", "").isNullOrBlank() || AssistantBrain.BUNDLED_API_KEY.isNotBlank()

        val reportText = StringBuilder()
            .append("STARK INDUSTRIES TELEMETRY REPORT\n")
            .append("---------------------------------\n")
            .append("• Time: $time\n")
            .append("• Arc Power (Battery): $battery%\n")
            .append("• WiFi Mesh: $wifiSsid (IP: $localIp)\n")
            .append("• Smart TV Telemetry: $tvStatus\n")
            .append("• Neural Super-Brain: ").append(if (hasKey) "ONLINE (Gemini)" else "LOCAL ONLY").append("\n")
            .append("• Tactical Accessibility: ").append(if (hasAccess) "ARMED" else "UNAUTHORIZED").append("\n")
            .append("• Acoustic Wake Array: ").append(if (hasHandsFree) "ONLINE" else "DISARMED").append("\n")
            .append("• Workstation Bridge: ${if (bridgeUrl.isNotBlank()) bridgeUrl else "STANDALONE (NO BRIDGE)"}\n")
            .toString()

        binding.tvTranscript.text = reportText
        SoundEffectManager.playConfirmChirp()

        val speech = "System diagnostics complete, Sir. Arc power is at $battery percent. Neural cognition is online. All primary defense grids operational."

        VoiceManager.speak(speech) {
            runOnUiThread {
                isProcessingCommand = false
                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                if (isHandsFreeEnabled) startAmbientWakeWordListener()
            }
        }
    }

    private fun triggerVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        runOnUiThread {
            stopAmbientWakeWordListener()
            isProcessingCommand = true

            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (e: Exception) {}

            setCoreActive(true)
            binding.tvCoreState.text = "LISTENING..."
            binding.tvTranscript.text = "Listening to you, Sir..."

            VoiceManager.listen(
                context = this,
                onPartialResult = { partial ->
                    runOnUiThread {
                        binding.tvTranscript.text = "You: $partial..."
                        binding.tvCoreState.text = "HEARING..."
                    }
                },
                onResult = { spokenText ->
                    setCoreActive(false)
                    handleUserInput(spokenText)
                },
                onError = { err ->
                    setCoreActive(false)
                    isProcessingCommand = false
                    binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                    binding.tvTranscript.text = "Jarvis: Standing by, Sir. Tap the core or speak to issue a directive."
                    if (isHandsFreeEnabled) {
                        scope.launch {
                            delay(400)
                            startAmbientWakeWordListener()
                        }
                    }
                }
            )
        }
    }

    private fun triggerVisualScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 103)
            return
        }
        cameraLauncher.launch(null)
    }

    private fun handleVisualScan(bitmap: Bitmap) {
        stopAmbientWakeWordListener()
        isProcessingCommand = true

        val prompt = binding.etCommand.text?.toString()?.trim() ?: ""
        binding.etCommand.text?.clear()
        binding.tvTranscript.text = "You: [Visual Scan Dispatched]\n\nJarvis: "
        binding.tvCoreState.text = "ANALYZING SCAN..."
        VoiceManager.resetSpeechTurn()

        scope.launch {
            val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", null)
            val liveTranscript = StringBuilder("You: [Visual Scan]\n\nJarvis: ")

            val (finalSpeech, actions) = brain.analyzeVisualFrameStream(bitmap, prompt, apiKey) { sentenceChunk ->
                runOnUiThread {
                    liveTranscript.append(sentenceChunk).append(" ")
                    binding.tvTranscript.text = liveTranscript.toString()
                    binding.tvCoreState.text = "SPEAKING..."
                }
                VoiceManager.streamSentence(sentenceChunk)
            }

            runOnUiThread {
                binding.tvTranscript.text = "You: [Visual Scan]\n\nJarvis: $finalSpeech"
                binding.tvCoreState.text = "SPEAKING..."
            }

            if (actions.isNotEmpty()) {
                executeDirectOrAccessibilityActions(actions)
            }

            VoiceManager.onTurnComplete {
                scope.launch {
                    delay(700)
                    runOnUiThread {
                        isProcessingCommand = false
                        setCoreActive(false)
                        binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                        if (isHandsFreeEnabled) {
                            startAmbientWakeWordListener()
                        }
                    }
                }
            }
        }
    }

    private fun handleUserInput(input: String) {
        val cleanInput = input.trim()
        if (cleanInput.isBlank()) return

        if (cleanInput.equals("diagnostics", ignoreCase = true) || cleanInput.equals("status report", ignoreCase = true)) {
            executeDiagnosticsProtocol()
            return
        }
        if (cleanInput.contains("calibrate voice", ignoreCase = true) || cleanInput.contains("train voice", ignoreCase = true)) {
            startVoiceCalibration()
            return
        }

        stopAmbientWakeWordListener()
        isProcessingCommand = true

        val currentTurn = System.currentTimeMillis()
        activeTurnId = currentTurn
        scope.launch {
            delay(14000)
            if (activeTurnId == currentTurn && isProcessingCommand) {
                Log.w(TAG, "Turn watchdog triggered - recovering standby state")
                runOnUiThread {
                    isProcessingCommand = false
                    setCoreActive(false)
                    binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                    if (isHandsFreeEnabled) {
                        startAmbientWakeWordListener()
                    }
                }
            }
        }

        binding.tvTranscript.text = "You: $cleanInput\n\nJarvis: "
        binding.tvCoreState.text = "PROCESSING..."
        VoiceManager.resetSpeechTurn()

        // 1. Direct Native Actions (Zero-latency, 100% reliable execution)
        if (tryHandleDirectCommand(cleanInput)) {
            return
        }

        // 2. Real-Time Central Super-Intelligence (Gemini)
        binding.tvCoreState.text = "THINKING..."
        scope.launch {
            val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", null)
            val liveTranscript = StringBuilder("You: $cleanInput\n\nJarvis: ")

            val (finalSpeech, actions) = brain.processCommandStream(cleanInput, apiKey) { sentenceChunk ->
                // Streams sentence audio in real-time
                runOnUiThread {
                    liveTranscript.append(sentenceChunk).append(" ")
                    binding.tvTranscript.text = liveTranscript.toString()
                    binding.tvCoreState.text = "SPEAKING..."
                }
                VoiceManager.streamSentence(sentenceChunk)
            }

            runOnUiThread {
                binding.tvTranscript.text = "You: $cleanInput\n\nJarvis: $finalSpeech"
                binding.tvCoreState.text = "SPEAKING..."
                SoundEffectManager.playConfirmChirp()
            }

            // Execute actions directly on the device
            if (actions.isNotEmpty()) {
                executeDirectOrAccessibilityActions(actions)
            }

            VoiceManager.onTurnComplete {
                scope.launch {
                    delay(600)
                    runOnUiThread {
                        isProcessingCommand = false
                        setCoreActive(false)
                        binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                        if (isHandsFreeEnabled) {
                            startAmbientWakeWordListener()
                        }
                    }
                }
            }
        }
    }

    private fun setCoreActive(active: Boolean) {
        if (active) {
            binding.viewCorePulse.setBackgroundResource(R.drawable.arc_reactor_bubble)
            binding.viewCoreGlow.alpha = 1.0f
        } else {
            binding.viewCoreGlow.alpha = 0.7f
        }
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        dialog.setContentView(view)

        val etKey = view.findViewById<EditText>(R.id.et_dialog_api_key)
        val etBridge = view.findViewById<EditText>(R.id.et_dialog_bridge_url)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_dialog_save)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btn_perm_access)
        val btnOverlay = view.findViewById<MaterialButton>(R.id.btn_perm_overlay)
        val btnWakeWord = view.findViewById<MaterialButton>(R.id.btn_perm_wakeword)
        val btnSentry = view.findViewById<MaterialButton>(R.id.btn_perm_sentry)

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        etKey.setText(prefs.getString("gemini_api_key", ""))
        etBridge.setText(prefs.getString("bridge_url", ""))

        btnSave.setOnClickListener {
            val key = etKey.text.toString().trim()
            val bridgeUrl = etBridge.text.toString().trim()
            prefs.edit()
                .putString("gemini_api_key", key)
                .putString("bridge_url", bridgeUrl)
                .apply()
            dialog.dismiss()
            if (key.isNotBlank()) {
                Toast.makeText(this, "Super-Intelligence Online!", Toast.LENGTH_SHORT).show()
                binding.tvStatusPill.text = "● SUPER-BRAIN ONLINE"
            } else {
                Toast.makeText(this, "Offline Core Active", Toast.LENGTH_SHORT).show()
                binding.tvStatusPill.text = "● SYSTEM ACTIVE"
            }
        }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                try {
                    startService(Intent(this, FloatingAssistantService::class.java))
                    Toast.makeText(this, "Floating Orb Activated!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.w(TAG, "Floating orb start error", e)
                }
            }
        }

        btnWakeWord.setOnClickListener {
            toggleHandsFree()
            dialog.dismiss()
        }

        btnSentry.setOnClickListener {
            val active = SecuritySentinel.isSentryModeActive(this)
            SecuritySentinel.setSentryMode(this, !active)
            Toast.makeText(this, if (!active) "House Protocol Armed!" else "House Protocol Disarmed", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btn_dialog_tts).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {}
            }
        }

        dialog.show()
    }

    private fun checkPermissionsState() {
        val hasAccess = AssistantAccessibilityService.isRunning
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

        if (!hasAccess || !hasOverlay) {
            binding.cardPermissionAlert.visibility = View.VISIBLE
            val missing = if (!hasAccess && !hasOverlay) "Accessibility & Overlay" else if (!hasAccess) "Accessibility" else "Overlay"
            binding.tvPermMessage.text = "Grant $missing permission for full control."
        } else {
            binding.cardPermissionAlert.visibility = View.GONE
            try {
                startService(Intent(this, FloatingAssistantService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Floating orb start error", e)
            }
        }

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val hasKey = !prefs.getString("gemini_api_key", "").isNullOrBlank() || AssistantBrain.BUNDLED_API_KEY.isNotBlank()
        if (hasKey) {
            binding.tvStatusPill.text = "● SUPER-BRAIN ONLINE"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAmbientWakeWordListener()
        coreAnimator?.cancel()
        VoiceManager.shutdown()
    }
}
