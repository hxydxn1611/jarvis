package com.assistant.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sentient Security & Intrusion Protocol for J.A.R.V.I.S. ("House Protocol")
 * Guards the device, handles security alerts, and logs physical unauthorized access attempts.
 */
object SecuritySentinel {

    private const val TAG = "SecuritySentinel"
    private const val PREFS_NAME = "JarvisSecurityPrefs"
    private const val KEY_SENTRY_ACTIVE = "sentry_mode_active"

    fun isSentryModeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SENTRY_ACTIVE, false)
    }

    fun setSentryMode(context: Context, active: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SENTRY_ACTIVE, active).apply()
        
        if (active) {
            SoundEffectManager.playSecurityAlert()
            VoiceManager.speak("House Protocol activated, Sir. Perimeter sensors and biometric tripwires are armed.")
        } else {
            SoundEffectManager.playConfirmChirp()
            VoiceManager.speak("House Protocol disarmed. Welcome back, Sir.")
        }
    }

    fun triggerIntrusionAlert(context: Context) {
        SoundEffectManager.playSecurityAlert()
        VoiceManager.speak("Security Alert: Unauthorized entity detected. Recording telemetry and notifying your desktop workstation.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceManager = DeviceManager(context)
                val coords = deviceManager.getLocationCoordinates()
                val time = deviceManager.getFormattedSystemTime()
                val batt = deviceManager.getBatteryLevel()

                val prefs = context.getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                val bridgeUrl = prefs.getString("bridge_url", "") ?: return@launch
                if (bridgeUrl.isBlank()) return@launch

                val cmd = "Write-Output '[SECURITY BREACH ALERT]: Unauthorized access attempt at $time. Location: $coords. Battery: $batt%' | Out-File -Append -FilePath .jarvis_security_log.txt"
                deviceManager.dispatchDesktopCommand(bridgeUrl, cmd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch security telemetry", e)
            }
        }
    }
}

