package com.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.assistant.core.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var brain: AssistantBrain
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        brain = AssistantBrain(this)
        VoiceManager.init(this)

        setupListeners()
        loadSavedApiKey()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupListeners() {
        // 1. Accessibility
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'Jarvis Assistant' and turn it ON", Toast.LENGTH_LONG).show()
        }

        // 2. Overlay
        binding.btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startService(Intent(this, FloatingAssistantService::class.java))
                log("Floating controller activated.")
            }
        }

        // 3. Notification Access
        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Allow notification access for Jarvis Assistant", Toast.LENGTH_LONG).show()
        }

        // 4. Microphone Permission
        binding.btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            } else {
                Toast.makeText(this, "Microphone permission is already granted.", Toast.LENGTH_SHORT).show()
            }
        }

        // Save API Key
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("gemini_api_key", key)
                .apply()

            if (key.isNotBlank()) {
                log("Gemini API key saved. Super-Brain mode activated.")
            } else {
                log("Offline rule-based mode active.")
            }
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        }

        // Manual Command Execution
        binding.btnExecute.setOnClickListener {
            val cmd = binding.etManualCommand.text?.toString()?.trim()
            if (!cmd.isNullOrBlank()) {
                executeCommand(cmd)
            }
        }

        // Voice Command Test
        binding.btnVoiceTest.setOnClickListener {
            log("Listening for voice input...")
            VoiceManager.listen(
                context = this,
                onResult = { spoken ->
                    binding.etManualCommand.setText(spoken)
                    executeCommand(spoken)
                },
                onError = { err ->
                    log("Voice error: $err")
                }
            )
        }
    }

    private fun executeCommand(command: String) {
        log("Command: \"$command\"")
        scope.launch {
            val key = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                .getString("gemini_api_key", null)
            val actions = brain.processCommand(command, key)

            log("Executing ${actions.size} actions...")
            val accessibility = AssistantAccessibilityService.instance
            if (accessibility != null) {
                accessibility.executeActions(actions) {
                    log("Execution finished successfully.")
                }
            } else {
                log("ERROR: Accessibility service is not enabled. Cannot execute screen actions.")
            }
        }
    }

    private fun loadSavedApiKey() {
        val key = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "")
        binding.etApiKey.setText(key)
    }

    private fun updateStatus() {
        val hasAccessibility = AssistantAccessibilityService.isRunning
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

        if (hasAccessibility && hasOverlay) {
            binding.tvSystemStatus.text = "INTELLIGENCE CORE // ONLINE"
            binding.tvSystemStatus.setTextColor(ContextCompat.getColor(this, R.color.ui_cyan))
        } else {
            binding.tvSystemStatus.text = "SETUP REQUIRED // PENDING PERMISSIONS"
            binding.tvSystemStatus.setTextColor(ContextCompat.getColor(this, R.color.ui_gold))
        }
    }

    private fun log(message: String) {
        val current = binding.tvConsoleLog.text.toString()
        binding.tvConsoleLog.text = "[LOG] $message\n$current"
    }

    override fun onDestroy() {
        super.onDestroy()
        VoiceManager.shutdown()
    }
}

