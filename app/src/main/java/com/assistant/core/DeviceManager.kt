package com.assistant.core

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceManager"
    }

    // --- Phone Calls with Smart Contact Name Resolution ---
    fun makeCall(contactNameOrNumber: String) {
        try {
            // First: Check if it's already a numeric phone number
            var resolvedNumber = contactNameOrNumber.replace(Regex("[^0-9+]"), "")

            // Second: If not numeric or too short, search phone's Contacts book
            if (resolvedNumber.length < 5) {
                val found = lookupContactNumber(contactNameOrNumber)
                if (found != null) {
                    resolvedNumber = found
                }
            }

            if (resolvedNumber.isNotBlank()) {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                // If contact not found, open dialer with original query
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contactNameOrNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            }
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contactNameOrNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        }
    }

    // Resolves contact name (e.g. "Rahul", "Mom") to actual phone number
    private fun lookupContactNumber(name: String): String? {
        var number: String? = null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex != -1) {
                    number = cursor.getString(numberIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
        } finally {
            cursor?.close()
        }
        return number
    }

    // --- Email Composition ---
    fun sendEmail(recipient: String, subject: String, body: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch email composer", e)
        }
    }

    // --- Alarms & Timers ---
    fun setAlarm(hour: Int, minute: Int, message: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
        }
    }

    // --- Spotify / Music Playback ---
    fun playMusic(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(ytIntent)
        }
    }

    // --- Flashlight / Torch ---
    fun toggleTorch(enable: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle failed", e)
        }
    }

    // --- GPS Location Telemetry ---
    fun getLocationCoordinates(): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (location != null) {
                "Lat: ${"%.4f".format(location.latitude)}, Lon: ${"%.4f".format(location.longitude)}"
            } else {
                "GPS Active"
            }
        } catch (e: Exception) {
            "Location permissions pending"
        }
    }

    // --- Battery Level ---
    fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // --- Precise Clock ---
    fun getFormattedSystemTime(): String {
        val sdf = SimpleDateFormat("h:mm a, EEEE, MMMM d, yyyy (z)", Locale.getDefault())
        return sdf.format(Date())
    }

    // --- Antigravity PC Workshop Bridge ---
    private val networkClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun checkBridgeStatus(bridgeUrl: String): Boolean {
        return try {
            val req = Request.Builder().url(bridgeUrl).get().build()
            val res = networkClient.newCall(req).execute()
            res.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun dispatchDesktopCommand(bridgeUrl: String, command: String): String {
        return try {
            val payload = JsonObject().apply {
                addProperty("command", command)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(bridgeUrl).post(body).build()
            val res = networkClient.newCall(req).execute()
            if (res.isSuccessful) {
                val responseJson = res.body?.string() ?: ""
                val obj = Gson().fromJson(responseJson, JsonObject::class.java)
                val stdout = obj.get("stdout")?.asString ?: ""
                val stderr = obj.get("stderr")?.asString ?: ""
                val summary = obj.get("summary")?.asString ?: "Command executed on PC workstation."
                if (stdout.isNotBlank()) "$summary\nOutput: $stdout"
                else if (stderr.isNotBlank()) "$summary\nNotice: $stderr"
                else summary
            } else {
                "Workstation returned HTTP error ${res.code}."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reach desktop bridge at $bridgeUrl", e)
            "Unable to reach the desktop workshop at $bridgeUrl. Please verify the PC bridge is online."
        }
    }
}
