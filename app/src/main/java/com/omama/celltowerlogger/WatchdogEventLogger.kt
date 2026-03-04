package com.omama.celltowerlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogEventLogger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = intent.getStringExtra("event") ?: "UNKNOWN"
        CsvLogger.logEvent(context, event, null)
    }
}