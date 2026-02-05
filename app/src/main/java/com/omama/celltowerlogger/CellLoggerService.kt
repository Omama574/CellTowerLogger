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
import com.google.android.gms.tasks.CancellationTokenSource
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

        writeToCsv("${getTimestamp()},SERVICE_STARTED,N/A,N/A,N/A,N/A,N/A,N/A,N/A")

        startCellInfoListener()
        startLocationAudit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startLocationAudit() {
        // Use scheduleWithFixedDelay to prevent "bursts" after phone wake-up
        locationScheduler.scheduleWithFixedDelay({
            requestLocationFix()
        }, 0, AUDIT_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationFix() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val requestStartTime = System.currentTimeMillis()
        lastGpsStatus = "Searching (High Acc)..."
        updateNotification()

        val cts = CancellationTokenSource()

        // Single-shot request: More reliable for audits than setMaxUpdates(1)
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY, // GNSS required for train speeds
            cts.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                logLocationSuccess(location, requestStartTime)
            } else {
                logLocationFailure("No Fix")
            }
        }.addOnFailureListener {
            logLocationFailure("API Error")
        }

        // 4-minute safety timeout to prevent overlapping with the 5-minute cycle
        locationScheduler.schedule({
            cts.cancel()
        }, 4, TimeUnit.MINUTES)
    }

    @SuppressLint("MissingPermission")
    private fun startCellInfoListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) { handleCellInfoChange(cellInfo) }
            }
            telephonyManager.registerTelephonyCallback(cellExecutor, telephonyCallback as TelephonyCallback)
        } else {
            telephonyCallback = object : PhoneStateListener() {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    cellExecutor.execute { cellInfo?.let { handleCellInfoChange(it) } }
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
            val label = if (cellInfo.isRegistered) "SERVING_HANDOVER" else "NEIGHBOR_DETECTED"
            writeToCsv("$ts,$label,$cid,$lacTac,$signal,N/A,N/A,N/A,N/A")
        }
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun logLocationSuccess(location: Location, startTime: Long) {
        val now = System.currentTimeMillis()
        val latency = now - startTime
        val ts = getTimestamp(now)

        lastGpsStatus = "$latency ms"
        updateNotification()

        // Dump current radio environment at this precise coordinate
        cellExecutor.execute {
            val allTowers = telephonyManager.allCellInfo
            if (!allTowers.isNullOrEmpty()) {
                allTowers.forEach { cellInfo ->
                    val (cid, lac) = getCellIdentifiers(cellInfo)
                    val signal = getSignalStrength(cellInfo)
                    val label = if (cellInfo.isRegistered) "AUDIT_SERVING" else "AUDIT_NEIGHBOR"
                    writeToCsv("$ts,$label,$cid,$lac,$signal,${location.latitude},${location.longitude},${location.accuracy},$latency")
                }
            }
        }
    }

    private fun logLocationFailure(reason: String) {
        lastGpsStatus = "Timeout"
        writeToCsv("${getTimestamp()},LOCATION_FAILURE,N/A,N/A,N/A,N/A,N/A,N/A,$reason")
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
            .setContentText("Tower: $cid | Last Fix: $lastGpsStatus")
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
        locationScheduler.shutdownNow()
        cellExecutor.shutdownNow()
        writeToCsv("${getTimestamp()},SERVICE_STOPPED,N/A,N/A,N/A,N/A,N/A,N/A,N/A")
    }
}