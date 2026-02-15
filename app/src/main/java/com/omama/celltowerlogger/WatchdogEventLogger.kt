package com.omama.celltowerlogger

import android.app.Service
import android.content.Intent
import android.os.IBinder

class WatchdogEventLogger : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "unknown"
        CsvLogger.logEvent(this, action, null)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
