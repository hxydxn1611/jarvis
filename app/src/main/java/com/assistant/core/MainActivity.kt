package com.assistant.core

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var brain: AssistantBrain
    private val scope = CoroutineScope(Dispatchers.Main)
    private var coreAnimator: ObjectAnimator? = null

    // Ambient Hands-Free Wake Engine
    private var isHandsFreeEnabled = true
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
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JarvisCrash", "Uncaught exception in ${thread.name}", throwable)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        isHandsFreeEnabled = prefs.getBoolean("hands_free_wake_word", true)

        brain = AssistantBrain(this)
        VoiceManager.init(this)

        startCoreAnimation()
        setupInteractions()
        updateHandsFreePill()
        checkPermissionsState()
        requestRuntimePermissionsIfNeeded()

        if (intent?.getBooleanExtra("AUTO_LISTEN", false) == true) {
            triggerVoiceListening()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("AUTO_LISTEN", false) == true) {
            triggerVoiceListening()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()

        // Stop background wake service when app is active in foreground to prevent mic collisions
        try {
            stopService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {}

        if (isHandsFreeEnabled && !isProcessingCommand) {
            startAmbientWakeWordListener()
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
                Log.w("MainActivity", "Failed to start background WakeWordService", e)
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
            Toast.makeText(this, "Hands-Free Active: Just say 'Hey Jarvis'", Toast.LENGTH_SHORT).show()
            startAmbientWakeWordListener()
        } else {
            Toast.makeText(this, "Hands-Free Deactivated: Tap Core to speak", Toast.LENGTH_SHORT).show()
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

    // --- Foreground Hands-Free Ambient Listening ---

    private fun startAmbientWakeWordListener() {
        if (!isHandsFreeEnabled || isProcessingCommand || VoiceManager.isCurrentlySpeaking()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        runOnUiThread {
            if (!isProcessingCommand) {
                binding.tvCoreState.text = "HANDS-FREE ACTIVE // SAY 'HEY JARVIS'"
                setCoreActive(false)
            }
        }

        try {
            ambientRecognizer?.destroy()
            ambientRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isAmbientListening = true
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        isAmbientListening = false
                        if (isHandsFreeEnabled && !isProcessingCommand && !isFinishing && !isDestroyed) {
                            scope.launch {
                                delay(400)
                                if (isHandsFreeEnabled && !isProcessingCommand && !VoiceManager.isCurrentlySpeaking()) {
                                    restartAmbientListener()
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isAmbientListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.lowercase() ?: ""
                        if (spoken.isNotBlank()) {
                            handleAmbientSpeech(spoken)
                        } else if (isHandsFreeEnabled && !isProcessingCommand) {
                            restartAmbientListener()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = partialMatches?.firstOrNull()?.lowercase() ?: ""
                        if (partial.contains("jarvis") || partial.contains("hey jarvis")) {
                            ambientRecognizer?.stopListening()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            restartAmbientListener()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ambient recognizer init error", e)
        }
    }

    private fun restartAmbientListener() {
        if (!isHandsFreeEnabled || isProcessingCommand || VoiceManager.isCurrentlySpeaking()) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            ambientRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start ambient recognizer", e)
        }
    }

    private fun stopAmbientWakeWordListener() {
        isAmbientListening = false
        try {
            ambientRecognizer?.stopListening()
            ambientRecognizer?.destroy()
            ambientRecognizer = null
        } catch (e: Exception) {}
    }

    private fun handleAmbientSpeech(spoken: String) {
        if (spoken.contains("jarvis")) {
            val trailingCommand = spoken.substringAfter("jarvis").trim().removePrefix(",").trim()
            stopAmbientWakeWordListener()

            if (trailingCommand.isNotBlank()) {
                // User said complete command directly: "Hey Jarvis what is the chemical equation of water"
                handleUserInput(trailingCommand)
            } else {
                // User just said "Hey Jarvis" or "Jarvis"
                setCoreActive(true)
                binding.tvCoreState.text = "LISTENING SIR..."
                binding.tvTranscript.text = "You: Hey Jarvis\n\nJarvis: At your service, Sir."

                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }

                VoiceManager.speak("At your service, Sir.") {
                    runOnUiThread {
                        triggerVoiceListening()
                    }
                }
            }
        } else {
            // Did not match "jarvis"
            restartAmbientListener()
        }
    }

    private fun triggerVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        stopAmbientWakeWordListener()
        isProcessingCommand = true

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        }

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
                binding.tvCoreState.text = if (isHandsFreeEnabled) "HANDS-FREE ACTIVE // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                binding.tvTranscript.text = "Jarvis: Standing by, Sir."
                if (isHandsFreeEnabled) {
                    startAmbientWakeWordListener()
                }
            }
        )
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
                runOnUiThread {
                    isProcessingCommand = false
                    setCoreActive(false)
                    binding.tvCoreState.text = if (isHandsFreeEnabled) "HANDS-FREE ACTIVE // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                    if (isHandsFreeEnabled) {
                        startAmbientWakeWordListener()
                    }
                }
            }
        }
    }

    private fun handleUserInput(input: String) {
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
                // Plays sentence audio in real-time under 400ms
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
                runOnUiThread {
                    isProcessingCommand = false
                    setCoreActive(false)
                    binding.tvCoreState.text = if (isHandsFreeEnabled) "HANDS-FREE ACTIVE // SAY 'HEY JARVIS'" else "STANDBY // TAP TO SPEAK"
                    if (isHandsFreeEnabled) {
                        startAmbientWakeWordListener()
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
        etBridge.setText(prefs.getString("bridge_url", "http://192.168.1.4:8888"))

        btnSave.setOnClickListener {
            val key = etKey.text.toString().trim()
            val bridgeUrl = etBridge.text.toString().trim().ifBlank { "http://192.168.1.4:8888" }
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
                startService(Intent(this, FloatingAssistantService::class.java))
                Toast.makeText(this, "Floating Orb Activated!", Toast.LENGTH_SHORT).show()
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
            // Auto start floating orb safely if authorized
            try {
                startService(Intent(this, FloatingAssistantService::class.java))
            } catch (e: Exception) {
                Log.e("MainActivity", "Floating orb start error", e)
            }
        }

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        if (!prefs.getString("gemini_api_key", "").isNullOrBlank()) {
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
