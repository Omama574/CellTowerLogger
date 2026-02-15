package com.omama.celltowerlogger

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.*
import kotlin.math.min
import kotlin.math.pow

class CellLoggerService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationCallbackRegistered = false

    companion object {
        private const val CHANNEL_ID = "SurvivalChannel"
        private const val NOTIF_ID = 200
        private const val PREFS = "survival_prefs"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val KEY_WATCHDOG_SCHEDULED_AT = "watchdog_scheduled_at"
        private const val KEY_BACKOFF_COUNT = "watchdog_backoff_count"
        private const val KEY_LAST_LOC_PROCESSED_AT = "last_loc_processed_at"
        private const val KEY_LAST_SCHEDULE_ACTION_AT = "last_schedule_action_at"
        private const val KEY_SERVICE_REQUESTED = "KEY_SERVICE_REQUESTED"

        const val ACTION_WATCHDOG_RESTART = "com.omama.celltowerlogger.ACTION_WATCHDOG_RESTART"
        private const val WATCHDOG_REQUEST_CODE = 1001

        // Tunables
        private const val WATCHDOG_MS_DEFAULT = 15 * 60 * 1000L
        private const val ONE_SHOT_TIMEOUT_MS = 30_000L
        private const val DEBOUNCE_LOCATION_MS = 5_000L
        private const val SCHEDULE_DEBOUNCE_MS = 3_000L

        // Backoff policy
        private const val BACKOFF_BASE_MULTIPLIER = 2.0
        private const val BACKOFF_MIN_MS = 30 * 60 * 1000L
        private const val BACKOFF_MAX_MS = 6 * 60 * 60 * 1000L

        // Freshness threshold for one-shot acceptance (2 minutes)
        private const val ONE_SHOT_FRESHNESS_MS = 2 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var oneShotHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // start foreground for initial start
        startForegroundCompat(NOTIF_ID, buildNotification("Initializing..."))

        // persist service requested so BootReceiver can restart if needed
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SERVICE_REQUESTED, true).apply()

        CsvLogger.logEvent(this, "SERVICE_ON_CREATE", null)

        // register location and schedule initial watchdog
        safeRegisterLocationCallbacks()
        scheduleWatchdogIfNeeded(WATCHDOG_MS_DEFAULT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground immediately to satisfy startForegroundService 5s rule
        startForegroundCompat(NOTIF_ID, buildNotification("Survival Logger Running"))

        when (intent?.action) {
            "ACTION_STOP", "com.omama.celltowerlogger.ACTION_STOP" -> {
                CsvLogger.logEvent(this, "USER_STOPPED", null)
                safeUnregisterLocationCallbacks()
                cancelWatchdog()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SERVICE_REQUESTED, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_WATCHDOG_RESTART -> {
                // aggressive single resurrection attempt
                handleWatchdogRestart()
                return START_STICKY
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CsvLogger.logEvent(this, "TASK_REMOVED", null)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        CsvLogger.logEvent(this, "SERVICE_ON_DESTROY", null)
        safeUnregisterLocationCallbacks()
        cancelWatchdog()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ------------------------------
    // startForeground compatibility
    // ------------------------------
    private fun startForegroundCompat(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(id, notification)
            }
        } catch (t: Throwable) {
            // best-effort fallback
            t.printStackTrace()
            startForeground(id, notification)
        }
    }

    // ------------------------------------------------------------
    // Safe registration / unregistration of location callbacks
    // ------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun safeRegisterLocationCallbacks() {
        // remove previous callback (if any) to avoid stacking
        try {
            if (locationCallbackRegistered) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (t: Throwable) {
            // ignore
        }

        // create fresh callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val lastProcessed = prefs.getLong(KEY_LAST_LOC_PROCESSED_AT, 0L)

                // debounce to collapse batched flushes
                if (now - lastProcessed < DEBOUNCE_LOCATION_MS) {
                    CsvLogger.logEvent(this@CellLoggerService, "LOCATION_IGNORED_DEBOUNCE", loc)
                    return
                }

                // reset debounce and backoff on normal heartbeat
                prefs.edit()
                    .putLong(KEY_LAST_LOC_PROCESSED_AT, now)
                    .putLong(KEY_LAST_HEARTBEAT, now)
                    .putInt(KEY_BACKOFF_COUNT, 0)
                    .apply()

                CsvLogger.logEvent(this@CellLoggerService, "LOCATION_UPDATE", loc)
                updateNotification("Lat: ${loc.latitude}, Lon: ${loc.longitude}")

                // cancel any watchdog and schedule normal watchdog
                cancelWatchdog()
                scheduleWatchdogIfNeeded(WATCHDOG_MS_DEFAULT)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                CsvLogger.logEvent(this@CellLoggerService, "LOCATION_AVAILABILITY:available=${availability.isLocationAvailable}", null)
            }
        }

        // request updates and log availability
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                10 * 60 * 1000L
            )
                .setMinUpdateIntervalMillis(5 * 60 * 1000L)
                .setMaxUpdateDelayMillis(10 * 60 * 1000L)
                .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                .build()

            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationCallbackRegistered = true
            CsvLogger.logEvent(this, "FLP_REQUEST_REG_SUCCESS", null)

            // immediate availability check
            fusedLocationClient.getLocationAvailability()
                .addOnSuccessListener { avail ->
                    CsvLogger.logEvent(this, "GET_LOCATION_AVAIL_SUCCESS:available=${avail.isLocationAvailable}", null)
                }
                .addOnFailureListener { ex ->
                    CsvLogger.logEvent(this, "GET_LOCATION_AVAIL_FAILED:${ex.javaClass.simpleName}", null)
                }

        } catch (t: Throwable) {
            CsvLogger.logEvent(this, "FLP_REQUEST_REG_FAILED:${t.javaClass.simpleName}", null)
        }
    }

    private fun safeUnregisterLocationCallbacks() {
        try {
            if (locationCallbackRegistered) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (t: Throwable) {
            // ignore
        } finally {
            locationCallbackRegistered = false
        }
    }

    // ------------------------------------------------------------
    // Watchdog scheduling — single owner, guarded by persisted timestamp
    // ------------------------------------------------------------
    private fun scheduleWatchdogIfNeeded(intervalMs: Long) {
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val scheduledAt = prefs.getLong(KEY_WATCHDOG_SCHEDULED_AT, -1L)
            val lastScheduleAction = prefs.getLong(KEY_LAST_SCHEDULE_ACTION_AT, 0L)

            // avoid schedule spam
            if (now - lastScheduleAction < SCHEDULE_DEBOUNCE_MS) {
                CsvLogger.logEvent(this, "WATCHDOG_SCHEDULE_SKIPPED_DEBOUNCE", null)
                return
            }

            // single-owner guard: if an alarm is already scheduled in the future, skip
            if (scheduledAt > now + 1000L) {
                CsvLogger.logEvent(this, "WATCHDOG_ALREADY_SCHEDULED", null)
                return
            }

            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = getWatchdogPendingIntent()
            val trigger = SystemClock.elapsedRealtime() + intervalMs

            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)

            prefs.edit()
                .putLong(KEY_WATCHDOG_SCHEDULED_AT, now + intervalMs)
                .putLong(KEY_LAST_SCHEDULE_ACTION_AT, now)
                .apply()

            CsvLogger.logEvent(this, "WATCHDOG_SCHEDULED", null)
        } catch (t: Throwable) {
            CsvLogger.logEvent(this, "WATCHDOG_SCHEDULE_FAILED:${t.javaClass.simpleName}", null)
        }
    }

    private fun cancelWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = getWatchdogPendingIntent()
            am.cancel(pi)
            pi.cancel()
        } catch (t: Throwable) {
            // ignore
        } finally {
            // clear persisted scheduled timestamp
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_WATCHDOG_SCHEDULED_AT).apply()
            CsvLogger.logEvent(this, "WATCHDOG_CANCELLED", null)
        }
    }

    private fun getWatchdogPendingIntent(): PendingIntent {
        val intent = Intent(this, WatchdogReceiver::class.java)
        intent.action = "com.omama.celltowerlogger.WATCHDOG_ALARM"
        return PendingIntent.getBroadcast(
            this,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ------------------------------------------------------------
    // Watchdog restart handler: single aggressive attempt with timeout and backoff
    // ------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun handleWatchdogRestart() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, -1L)
        val now = System.currentTimeMillis()

        // If we have a sufficiently recent heartbeat, abort
        if (lastHeartbeat > 0 && now - lastHeartbeat < WATCHDOG_MS_DEFAULT / 2) {
            CsvLogger.logEvent(this, "WATCHDOG_ABORT_RECENT_HEARTBEAT", null)
            cancelWatchdog()
            return
        }

        CsvLogger.logEvent(this, "PROCESS_RESTORED_ATTEMPT", null)

        // acquire wakelock (length > one-shot timeout to protect registration)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:watchdogwl")
            wakeLock?.acquire(ONE_SHOT_TIMEOUT_MS + 5_000L)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        // ensure we register a fresh callback (removes old)
        safeRegisterLocationCallbacks()

        // prepare cancellation + timeout for one-shot
        val cts = CancellationTokenSource()
        oneShotHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            try {
                cts.cancel()
            } catch (_: Throwable) {}
            CsvLogger.logEvent(this, "ONE_SHOT_TIMEOUT", null)
            handleBackoff()
            releaseWakeLock()
        }
        oneShotHandler?.postDelayed(timeoutRunnable, ONE_SHOT_TIMEOUT_MS)

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    // cancel timeout
                    oneShotHandler?.removeCallbacks(timeoutRunnable)

                    if (loc != null) {
                        val age = System.currentTimeMillis() - loc.time
                        if (age <= ONE_SHOT_FRESHNESS_MS) {
                            // valid fresh fix
                            prefs.edit().putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).putInt(KEY_BACKOFF_COUNT, 0).apply()
                            CsvLogger.logEvent(this, "ONE_SHOT_LOCATION", loc)

                            // cancel previous watchdog and schedule default
                            cancelWatchdog()
                            scheduleWatchdogIfNeeded(WATCHDOG_MS_DEFAULT)
                        } else {
                            CsvLogger.logEvent(this, "ONE_SHOT_STALE_IGNORED_age_ms=$age", loc)
                            handleBackoff()
                        }
                    } else {
                        CsvLogger.logEvent(this, "ONE_SHOT_LOCATION_NULL", null)
                        handleBackoff()
                    }

                    releaseWakeLock()
                }
                .addOnFailureListener { ex ->
                    oneShotHandler?.removeCallbacks(timeoutRunnable)
                    CsvLogger.logEvent(this, "ONE_SHOT_LOCATION_FAILED:${ex.javaClass.simpleName}", null)
                    handleBackoff()
                    releaseWakeLock()
                }
        } catch (t: Throwable) {
            oneShotHandler?.removeCallbacks(timeoutRunnable)
            CsvLogger.logEvent(this, "ONE_SHOT_CALL_THROWN:${t.javaClass.simpleName}", null)
            handleBackoff()
            releaseWakeLock()
        }
    }

    private fun handleBackoff() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val currentBackoff = prefs.getInt(KEY_BACKOFF_COUNT, 0)
        val next = currentBackoff + 1
        prefs.edit().putInt(KEY_BACKOFF_COUNT, next).apply()

        val exponential = (WATCHDOG_MS_DEFAULT * BACKOFF_BASE_MULTIPLIER.pow(next.toDouble())).toLong()
        val computed = maxOf(BACKOFF_MIN_MS, exponential)
        val backoff = min(computed, BACKOFF_MAX_MS)

        CsvLogger.logEvent(this, "ONE_SHOT_FAILED_SCHED_BACKOFF_ms=$backoff", null)
        scheduleWatchdogIfNeeded(backoff)
    }

    private fun releaseWakeLock() {
        try {
            oneShotHandler?.removeCallbacksAndMessages(null)
            oneShotHandler = null
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            wakeLock = null
        }
    }

    // -------------------------
    // Notification helpers
    // -------------------------
    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Survival Logger", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CellLoggerService::class.java).apply { action = "ACTION_STOP" }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Survival Logger Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }
}
