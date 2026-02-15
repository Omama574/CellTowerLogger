package com.omama.celltowerlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("survival_prefs", Context.MODE_PRIVATE)
            val shouldRun = prefs.getBoolean("KEY_SERVICE_REQUESTED", false)
            if (shouldRun) {
                val serviceIntent = Intent(context, CellLoggerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
