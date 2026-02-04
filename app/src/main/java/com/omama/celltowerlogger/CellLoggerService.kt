package com.omama.celltowerlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

class CellLoggerService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager

    private var cellExecutor: ExecutorService? = null
    private var locationScheduler: ScheduledExecutorService? = null

    private var telephonyCallback: Any? = null
    private var locationCallback: LocationCallback? = null

    private var lastServingCellInfo: CellInfo? = null
    private var lastGpsStatus: String = "Starting..."

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "CellLoggerChannel"
        private const val NOTIFICATION_ID = 1
        private const val AUDIT_INTERVAL_MINUTES = 5L
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize fresh workers every time the service starts
        cellExecutor = Executors.newSingleThreadExecutor()
        locationScheduler = Executors.newSingleThreadScheduledExecutor()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 1. Mark the start in the CSV
        writeToCsv("${getTimestamp()},SERVICE_STARTED,N/A,N/A,N/A,N/A,N/A,N/A,N/A")

        // 2. Start listeners
        startCellInfoListener()

        // 3. Immediately log current state
        logCurrentCellState()

        // 4. Start the 5-minute Audit
        startLocationAudit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startLocationAudit() {
        // Use scheduleWithFixedDelay for a steady 5-minute heartbeat
        locationScheduler?.scheduleWithFixedDelay({
            requestLocationFix()
        }, 0, AUDIT_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationFix() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val requestStartTime = System.currentTimeMillis()
        lastGpsStatus = "Searching (Balanced)..."
        updateNotification()

        // V3: Balanced Power (Cell/Wi-Fi)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()

        // Give up after 4 mins to reset for the next 5-min cycle
        val timeoutHandler = locationScheduler?.schedule({
            if (locationCallback != null) {
                logLocationFailure()
                removeLocationUpdates()
            }
        }, 4, TimeUnit.MINUTES)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                timeoutHandler?.cancel(false)
                val location = locationResult.lastLocation ?: return
                logLocationSuccess(location, requestStartTime)
                removeLocationUpdates()
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun logCurrentCellState() {
        cellExecutor?.execute {
            val cellInfoList = telephonyManager.allCellInfo
            if (!cellInfoList.isNullOrEmpty()) {
                handleCellInfoChange(cellInfoList)
            }
        }
    }

    private fun removeLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCellInfoListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) { handleCellInfoChange(cellInfo) }
            }
            telephonyManager.registerTelephonyCallback(cellExecutor!!, telephonyCallback as TelephonyCallback)
        } else {
            telephonyCallback = object : PhoneStateListener() {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    cellExecutor?.execute { cellInfo?.let { handleCellInfoChange(it) } }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(telephonyCallback as PhoneStateListener, PhoneStateListener.LISTEN_CELL_INFO)
        }
    }

    private fun handleCellInfoChange(cellInfoList: List<CellInfo>) {
        val newServingCell = cellInfoList.firstOrNull { it.isRegistered }
        if (newServingCell == null || getCellId(newServingCell) == getCellId(lastServingCellInfo)) return

        lastServingCellInfo = newServingCell
        logCellChange(cellInfoList)
    }

    private fun logCellChange(cellInfoList: List<CellInfo>) {
        val ts = getTimestamp()
        cellInfoList.forEach { cellInfo ->
            val (cid, lacTac) = getCellIdentifiers(cellInfo)
            val signal = getSignalStrength(cellInfo)
            val eventLabel = if (cellInfo.isRegistered) "SERVING_CELL" else "NEIGHBOR"
            writeToCsv("$ts,$eventLabel,$cid,$lacTac,$signal,N/A,N/A,N/A,N/A")
        }
        updateNotification()
    }

    private fun logLocationSuccess(location: Location, startTime: Long) {
        val now = System.currentTimeMillis()
        val latency = now - startTime
        val (cid, lacTac) = getCellIdentifiers(lastServingCellInfo)
        val signal = getSignalStrength(lastServingCellInfo)

        lastGpsStatus = "$latency ms"
        writeToCsv("${getTimestamp(now)},BALANCED_FIX,$cid,$lacTac,$signal,${location.latitude},${location.longitude},${location.accuracy},$latency")
        updateNotification()
    }

    private fun logLocationFailure() {
        lastGpsStatus = "Timeout"
        writeToCsv("${getTimestamp()},LOCATION_FAILURE,N/A,N/A,N/A,N/A,N/A,N/A,TIMEOUT")
        updateNotification()
    }

    private fun writeToCsv(data: String) {
        val logFile = File(filesDir, "tower_logs.csv")
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                if (logFile.length() == 0L) {
                    writer.write("Timestamp,Event_Type,CID,LAC_TAC,Signal_dBm,Lat,Lon,Accuracy,Latency_ms")
                    writer.newLine()
                }
                writer.write(data)
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun buildNotification(): Notification {
        val (cid, _) = getCellIdentifiers(lastServingCellInfo)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cell Audit Active")
            .setContentText("Tower: $cid | Fix: $lastGpsStatus")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }

    private fun getTimestamp(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

    private fun getCellId(cellInfo: CellInfo?): String = getCellIdentifiers(cellInfo).first

    private fun getCellIdentifiers(cellInfo: CellInfo?): Pair<String, String> {
        if (cellInfo == null) return Pair("N/A", "N/A")
        return when (cellInfo) {
            is CellInfoLte -> Pair(cellInfo.cellIdentity.ci.toString(), cellInfo.cellIdentity.tac.toString())
            is CellInfoGsm -> Pair(cellInfo.cellIdentity.cid.toString(), cellInfo.cellIdentity.lac.toString())
            is CellInfoWcdma -> Pair(cellInfo.cellIdentity.cid.toString(), cellInfo.cellIdentity.lac.toString())
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val id = cellInfo.cellIdentity as CellIdentityNr
                Pair(id.nci.toString(), id.tac.toString())
            } else Pair("N/A", "N/A")
            else -> Pair("N/A", "N/A")
        }
    }

    private fun getSignalStrength(cellInfo: CellInfo?): String {
        if (cellInfo == null) return "N/A"
        return when (cellInfo) {
            is CellInfoLte -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoGsm -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoWcdma -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (cellInfo.cellSignalStrength as CellSignalStrengthNr).dbm.toString() else "N/A"
            else -> "N/A"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Cell Logger", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (telephonyCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(telephonyCallback as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
        locationScheduler?.shutdownNow()
        cellExecutor?.shutdownNow()
        removeLocationUpdates()
        writeToCsv("${getTimestamp()},SERVICE_STOPPED,N/A,N/A,N/A,N/A,N/A,N/A,N/A")
    }
}