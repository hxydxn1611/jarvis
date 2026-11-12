package com.assistant.core

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class NotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AssistantNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "AssistantNotif"
        var instance: AssistantNotificationService? = null
            private set

        private val recentNotifications = mutableListOf<NotificationItem>()

        fun getRecent(limit: Int = 5): List<NotificationItem> {
            synchronized(recentNotifications) {
                return recentNotifications.takeLast(limit).reversed()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Assistant Notification Listener active.")
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

        val item = NotificationItem(
            packageName = sbn.packageName,
            title = title,
            text = text
        )

        synchronized(recentNotifications) {
            recentNotifications.add(item)
            if (recentNotifications.size > 50) {
                recentNotifications.removeAt(0)
            }
        }

        Log.d(TAG, "Notification received from ${sbn.packageName}: $title -> $text")
    }
}

