package com.assistant.core

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

data class NotificationItem(
    val packageName: String,
    val title: String,
    val text: String,
    val isSystemAlert: Boolean = false,
    val isEmail: Boolean = false,
    val isWhatsApp: Boolean = false,
    val isInstagram: Boolean = false,
    val isSnapchat: Boolean = false,
    val isSms: Boolean = false,
    val isMissedCall: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChannelUpdatesReport(
    val whatsappCount: Int,
    val instagramCount: Int,
    val snapchatCount: Int,
    val emailCount: Int,
    val smsCount: Int,
    val missedCalls: List<String>,
    val recentAlerts: List<NotificationItem>
)

data class ReplyableNotification(
    val key: String,
    val packageName: String,
    val sender: String,
    val text: String,
    val action: Notification.Action,
    val remoteInput: android.app.RemoteInput,
    val timestamp: Long = System.currentTimeMillis()
)

class AssistantNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "AssistantNotif"
        var instance: AssistantNotificationService? = null
            private set

        private val recentNotifications = mutableListOf<NotificationItem>()
        var lastReplyable: ReplyableNotification? = null
            private set

        fun getRecent(limit: Int = 6): List<NotificationItem> {
            synchronized(recentNotifications) {
                return recentNotifications.takeLast(limit).reversed()
            }
        }

        fun getRecentEmails(limit: Int = 5): List<NotificationItem> {
            synchronized(recentNotifications) {
                return recentNotifications.filter { it.isEmail }.takeLast(limit).reversed()
            }
        }

        fun checkSystemSoftwareUpdate(): NotificationItem? {
            synchronized(recentNotifications) {
                return recentNotifications.firstOrNull { it.isSystemAlert }
            }
        }

        fun dispatchReply(replyText: String, context: Context): Boolean {
            val target = lastReplyable ?: return false
            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(target.remoteInput.resultKey, replyText)
                android.app.RemoteInput.addResultsToIntent(arrayOf(target.remoteInput), intent, bundle)
                target.action.actionIntent.send(context, 0, intent)
                Log.i(TAG, "Dispatched autonomous RemoteInput reply to ${target.sender}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch RemoteInput reply", e)
                false
            }
        }

        fun getDetailedUnreadBriefing(context: Context): Pair<String, String> {
            val active = try { instance?.activeNotifications } catch (e: Exception) { null }
            val messages = mutableListOf<NotificationItem>()

            if (active != null && active.isNotEmpty()) {
                for (sbn in active) {
                    if (sbn.isOngoing) continue
                    val pkg = sbn.packageName.lowercase()
                    val title = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    if (title.isBlank() && text.isBlank()) continue

                    val isWa = pkg.contains("whatsapp")
                    val isSms = pkg.contains("messaging") || pkg.contains("mms")
                    val isMail = pkg == "com.google.android.gm" || pkg.contains("mail")
                    val isInsta = pkg.contains("instagram")
                    val isSnap = pkg.contains("snapchat")

                    if (isWa || isSms || isMail || isInsta || isSnap) {
                        messages.add(
                            NotificationItem(
                                packageName = pkg,
                                title = title,
                                text = text,
                                isWhatsApp = isWa,
                                isSms = isSms,
                                isEmail = isMail,
                                isInstagram = isInsta,
                                isSnapchat = isSnap
                            )
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                synchronized(recentNotifications) {
                    messages.addAll(recentNotifications.filter { it.isWhatsApp || it.isSms || it.isEmail }.takeLast(5))
                }
            }

            // Direct SMS Content Provider fallback if messages are still empty
            if (messages.isEmpty()) {
                try {
                    val cursor = context.contentResolver.query(
                        android.net.Uri.parse("content://sms/inbox"),
                        arrayOf("address", "body"),
                        "read = 0",
                        null,
                        "date DESC LIMIT 5"
                    )
                    cursor?.use {
                        val addrIdx = it.getColumnIndex("address")
                        val bodyIdx = it.getColumnIndex("body")
                        while (it.moveToNext()) {
                            val addr = if (addrIdx >= 0) it.getString(addrIdx) ?: "SMS" else "SMS"
                            val body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                            messages.add(
                                NotificationItem(
                                    packageName = "com.google.android.apps.messaging",
                                    title = addr,
                                    text = body,
                                    isSms = true
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct SMS inbox query exception", e)
                }
            }

            if (messages.isEmpty()) {
                val isListenerAuthorized = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                if (!isListenerAuthorized) {
                    val spoken = "Notification telemetry is currently unauthorized in Android settings, Sir. Please tap the authorization banner on your display to enable notification access so I can monitor and read your incoming transmissions."
                    val transcript = "STARK TELEMETRY // NOTIFICATION ACCESS UNAUTHORIZED\n-----------------------------------------\nTap the 'Enable' banner on your HUD to grant Notification Access in Android Settings."
                    return Pair(spoken, transcript)
                }

                val spoken = "You have no unread transmissions at this time, Sir. All communication channels are clear."
                val transcript = "STARK TELEMETRY // COMMUNICATIONS BRIEFING\nAll channels clear. No unread messages."
                return Pair(spoken, transcript)
            }

            val spokenParts = mutableListOf<String>()
            val transcriptSb = StringBuilder("STARK TELEMETRY // UNREAD TRANSMISSIONS\n-----------------------------------------\n")

            for ((index, item) in messages.take(4).withIndex()) {
                val channel = when {
                    item.isWhatsApp -> "WhatsApp"
                    item.isSms -> "SMS"
                    item.isEmail -> "Email"
                    item.isInstagram -> "Instagram"
                    item.isSnapchat -> "Snapchat"
                    else -> "Message"
                }
                val sender = if (item.title.isNotBlank()) item.title else "Unknown"
                val snippet = if (item.text.isNotBlank()) item.text.take(80) else "New transmission"
                spokenParts.add("${if (index == 0) "First" else "Next"}, on $channel from $sender: '$snippet'")
                transcriptSb.append("• [$channel] $sender: $snippet\n")
            }

            val spoken = "Sir, you have ${messages.size} unread transmission${if (messages.size > 1) "s" else ""}. " +
                    spokenParts.joinToString(". ") + ". Would you like me to dispatch a reply?"
            return Pair(spoken, transcriptSb.toString())
        }

        fun getChannelUpdatesReport(context: Context): ChannelUpdatesReport {
            var wa = 0
            var insta = 0
            var snap = 0
            var mail = 0
            var sms = 0
            val missed = mutableListOf<String>()

            // 1. Inspect live active notifications in drawer if listener is connected
            val active = try { instance?.activeNotifications } catch (e: Exception) { null }
            if (active != null && active.isNotEmpty()) {
                for (sbn in active) {
                    if (sbn.isOngoing) continue
                    val pkg = sbn.packageName.lowercase()
                    val title = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val combined = "$title $text".lowercase()

                    when {
                        pkg.contains("whatsapp") -> wa++
                        pkg.contains("instagram") -> insta++
                        pkg.contains("snapchat") -> snap++
                        pkg == "com.google.android.gm" || pkg.contains("mail") || pkg.contains("email") || pkg.contains("outlook") -> mail++
                        pkg.contains("messaging") || pkg.contains("mms") -> sms++
                        sbn.notification?.category == Notification.CATEGORY_MISSED_CALL || combined.contains("missed call") -> {
                            val caller = if (title.isNotBlank()) title else "Unknown"
                            if (!missed.contains(caller)) missed.add(caller)
                        }
                    }
                }
            }

            // 2. Also check internal history buffer
            synchronized(recentNotifications) {
                for (item in recentNotifications.takeLast(25)) {
                    val combined = "${item.title} ${item.text}".lowercase()
                    if (item.isMissedCall || combined.contains("missed call")) {
                        val caller = if (item.title.isNotBlank()) item.title else "Unknown"
                        if (!missed.contains(caller)) missed.add(caller)
                    }
                }
                if (wa == 0) wa = recentNotifications.count { it.isWhatsApp }
                if (insta == 0) insta = recentNotifications.count { it.isInstagram }
                if (snap == 0) snap = recentNotifications.count { it.isSnapchat }
                if (mail == 0) mail = recentNotifications.count { it.isEmail }
                if (sms == 0) sms = recentNotifications.count { it.isSms }
            }

            // 3. Fallback to Device CallLog & SMS provider if listener was offline
            if (sms == 0) {
                try {
                    val cursor = context.contentResolver.query(
                        android.net.Uri.parse("content://sms/inbox"),
                        arrayOf("_id"),
                        "read = 0",
                        null,
                        null
                    )
                    cursor?.use { sms = it.count }
                } catch (e: Exception) {}
            }

            if (missed.isEmpty()) {
                val callLogMissed = DeviceManager(context).getRecentMissedCalls()
                missed.addAll(callLogMissed)
            }

            return ChannelUpdatesReport(
                whatsappCount = wa,
                instagramCount = insta,
                snapchatCount = snap,
                emailCount = mail,
                smsCount = sms,
                missedCalls = missed,
                recentAlerts = getRecent(5)
            )
        }

        fun formatSpokenUpdates(report: ChannelUpdatesReport, context: Context? = null): String {
            val parts = mutableListOf<String>()
            if (report.whatsappCount > 0) parts.add("${report.whatsappCount} WhatsApp message${if (report.whatsappCount > 1) "s" else ""}")
            if (report.instagramCount > 0) parts.add("${report.instagramCount} Instagram alert${if (report.instagramCount > 1) "s" else ""}")
            if (report.snapchatCount > 0) parts.add("${report.snapchatCount} Snapchat ping${if (report.snapchatCount > 1) "s" else ""}")
            if (report.emailCount > 0) parts.add("${report.emailCount} unread email${if (report.emailCount > 1) "s" else ""}")
            if (report.smsCount > 0) parts.add("${report.smsCount} new SMS message${if (report.smsCount > 1) "s" else ""}")
            if (report.missedCalls.isNotEmpty()) {
                val callers = report.missedCalls.joinToString(" and ")
                parts.add("${report.missedCalls.size} missed call${if (report.missedCalls.size > 1) "s" else ""} from $callers")
            }

            if (parts.isEmpty()) {
                if (context != null && !NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
                    return "Notification telemetry is currently unauthorized in Android settings, Sir. Please tap the authorization banner on your display to enable notification access so I can monitor and read your incoming transmissions."
                }
                return "All primary communication channels are clear, Sir. No pending notifications from WhatsApp, Instagram, Snapchat, Mail, SMS, or Missed Calls."
            } else {
                return "Communications briefing, Sir: You have " + parts.joinToString(", ") + ". All other channels clear."
            }
        }

        fun formatTranscriptUpdates(report: ChannelUpdatesReport): String {
            return StringBuilder()
                .append("STARK TELEMETRY // COMMUNICATIONS BRIEFING\n")
                .append("-----------------------------------------\n")
                .append("• WhatsApp: ").append(if (report.whatsappCount > 0) "${report.whatsappCount} pending" else "Clear").append("\n")
                .append("• Instagram: ").append(if (report.instagramCount > 0) "${report.instagramCount} alert(s)" else "Clear").append("\n")
                .append("• Snapchat: ").append(if (report.snapchatCount > 0) "${report.snapchatCount} ping(s)" else "Clear").append("\n")
                .append("• Mail / Gmail: ").append(if (report.emailCount > 0) "${report.emailCount} unread" else "Clear").append("\n")
                .append("• SMS / Messaging: ").append(if (report.smsCount > 0) "${report.smsCount} unread" else "Clear").append("\n")
                .append("• Missed Calls: ").append(if (report.missedCalls.isNotEmpty()) report.missedCalls.joinToString(", ") else "None").append("\n")
                .toString()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            VoiceManager.init(this)
        } catch (e: Throwable) {}
        Log.i(TAG, "Jarvis Notification Listener active.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null || sbn.isOngoing) return

            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            if (title.isBlank() && text.isBlank()) return

            val pkg = sbn.packageName.lowercase()
            val combined = "$title $text".lowercase()

            // 1. Detect Incoming Calls (announce aloud in real-time)
            val isIncomingCall = sbn.notification?.category == Notification.CATEGORY_CALL ||
                    (pkg.contains("dialer") && (combined.contains("incoming call") || combined.contains("calling") || combined.contains("incoming")))
            if (isIncomingCall) {
                val caller = if (title.isNotBlank()) title else "an incoming caller"
                VoiceManager.speak("Sir, incoming transmission from $caller.")
                return
            }

            // 2. Detect Missed Calls
            val isMissedCall = sbn.notification?.category == Notification.CATEGORY_MISSED_CALL ||
                    (pkg.contains("dialer") && (combined.contains("missed call") || combined.contains("missed")))

            // 3. Detect WhatsApp, Instagram, Snapchat, Mail, SMS
            val isWhatsApp = pkg.contains("whatsapp")
            val isInstagram = pkg.contains("instagram")
            val isSnapchat = pkg.contains("snapchat")
            val isSms = pkg.contains("messaging") || pkg.contains("mms") || pkg == "com.google.android.apps.messaging" || pkg == "com.samsung.android.messaging"
            val isEmail = pkg == "com.google.android.gm" || pkg.contains("mail") || pkg.contains("outlook") || pkg.contains("email")

            // 4. Detect Samsung One UI / Android System Software Updates
            val isUpdate = pkg.contains("wssyncmldm") || 
                           pkg.contains("soagent") || 
                           pkg.contains("fota") ||
                           (pkg == "android" && (combined.contains("update") || combined.contains("firmware") || combined.contains("restart to update")))

            // 5. Look for RemoteInput actions (Direct Voice Reply support for WhatsApp / SMS)
            var foundReplyable: ReplyableNotification? = null
            val actions = sbn.notification?.actions
            if (actions != null) {
                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        val input = remoteInputs.first()
                        foundReplyable = ReplyableNotification(
                            key = sbn.key ?: "",
                            packageName = sbn.packageName,
                            sender = title,
                            text = text,
                            action = action,
                            remoteInput = input
                        )
                        lastReplyable = foundReplyable
                        break
                    }
                }
            }

            val item = NotificationItem(
                packageName = sbn.packageName,
                title = title,
                text = text,
                isSystemAlert = isUpdate,
                isEmail = isEmail,
                isWhatsApp = isWhatsApp,
                isInstagram = isInstagram,
                isSnapchat = isSnapchat,
                isSms = isSms,
                isMissedCall = isMissedCall
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
                return
            }

            // Proactive spoken announcements for incoming WhatsApp / SMS / Social notifications
            if (isWhatsApp || isSms || isInstagram || isSnapchat) {
                val prefs = getSharedPreferences("AssistantPrefs", Context.MODE_PRIVATE)
                val proactiveEnabled = prefs.getBoolean("proactive_notif_enabled", true)
                if (proactiveEnabled && !VoiceManager.isCurrentlySpeaking()) {
                    val appName = when {
                        isWhatsApp -> "WhatsApp"
                        isSms -> "Messages"
                        isInstagram -> "Instagram"
                        isSnapchat -> "Snapchat"
                        else -> "System"
                    }
                    val spoken = if (foundReplyable != null) {
                        "Sir, new message on $appName from $title: '$text'. Would you like me to respond?"
                    } else {
                        "Sir, new alert on $appName from $title: '$text'."
                    }
                    VoiceManager.speak(spoken)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Safe catch onNotificationPosted", t)
        }
    }
}
