package com.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallAnnouncementReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallAnnouncement"
        private var lastAnnouncedNumber: String? = null
        private var lastAnnounceTime: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

            val state = try {
                intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            } catch (e: Throwable) {
                null
            } ?: return

            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val incomingNumber = try {
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                } catch (e: Throwable) {
                    ""
                }
                val now = System.currentTimeMillis()
                if (now - lastAnnounceTime < 4000 && lastAnnouncedNumber == incomingNumber) {
                    return // Prevent repeated chime while ringing
                }
                lastAnnounceTime = now
                lastAnnouncedNumber = incomingNumber

                VoiceManager.init(context)
                val dm = DeviceManager(context)
                val callerName = if (incomingNumber.isNotBlank()) {
                    try {
                        dm.lookupContactNameByNumber(incomingNumber) ?: incomingNumber
                    } catch (e: Throwable) {
                        incomingNumber
                    }
                } else {
                    "an incoming caller"
                }

                Log.i(TAG, "Incoming transmission from: $callerName")
                try {
                    SoundEffectManager.playWakeChime()
                } catch (e: Throwable) {}
                try {
                    VoiceManager.speak("Sir, incoming transmission from $callerName.")
                } catch (e: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Safe catch on telephony broadcast receive", t)
        }
    }
}
