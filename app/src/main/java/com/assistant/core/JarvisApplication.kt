package com.assistant.core

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisApplication : Application() {

    companion object {
        private const val TAG = "JarvisApp"
        lateinit var instance: JarvisApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupGlobalCrashHandler()

        try {
            VoiceManager.init(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed initial VoiceManager initialization", e)
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val stackTrace = Log.getStackTraceString(throwable)
            val crashReport = "CRASH OCCURRED AT $timestamp in thread '${thread.name}':\n$stackTrace\n\n"

            Log.e("JarvisCrash", crashReport)

            try {
                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(crashReport)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing crash log", e)
            }

            // If the crash is on a non-main worker thread or service background thread, catch and avoid terminating the app
            if (thread.name.contains("DefaultDispatcher") || thread.name.contains("OkHttp") || thread.name.contains("Binder")) {
                Log.w(TAG, "Suppressed background worker thread crash to keep main process alive: ${thread.name}")
                return@setDefaultUncaughtExceptionHandler
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
