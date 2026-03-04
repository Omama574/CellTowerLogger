package com.omama.celltowerlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.omama.celltowerlogger.WATCHDOG_ALARM") {
            val serviceIntent = Intent(context, CellLoggerService::class.java)
            serviceIntent.action = CellLoggerService.ACTION_WATCHDOG_RESTART

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}