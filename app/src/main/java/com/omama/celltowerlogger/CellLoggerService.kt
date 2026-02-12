package com.omama.celltowerlogger

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class CellLoggerService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    companion object {
        private const val CHANNEL_ID = "SurvivalChannel"
        private const val NOTIF_ID = 200
        private const val PREFS = "survival_prefs"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    }

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Initializing..."))

        detectRestart()
        logEvent("SERVICE_ON_CREATE", null)

        startBatchedLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == "ACTION_STOP") {
            logEvent("USER_STOPPED", null)
            fusedLocationClient.removeLocationUpdates(locationCallback)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logEvent("TASK_REMOVED", null)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        logEvent("SERVICE_ON_DESTROY", null)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    @SuppressLint("MissingPermission")
    private fun startBatchedLocation() {

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10 * 60 * 1000L
        )
            .setMinUpdateIntervalMillis(5 * 60 * 1000L)
            .setMaxUpdateDelayMillis(10 * 60 * 1000L)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location? = result.lastLocation
                if (loc != null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
                        .apply()

                    logEvent("LOCATION_UPDATE", loc)
                    updateNotification("Lat: ${loc.latitude}, Lon: ${loc.longitude}")
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun detectRestart() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_HEARTBEAT, -1L)

        if (last > 0) {
            val gap = System.currentTimeMillis() - last
            if (gap > 15 * 60 * 1000L) {
                logEvent("PROCESS_RESTART_DETECTED gap_ms=$gap", null)
            }
        }
    }

    private fun logEvent(event: String, location: Location?) {

        val file = File(filesDir, "survival_logs.csv")

        val header =
            "Timestamp,Event,Latitude,Longitude,Accuracy,ScreenOn,BattOptIgnored,Manufacturer,PID\n"

        // If file does not exist OR header mismatches, recreate it
        if (!file.exists() || file.length() == 0L) {
            file.writeText(header)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val ignoringOpt = pm.isIgnoringBatteryOptimizations(packageName)
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val pid = android.os.Process.myPid()

        val lat = location?.latitude?.toString() ?: "N/A"
        val lon = location?.longitude?.toString() ?: "N/A"
        val acc = location?.accuracy?.toString() ?: "N/A"

        val row =
            "${timestamp()},$event,$lat,$lon,$acc,$screenOn,$ignoringOpt,$manufacturer,$pid\n"

        file.appendText(row)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Survival Logger",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {

        val stopIntent = Intent(this, CellLoggerService::class.java).apply {
            action = "ACTION_STOP"
        }

        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Survival Logger Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
