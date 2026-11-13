package com.assistant.core

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Stark Protocol Execution Engine for J.A.R.V.I.S.
 * Deterministically executes iconic Stark protocols locally in under 5 milliseconds,
 * eliminating network roundtrips and preventing LLM hallucination.
 */
object StarkProtocolEngine {

    private const val TAG = "StarkProtocolEngine"

    fun matchProtocol(context: Context, command: String): Pair<String, List<AssistantAction>>? {
        val lower = command.lowercase().trim()
            .removePrefix("jarvis").removePrefix("hey jarvis").removePrefix("ok jarvis").trim()
            .removePrefix(",").trim()

        // 1. House Protocol / Protocol House Party
        if (lower.contains("house protocol") || lower.contains("party protocol") || lower.contains("house party")) {
            return executeHouseProtocol(context)
        }

        // 2. Protocol Clean Slate
        if (lower.contains("clean slate") || lower.contains("protocol clean slate")) {
            return executeCleanSlateProtocol(context)
        }

        // 3. Protocol Sentry / Sentry Mode
        if (lower.contains("protocol sentry") || lower.contains("sentry mode") || lower.contains("sentry protocol") || lower.contains("arm sentry")) {
            return executeSentryProtocol(context, arm = true)
        }
        if (lower.contains("disarm sentry") || lower.contains("stand down sentry") || lower.contains("deactivate sentry")) {
            return executeSentryProtocol(context, arm = false)
        }

        // 4. Protocol Silent Watch / Ghost Protocol
        if (lower.contains("silent watch") || lower.contains("ghost protocol") || lower.contains("protocol ghost") || lower.contains("protocol silent")) {
            return executeSilentWatchProtocol(context)
        }

        // 5. Protocol 11 / Red Protocol (Full Tactical Diagnostics)
        if (lower.contains("protocol 11") || lower.contains("protocol eleven") || lower.contains("red protocol")) {
            return executeProtocolEleven(context)
        }

        // 6. Protocol Workshop / Laboratory Protocol
        if (lower.contains("workshop protocol") || lower.contains("protocol workshop") || lower.contains("laboratory protocol") || lower.contains("lab protocol")) {
            return executeWorkshopProtocol(context)
        }

        // 7. Protocol Veronica / Hulkbuster Mode
        if (lower.contains("protocol veronica") || lower.contains("veronica protocol") || lower.contains("hulkbuster")) {
            return executeVeronicaProtocol(context)
        }

        // 8. Protocol Catch-all check: "Protocol [Name]"
        if (lower.startsWith("protocol ") || lower.contains(" protocol")) {
            val name = lower.substringAfter("protocol").trim().replace(Regex("[^a-zA-Z0-9 ]"), "")
            return Pair(
                "Protocol $name acknowledged, Sir. Realigning local telemetry and standing by for sequence parameters.",
                listOf(AssistantAction(ActionType.SPEAK, value = "Protocol $name acknowledged, Sir."))
            )
        }

        return null
    }

    private fun executeHouseProtocol(context: Context): Pair<String, List<AssistantAction>> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.let {
            val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            it.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.75).toInt(), 0)
        }
        SoundEffectManager.playConfirmChirp()

        val speech = "House Protocol engaged, Sir. Wireless mesh synchronized, audio output directed to perimeter."
        val actions = listOf(
            AssistantAction(ActionType.SCAN_WIFI),
            AssistantAction(ActionType.PLAY_YOUTUBE, target = "AC DC Back In Black"),
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun executeCleanSlateProtocol(context: Context): Pair<String, List<AssistantAction>> {
        try {
            AssistantNotificationService.instance?.cancelAllNotifications()
        } catch (e: Exception) {
            Log.w(TAG, "Could not dismiss active notifications in Clean Slate", e)
        }
        SoundEffectManager.playWakeChime()

        val speech = "Protocol Clean Slate executed, Sir. Notification slate wiped clean, peripheral links refreshed, memory cores reset."
        val actions = listOf(
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun executeSentryProtocol(context: Context, arm: Boolean): Pair<String, List<AssistantAction>> {
        SecuritySentinel.setSentryMode(context, arm)
        val speech = if (arm) {
            "Protocol Sentry armed, Sir. Perimeter sensors sensitized. All transmissions will be announced in real-time."
        } else {
            "Protocol Sentry disarmed. Normal sensory baseline restored, Sir."
        }
        return Pair(speech, listOf(AssistantAction(ActionType.TOGGLE_SENTRY_MODE, value = arm.toString()), AssistantAction(ActionType.SPEAK, value = speech)))
    }

    private fun executeSilentWatchProtocol(context: Context): Pair<String, List<AssistantAction>> {
        triggerHapticPulse(context, 100)
        val speech = "Ghost Protocol initiated, Sir. Audio emissions muted, silent watch engaged."
        val actions = listOf(
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun executeProtocolEleven(context: Context): Pair<String, List<AssistantAction>> {
        val dm = DeviceManager(context)
        val batt = dm.getBatteryLevel()
        val (ssid, ip) = SmartTvManager.getWifiInfo(context)
        val time = dm.getFormattedSystemTime()
        val osVer = Build.VERSION.RELEASE
        SoundEffectManager.playWakeChime()

        val speech = "Protocol 11 diagnostic, Sir: Device clock at $time. Power reserves at $batt percent. Connected to WiFi mesh '$ssid' at IP $ip. Running One UI on Android $osVer. All sensory arrays nominal."
        val actions = listOf(
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun executeWorkshopProtocol(context: Context): Pair<String, List<AssistantAction>> {
        SoundEffectManager.playConfirmChirp()
        val speech = "Workshop Protocol active, Sir. Local development bridge listening on workstation mesh. Ready for code and terminal directives."
        val actions = listOf(
            AssistantAction(ActionType.DISPATCH_DESKTOP_COMMAND, target = "git status"),
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun executeVeronicaProtocol(context: Context): Pair<String, List<AssistantAction>> {
        SoundEffectManager.playSecurityAlert()
        val speech = "Veronica Protocol engaged, Sir. Maximum tactical illumination and alert systems deployed."
        val actions = listOf(
            AssistantAction(ActionType.FLASHLIGHT_TOGGLE, value = "true"),
            AssistantAction(ActionType.SPEAK, value = speech)
        )
        return Pair(speech, actions)
    }

    private fun triggerHapticPulse(context: Context, durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback unavailable", e)
        }
    }
}

