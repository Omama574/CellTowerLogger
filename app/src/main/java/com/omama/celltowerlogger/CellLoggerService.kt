package com.omama.celltowerlogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CellLoggerService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager

    private val cellExecutor = Executors.newSingleThreadExecutor()
    private val locationScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private var telephonyCallback: Any? = null
    private var locationCallback: LocationCallback? = null

    // State variables for notification and cross-event logging
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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        startCellInfoListener()
        startLocationAudit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startLocationAudit() {
        val auditTask = Runnable { requestLocationFix() }
        locationScheduler.scheduleAtFixedRate(auditTask, 0, AUDIT_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationFix() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val requestStartTime = System.currentTimeMillis()
        lastGpsStatus = "Searching..."
        updateNotification()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()

        val timeoutHandler = locationScheduler.schedule({
            if (locationCallback != null) {
                logLocationFailure()
                removeLocationUpdates()
            }
        }, 1, TimeUnit.MINUTES) // 1-minute timeout for a fix

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                timeoutHandler.cancel(false) // Fix received, cancel timeout
                val location = locationResult.lastLocation ?: return
                logLocationSuccess(location, requestStartTime)
                removeLocationUpdates()
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
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
            telephonyManager.registerTelephonyCallback(cellExecutor, telephonyCallback as TelephonyCallback)
        } else {
            telephonyCallback = object : PhoneStateListener(cellExecutor) {
                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) { cellInfo?.let { handleCellInfoChange(it) } }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(telephonyCallback as PhoneStateListener, PhoneStateListener.LISTEN_CELL_INFO)
        }
    }

    private fun handleCellInfoChange(cellInfoList: List<CellInfo>) {
        val newServingCell = cellInfoList.firstOrNull { it.isRegistered }
        if (newServingCell == null || getCellId(newServingCell) == getCellId(lastServingCellInfo)) {
            return // No change or no serving cell
        }
        lastServingCellInfo = newServingCell
        logCellChange(cellInfoList)
    }

    // --- Logging & Formatting --- //

    private fun logCellChange(cellInfoList: List<CellInfo>) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        cellInfoList.forEach { cellInfo ->
            val (cid, lacTac) = getCellIdentifiers(cellInfo)
            val signal = getSignalStrength(cellInfo)
            // For cell changes, location data is N/A
            val logString = "$timestamp,CELL_CHANGE,$cid,$lacTac,$signal,N/A,N/A,N/A,N/A"
            writeToCsv(logString)
        }
        updateNotification()
    }

    private fun logLocationSuccess(location: Location, requestStartTime: Long) {
        val responseTime = System.currentTimeMillis()
        val latency = responseTime - requestStartTime
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(responseTime))

        // For location events, use the last known serving cell data
        val (cid, lacTac) = getCellIdentifiers(lastServingCellInfo)
        val signal = getSignalStrength(lastServingCellInfo)

        lastGpsStatus = "$latency ms"
        val logString = "$timestamp,LOCATION_SUCCESS,$cid,$lacTac,$signal,${location.latitude},${location.longitude},${location.accuracy},$latency"
        writeToCsv(logString)
        updateNotification()
    }

    private fun logLocationFailure() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        lastGpsStatus = "Timeout"
        // For failed location, cell and location data is N/A, only latency is relevant
        val logString = "$timestamp,LOCATION_FAILURE,N/A,N/A,N/A,N/A,N/A,N/A,TIMEOUT"
        writeToCsv(logString)
        updateNotification()
    }

    private fun writeToCsv(data: String) {
        val logFile = File(filesDir, "tower_logs.csv")
        val isNewFile = !logFile.exists()
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                if (isNewFile) {
                    writer.write("Timestamp,Event_Type,CID,LAC_TAC,Signal_dBm,Lat,Lon,Accuracy,Latency_ms")
                    writer.newLine()
                }
                writer.write(data)
                writer.newLine()
                writer.flush() // Force write to disk
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Notification --- //

    private fun buildNotification(): Notification {
        val (cid, _) = getCellIdentifiers(lastServingCellInfo)
        val contentText = "Tower: $cid | GPS: $lastGpsStatus"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cell & Location Audit Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true) // Don't make sound on update
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    // --- Helper Functions --- //

    private fun getCellId(cellInfo: CellInfo?): String {
        val (cid, _) = getCellIdentifiers(cellInfo)
        return cid
    }

    private fun getCellIdentifiers(cellInfo: CellInfo?): Pair<String, String> {
        if (cellInfo == null) return Pair("N/A", "N/A")
        return when (cellInfo) {
            is CellInfoLte -> Pair(cellInfo.cellIdentity.ci.toString(), cellInfo.cellIdentity.tac.toString())
            is CellInfoGsm -> Pair(cellInfo.cellIdentity.cid.toString(), cellInfo.cellIdentity.lac.toString())
            is CellInfoWcdma -> Pair(cellInfo.cellIdentity.cid.toString(), cellInfo.cellIdentity.lac.toString())
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Pair((cellInfo.cellIdentity as CellIdentityNr).nci.toString(), (cellInfo.cellIdentity as CellIdentityNr).tac.toString()) else Pair("N/A", "N/A")
            else -> Pair("N/A", "N/A")
        }
    }

    private fun getSignalStrength(cellInfo: CellInfo?): String {
        if (cellInfo == null) return "N/A"
        return when (cellInfo) {
            is CellInfoLte -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoGsm -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoWcdma -> cellInfo.cellSignalStrength.dbm.toString()
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (cellInfo.cellSignalStrength as CellSignalStrengthNr).dbm.toString()
                } else {
                    "N/A"
                }
            }
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
        locationScheduler.shutdownNow()
        cellExecutor.shutdown()
        removeLocationUpdates()
        writeToCsv("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())},SERVICE_STOPPED,N/A,N/A,N/A,N/A,N/A,N/A,N/A")
    }
}