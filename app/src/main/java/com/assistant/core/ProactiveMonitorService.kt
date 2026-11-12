package com.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Autonomous Proactive Intelligence Daemon for J.A.R.V.I.S.
 * Proactively observes battery metrics, thermal states, Samsung software updates,
 * and PC workstation background tasks, speaking up unprompted when critical events occur.
 */
class ProactiveMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "jarvis_proactive_channel"
        private const val NOTIFICATION_ID = 2001
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var monitorJob: Job? = null
    private var lastBatteryLevel = -1
    private var hasAlertedLowBattery = false
    private var hasAlertedFullCharge = false
    private var lastSoftwareUpdateText: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            checkBatteryAlerts(level, isCharging)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startPeriodicAuditor()
    }

    private fun startPeriodicAuditor() {
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(30_000) // Audit every 30 seconds

                // 1. Check for Samsung One UI Software Updates
                val updateNotif = AssistantNotificationService.checkSystemSoftwareUpdate()
                if (updateNotif != null && updateNotif.text != lastSoftwareUpdateText) {
                    lastSoftwareUpdateText = updateNotif.text
                    SoundEffectManager.playAlertChime()
                    delay(400)
                    VoiceManager.speak("Pardon the interruption, Sir. A Samsung One UI system software update has arrived: ${updateNotif.text}")
                }

                // 2. Audit Asynchronous Antigravity Desktop Jobs
                auditDesktopWorkstationJobs()
            }
        }
    }

    private fun checkBatteryAlerts(level: Int, isCharging: Boolean) {
        if (level <= 15 && !isCharging && !hasAlertedLowBattery) {
            hasAlertedLowBattery = true
            SoundEffectManager.playAlertChime()
            VoiceManager.speak("Pardon the interruption, Sir. Power reserves have depleted to $level percent. Recommend connecting to power immediately.")
        } else if (level > 25) {
            hasAlertedLowBattery = false
        }

        if (level == 100 && isCharging && !hasAlertedFullCharge) {
            hasAlertedFullCharge = true
            SoundEffectManager.playConfirmChirp()
            VoiceManager.speak("Sir, power cells are fully saturated at 100 percent. Ready for field operations.")
        } else if (level < 95) {
            hasAlertedFullCharge = false
        }
    }

    private suspend fun auditDesktopWorkstationJobs() {
        val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
        val bridgeUrl = prefs.getString("bridge_url", "http://192.168.1.4:8888") ?: return
        
        try {
            val deviceManager = DeviceManager(this)
            val jobResult = deviceManager.dispatchDesktopCommand(bridgeUrl, "Get-Content -Path .jarvis_last_job.txt -ErrorAction SilentlyContinue")
            if (jobResult.isNotBlank() && jobResult.contains("[JOB_COMPLETED:")) {
                val jobTitle = jobResult.substringAfter("[JOB_COMPLETED:").substringBefore("]")
                // Clear the flag so it only alerts once
                deviceManager.dispatchDesktopCommand(bridgeUrl, "Remove-Item .jarvis_last_job.txt -ErrorAction SilentlyContinue")
                SoundEffectManager.playConfirmChirp()
                VoiceManager.speak("Sir, your workstation has completed the background task: $jobTitle.")
            }
        } catch (e: Exception) {
            // Server offline or sleeping
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Autonomous Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors device health, software updates, and workstation tasks"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Core Online")
            .setContentText("Autonomous telemetry and proactive monitoring active.")
            .setSmallIcon(R.drawable.arc_reactor_bubble)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        monitorJob?.cancel()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
    }
}
