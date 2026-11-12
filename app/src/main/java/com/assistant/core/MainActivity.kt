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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var brain: AssistantBrain
    private val scope = CoroutineScope(Dispatchers.Main)
    private var coreAnimator: ObjectAnimator? = null

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

        brain = AssistantBrain(this)
        VoiceManager.init(this)

        startCoreAnimation()
        setupInteractions()
        checkPermissionsState()
        requestRuntimePermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 102)
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

    private fun triggerVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

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
                binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
                binding.tvTranscript.text = "Jarvis: Standing by, Sir."
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
                binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
            }

            if (actions.isNotEmpty()) {
                AssistantAccessibilityService.instance?.executeActions(actions)
            }
        }
    }

    private fun handleUserInput(input: String) {
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
                binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
                SoundEffectManager.playConfirmChirp()
            }

            // Execute device actions
            if (actions.isNotEmpty()) {
                AssistantAccessibilityService.instance?.executeActions(actions)
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
            try {
                if (WakeWordService.isListening) {
                    stopService(Intent(this, WakeWordService::class.java))
                    Toast.makeText(this, "Wake Array Disarmed", Toast.LENGTH_SHORT).show()
                } else {
                    startService(Intent(this, WakeWordService::class.java))
                    Toast.makeText(this, "Hands-Free Wake Array Active ('Hey Jarvis')", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Wake Array initialization restricted by OS", Toast.LENGTH_SHORT).show()
            }
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
        coreAnimator?.cancel()
        VoiceManager.shutdown()
    }
}
