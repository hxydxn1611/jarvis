package com.assistant.core

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class NotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val isSystemAlert: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class AssistantNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "AssistantNotif"
        var instance: AssistantNotificationService? = null
            private set

        private val recentNotifications = mutableListOf<NotificationItem>()

        fun getRecent(limit: Int = 6): List<NotificationItem> {
            synchronized(recentNotifications) {
                return recentNotifications.takeLast(limit).reversed()
            }
        }

        fun checkSystemSoftwareUpdate(): NotificationItem? {
            synchronized(recentNotifications) {
                return recentNotifications.firstOrNull { it.isSystemAlert }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Jarvis Notification Listener active.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val pkg = sbn.packageName.lowercase()
        val combined = "$title $text".lowercase()

        // Detect Samsung One UI / Android System Software Updates
        val isUpdate = pkg.contains("wssyncmldm") || 
                       pkg.contains("soagent") || 
                       pkg.contains("fota") ||
                       (pkg == "android" && (combined.contains("update") || combined.contains("firmware") || combined.contains("restart to update")))

        val item = NotificationItem(
            packageName = sbn.packageName,
            title = title,
            text = text,
            isSystemAlert = isUpdate
        )

        synchronized(recentNotifications) {
            recentNotifications.add(item)
            if (recentNotifications.size > 50) {
                recentNotifications.removeAt(0)
            }
        }

        // Proactive Jarvis announcement for Samsung system updates
        if (isUpdate) {
            VoiceManager.speak("Sir, Samsung has dispatched a new software update for your device.")
        }
    }
}
