package com.omama.celltowerlogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogReceiver"
        private const val RETRY_DELAY_MS = 60_000L // 1 minute single retry
        private const val WATCHDOG_RETRY_REQUEST_CODE = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        // log that the OS delivered the alarm
        val svcIntent = Intent(context, CellLoggerService::class.java).apply {
            action = CellLoggerService.ACTION_WATCHDOG_RESTART
        }

        // Inform CSV that watchdog alarm fired (so we know OS delivered it)
        try {
            // Best-effort: write a small broadcast to service via startService if possible
            // But we also log via the file system from here by starting the service with a special action
            context.startService(Intent(context, WatchdogEventLogger::class.java).apply {
                action = "com.omama.celltowerlogger.WATCHDOG_ALARM_FIRED"
            })
        } catch (t: Throwable) {
            // ignore — this is just an extra log path
            t.printStackTrace()
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (ex: Throwable) {
            // Could be IllegalStateException on aggressive OEMs. Schedule a single retry in 60s.
            ex.printStackTrace()
            scheduleSingleRetry(context)
            // Optional: also record a small log so we can see the failure path in CSV (the service will pick this up later)
            try {
                context.startService(Intent(context, WatchdogEventLogger::class.java).apply {
                    action = "com.omama.celltowerlogger.WATCHDOG_START_FAILED"
                    putExtra("error", ex.javaClass.simpleName)
                })
            } catch (t: Throwable) {
                // swallow
            }
        }
    }

    private fun scheduleSingleRetry(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val retryIntent = Intent(context, WatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                WATCHDOG_RETRY_REQUEST_CODE,
                retryIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val triggerAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)

            // write a fallback log via the service if possible
            try {
                context.startService(Intent(context, WatchdogEventLogger::class.java).apply {
                    action = "com.omama.celltowerlogger.WATCHDOG_ALARM_RETRY_SCHEDULED"
                    putExtra("retry_at", System.currentTimeMillis() + RETRY_DELAY_MS)
                })
            } catch (ignored: Throwable) {
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
