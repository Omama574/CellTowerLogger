package com.omama.celltowerlogger

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CsvLogger {

    private const val PREFS = "survival_prefs"
    private const val KEY_WATCHDOG_SCHEDULED_AT = "watchdog_scheduled_at"

    fun logEvent(context: Context, event: String, location: Location?) {

        val file = File(context.filesDir, "survival_logs.csv")

        val header =
            "Timestamp,Event,Latitude,Longitude,Accuracy,ScreenOn,BattOptIgnored,Manufacturer,PID,scheduled_watchdog_at_ms\n"

        if (!file.exists() || file.length() == 0L) {
            file.writeText(header)
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val ignoringOpt = pm.isIgnoringBatteryOptimizations(context.packageName)
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val pid = android.os.Process.myPid()

        val lat = location?.latitude?.toString() ?: "N/A"
        val lon = location?.longitude?.toString() ?: "N/A"
        val acc = location?.accuracy?.toString() ?: "N/A"

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduledAt = prefs.getLong(KEY_WATCHDOG_SCHEDULED_AT, -1L)

        val row =
            "${timestamp()},$event,$lat,$lon,$acc,$screenOn,$ignoringOpt,$manufacturer,$pid,$scheduledAt\n"

        file.appendText(row)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
