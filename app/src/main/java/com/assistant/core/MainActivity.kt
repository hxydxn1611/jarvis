package com.assistant.core

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        brain = AssistantBrain(this)
        VoiceManager.init(this)

        startCoreAnimation()
        setupInteractions()
        checkPermissionsState()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
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

        setCoreActive(true)
        binding.tvCoreState.text = "LISTENING..."
        binding.tvTranscript.text = "Listening to you, Sir..."

        VoiceManager.listen(
            context = this,
            onResult = { spokenText ->
                setCoreActive(false)
                handleUserInput(spokenText)
            },
            onError = { err ->
                setCoreActive(false)
                binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"
                binding.tvTranscript.text = "Jarvis: I couldn't quite hear you, Sir."
            }
        )
    }

    private fun handleUserInput(input: String) {
        binding.tvTranscript.text = "You: $input\n\nJarvis: Processing..."
        binding.tvCoreState.text = "THINKING..."

        scope.launch {
            val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", null)

            val (speech, actions) = brain.processCommand(input, apiKey)

            binding.tvTranscript.text = "You: $input\n\nJarvis: $speech"
            binding.tvCoreState.text = "STANDBY // TAP TO SPEAK"

            // Speak response aloud
            VoiceManager.speak(speech)

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
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_dialog_save)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btn_perm_access)
        val btnOverlay = view.findViewById<MaterialButton>(R.id.btn_perm_overlay)

        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        etKey.setText(prefs.getString("gemini_api_key", ""))

        btnSave.setOnClickListener {
            val key = etKey.text.toString().trim()
            prefs.edit().putString("gemini_api_key", key).apply()
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
            // Auto start floating orb if authorized
            startService(Intent(this, FloatingAssistantService::class.java))
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
