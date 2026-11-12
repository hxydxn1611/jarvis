package com.assistant.core

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
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
        isHandsFreeEnabled = prefs.getBoolean("hands_free_wake_word", false)
        if (prefs.getString("gemini_api_key", "").isNullOrBlank()) {
            prefs.edit().putString("gemini_api_key", AssistantBrain.BUNDLED_API_KEY).apply()
        }

        brain = AssistantBrain(this)
        VoiceManager.init(this)

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

        // Stop background wake service when app is in foreground to prevent mic collisions
        try {
            stopService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {}

        if (isHandsFreeEnabled && !isProcessingCommand) {
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
        val missing = requiredPermissions.filter {
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
            triggerVoiceListening()
        }

        // Tap on Mic FAB
        binding.fabVoice.setOnClickListener {
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

        // Stark Protocol Chips
        binding.chipCalibrateVoice.setOnClickListener {
            startVoiceCalibration()
        }
        binding.chipDiagnostics.setOnClickListener {
            executeDiagnosticsProtocol()
        }
        binding.chipCleanSlate.setOnClickListener {
            executeCleanSlateProtocol()
        }
        binding.chipHouseParty.setOnClickListener {
            executeHousePartyProtocol()
        }
        binding.chipStealth.setOnClickListener {
            executeStealthProtocol()
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
            binding.tvHandsFreePill.text = "🎤 WAKE: ON"
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
                                    delay(2500)
                                    if (isHandsFreeEnabled && !isProcessingCommand && !VoiceManager.isCurrentlySpeaking()) {
                                        startAmbientWakeWordListener()
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
                                    delay(500)
                                    restartAmbientListener()
                                }
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
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
        runOnUiThread {
            try {
                ambientRecognizer?.cancel()
                launchAmbientListeningIntent()
            } catch (e: Exception) {
                startAmbientWakeWordListener()
            }
        }
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
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val customSignatures = prefs.getStringSet("custom_wake_signatures", emptySet()) ?: emptySet()

        val (matched, command) = evaluateWakeMatch(matches, customSignatures)
        if (matched) {
            stopAmbientWakeWordListener()

            if (command.isNotBlank()) {
                handleUserInput(command)
            } else {
                setCoreActive(true)
                binding.tvCoreState.text = "LISTENING SIR..."
                binding.tvTranscript.text = "You: Hey Jarvis\n\nJarvis: At your service, Sir."

                try {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } catch (e: Exception) {}

                VoiceManager.speak("At your service, Sir.") {
                    scope.launch {
                        delay(400)
                        runOnUiThread {
                            triggerVoiceListening()
                        }
                    }
                }
            }
        } else {
            scope.launch {
                delay(600)
                restartAmbientListener()
            }
        }
    }

    private fun evaluateWakeMatch(candidates: List<String>, customSignatures: Set<String>): Pair<Boolean, String> {
        val wakeRegexes = listOf(
            Regex("""\b(hey|hi|hello|ok|okay|yo)?\s*(jarvis|jervis|jarves|javis|jarviz)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bjarvis\b""", RegexOption.IGNORE_CASE),
            Regex("""\bj\.a\.r\.v\.i\.s\b""", RegexOption.IGNORE_CASE)
        )

        for (candidate in candidates) {
            val text = candidate.trim()
            // Check custom trained signatures first
            for (custom in customSignatures) {
                if (text.contains(custom, ignoreCase = true)) {
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

    // --- Stark Tactical Protocols ---

    private fun executeCleanSlateProtocol() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true
        binding.tvTranscript.text = "CLEAN SLATE PROTOCOL INITIATED...\nPurging neural cache & workspace..."
        binding.tvCoreState.text = "CLEAN SLATE ACTIVE"

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), -1))
            }
        } catch (e: Exception) {}

        binding.etCommand.text?.clear()
        binding.dialogueContainer.fullScroll(View.FOCUS_UP)
        SoundEffectManager.playConfirmChirp()

        VoiceManager.speak("Clean Slate Protocol executed, Sir. Neural workspace purged and systems recalibrated to factory baseline.") {
            runOnUiThread {
                isProcessingCommand = false
                binding.tvTranscript.text = "Jarvis: Clean Slate complete, Sir. Systems standing by."
                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                if (isHandsFreeEnabled) startAmbientWakeWordListener()
            }
        }
    }

    private fun executeHousePartyProtocol() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true
        binding.tvCoreState.text = "HOUSE PARTY PROTOCOL"
        binding.tvTranscript.text = "🎉 HOUSE PARTY PROTOCOL ENGAGED!\nMax volume // Workshop alert // Playing Stark Anthem..."

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )

        // Strobe flashlight 3 times
        GhostPilot.strobeFlashlight(this)

        // Dispatch desktop bridge alert safely in background if configured
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val bridgeUrl = prefs.getString("bridge_url", "") ?: ""
        if (bridgeUrl.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                try {
                    DeviceManager(this@MainActivity).dispatchDesktopCommand(bridgeUrl, "[ASYNC] echo 'HOUSE PARTY PROTOCOL ENGAGED'")
                } catch (e: Exception) {
                    Log.e(TAG, "Bridge dispatch error", e)
                }
            }
        }

        // Play AC/DC on YouTube hands-free
        GhostPilot.playYouTube(this, "AC/DC Back in Black Iron Man")

        VoiceManager.speak("House Party Protocol engaged, Sir. Pumping Stark audio to maximum and lighting up the workshop.") {
            runOnUiThread {
                isProcessingCommand = false
                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                if (isHandsFreeEnabled) startAmbientWakeWordListener()
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
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val bridgeUrl = prefs.getString("bridge_url", "") ?: ""
        val hasKey = !prefs.getString("gemini_api_key", "").isNullOrBlank()

        val reportText = StringBuilder()
            .append("STARK INDUSTRIES TELEMETRY REPORT\n")
            .append("---------------------------------\n")
            .append("• Time: $time\n")
            .append("• Arc Power (Battery): $battery%\n")
            .append("• Neural Super-Brain: ").append(if (hasKey) "ONLINE (Gemini)" else "LOCAL ONLY").append("\n")
            .append("• Ghost Pilot (Accessibility): ").append(if (hasAccess) "ARMED" else "UNAUTHORIZED").append("\n")
            .append("• Acoustic Wake Array: ").append(if (hasHandsFree) "ONLINE" else "DISARMED").append("\n")
            .append("• Workstation Bridge: ${if (bridgeUrl.isNotBlank()) bridgeUrl else "STANDALONE (NO BRIDGE)"}\n")
            .toString()

        binding.tvTranscript.text = reportText
        SoundEffectManager.playConfirmChirp()

        val speech = "Running comprehensive diagnostics, Sir. Arc power cell is at $battery percent. Tactical accessibility is ${if (hasAccess) "armed" else "offline"}. All primary defense grids are functional."

        VoiceManager.speak(speech) {
            runOnUiThread {
                isProcessingCommand = false
                binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                if (isHandsFreeEnabled) startAmbientWakeWordListener()
            }
        }
    }

    private fun executeStealthProtocol() {
        stopAmbientWakeWordListener()
        isProcessingCommand = true
        binding.tvCoreState.text = "STEALTH PROTOCOL"
        binding.tvTranscript.text = "🛡️ STEALTH PROTOCOL ENGAGED\nSilent audio // Sentry armed // Low-profile HUD"

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        SecuritySentinel.setSentryMode(this, true)

        VoiceManager.speak("Stealth protocol armed, Sir. Silent running.") {
            runOnUiThread {
                isProcessingCommand = false
                binding.tvCoreState.text = "STEALTH ACTIVE // SENTRY ARMED"
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
                onResult = { spokenText ->
                    setCoreActive(false)
                    handleUserInput(spokenText)
                },
                onError = { _ ->
                    setCoreActive(false)
                    isProcessingCommand = false
                    binding.tvCoreState.text = if (isHandsFreeEnabled) "STANDBY // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                    binding.tvTranscript.text = "Jarvis: Standing by, Sir."
                    if (isHandsFreeEnabled) {
                        scope.launch {
                            delay(600)
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
                AssistantAccessibilityService.instance?.executeActions(actions)
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
        // Intercept Stark Tactical Protocols
        val lower = input.lowercase().trim()
        if (lower.contains("clean slate")) {
            executeCleanSlateProtocol()
            return
        }
        if (lower.contains("house party")) {
            executeHousePartyProtocol()
            return
        }
        if (lower.contains("diagnostics") || lower.contains("status report") || lower.contains("system status")) {
            executeDiagnosticsProtocol()
            return
        }
        if (lower.contains("stealth protocol") || lower.contains("stealth mode")) {
            executeStealthProtocol()
            return
        }
        if (lower.contains("calibrate voice") || lower.contains("train voice") || lower.contains("train my voice")) {
            startVoiceCalibration()
            return
        }

        stopAmbientWakeWordListener()
        isProcessingCommand = true

        binding.tvTranscript.text = "You: $input\n\nJarvis: "
        binding.tvCoreState.text = "THINKING..."
        VoiceManager.resetSpeechTurn()

        scope.launch {
            val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", null)
            val liveTranscript = StringBuilder("You: $input\n\nJarvis: ")

            val (finalSpeech, actions) = brain.processCommandStream(input, apiKey) { sentenceChunk ->
                // Streams sentence audio in real-time under 400ms
                runOnUiThread {
                    liveTranscript.append(sentenceChunk).append(" ")
                    binding.tvTranscript.text = liveTranscript.toString()
                    binding.tvCoreState.text = "SPEAKING..."
                }
                VoiceManager.streamSentence(sentenceChunk)
            }

            runOnUiThread {
                binding.tvTranscript.text = "You: $input\n\nJarvis: $finalSpeech"
                binding.tvCoreState.text = "SPEAKING..."
                SoundEffectManager.playConfirmChirp()
            }

            // Execute device actions
            if (actions.isNotEmpty()) {
                AssistantAccessibilityService.instance?.executeActions(actions)
            }

            VoiceManager.onTurnComplete {
                scope.launch {
                    delay(700) // Acoustic echo decay buffer
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
