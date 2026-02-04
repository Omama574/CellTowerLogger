package com.omama.celltowerlogger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CellLoggerService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var loggingRunnable: Runnable
    private val LOG_INTERVAL = 60000L // 1 minute
    private val TEN_MINUTES_IN_MILLIS = 10 * 60 * 1000L

    private val notificationChannelId = "CellLoggerChannel"

    private var lastLoggedServingCellId: String? = null
    private var lastWriteTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Cell Logger Active")
            .setContentText("Logging cell tower information.")
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure you have this icon
            .build()

        startForeground(1, notification)

        startLogging()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                notificationChannelId,
                "Cell Logger",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startLogging() {
        loggingRunnable = Runnable {
            scanCellTowers()
            handler.postDelayed(loggingRunnable, LOG_INTERVAL)
        }
        handler.post(loggingRunnable)
    }

    private fun scanCellTowers() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            saveToFile("Error: Location permission not granted.")
            return
        }

        val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo
        if (cellInfoList.isNullOrEmpty()) {
            return // Nothing to log
        }

        val servingCell = cellInfoList.firstOrNull { it.isRegistered }
        if (servingCell == null) {
            return // No serving cell found
        }

        val currentServingCellId = getCellId(servingCell)
        val currentTime = System.currentTimeMillis()

        // Check if we need to log
        if (currentServingCellId != lastLoggedServingCellId || (currentTime - lastWriteTime) > TEN_MINUTES_IN_MILLIS) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(currentTime))

            // Log all cells (serving and neighbors) in a batch
            cellInfoList.forEach { cellInfo ->
                val data = formatCellInfo(cellInfo, timestamp)
                if (data.isNotEmpty()) {
                    saveToFile(data)
                }
            }
            // Update state
            lastLoggedServingCellId = currentServingCellId
            lastWriteTime = currentTime
        }
    }

    private fun getCellId(cellInfo: CellInfo): String? {
        return when (cellInfo) {
            is CellInfoLte -> "${cellInfo.cellIdentity.mccString}-${cellInfo.cellIdentity.mncString}-${cellInfo.cellIdentity.ci}"
            is CellInfoGsm -> "${cellInfo.cellIdentity.mccString}-${cellInfo.cellIdentity.mncString}-${cellInfo.cellIdentity.cid}"
            is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${(cellInfo.cellIdentity as CellIdentityNr).mccString}-${(cellInfo.cellIdentity as CellIdentityNr).mncString}-${(cellInfo.cellIdentity as CellIdentityNr).nci}" else null
            is CellInfoWcdma -> "${cellInfo.cellIdentity.mccString}-${cellInfo.cellIdentity.mncString}-${cellInfo.cellIdentity.cid}"
            else -> null
        }
    }

    private fun formatCellInfo(cellInfo: CellInfo, timestamp: String): String {
        var data = "$timestamp,"
        val isRegistered = cellInfo.isRegistered
        try {
            when (cellInfo) {
                is CellInfoLte -> {
                    val identity = cellInfo.cellIdentity
                    val strength = cellInfo.cellSignalStrength
                    data += "LTE,${identity.mccString},${identity.mncString},${identity.ci},${identity.tac},${strength.dbm},${isRegistered}"
                }
                is CellInfoGsm -> {
                    val identity = cellInfo.cellIdentity
                    val strength = cellInfo.cellSignalStrength
                    data += "GSM,${identity.mccString},${identity.mncString},${identity.cid},${identity.lac},${strength.dbm},${isRegistered}"
                }
                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val identity = cellInfo.cellIdentity as CellIdentityNr
                        val strength = cellInfo.cellSignalStrength as CellSignalStrengthNr
                        data += "NR,${identity.mccString},${identity.mncString},${identity.nci},${identity.tac},${strength.dbm},${isRegistered}"
                    }
                }
                is CellInfoWcdma -> {
                    val identity = cellInfo.cellIdentity
                    val strength = cellInfo.cellSignalStrength
                    data += "WCDMA,${identity.mccString},${identity.mncString},${identity.cid},${identity.lac},${strength.dbm},${isRegistered}"
                }
            }
        } catch (e: Exception) {
            return "$timestamp,Error parsing cell info: ${e.message}"
        }
        // Return data only if it was populated
        return if (data.split(",").size > 2) data else ""
    }


    private fun saveToFile(data: String) {
        val logFile = File(filesDir, "tower_logs.csv")
        val isNewFile = !logFile.exists()
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                if (isNewFile) {
                    writer.write("Time,Type,MCC,MNC,CID,LAC_TAC,Signal,isServing")
                    writer.newLine()
                }
                writer.write(data)
                writer.newLine()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(loggingRunnable)
        saveToFile("Logging service stopped.")
    }
}