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
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
            val now = System.currentTimeMillis()
            if (now - lastAnnounceTime < 4000 && lastAnnouncedNumber == incomingNumber) {
                return // Prevent repeated chime while ringing
            }
            lastAnnounceTime = now
            lastAnnouncedNumber = incomingNumber

            val dm = DeviceManager(context)
            val callerName = if (incomingNumber.isNotBlank()) {
                dm.lookupContactNameByNumber(incomingNumber) ?: incomingNumber
            } else {
                "an incoming caller"
            }

            Log.i(TAG, "Incoming transmission from: $callerName")
            SoundEffectManager.playWakeChime()
            VoiceManager.speak("Sir, incoming transmission from $callerName.")
        }
    }
}