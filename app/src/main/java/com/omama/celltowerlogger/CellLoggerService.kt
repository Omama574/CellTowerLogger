package com.omama.celltowerlogger

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import android.telephony.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CellLoggerService : Service() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val fileLock = Any()
    private val isAuditRunning = AtomicBoolean(false)
    private var lastCid = ""

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startForeground(101, buildNotification("Initializing..."))

        // 1. Passive Handover (Checks for tower changes between GPS fixes)
        setupPassiveListener()

        // 2. High Priority GPS Heartbeat (Every 5 minutes)
        scheduler.scheduleAtFixedRate({ if (!isAuditRunning.get()) runHighPriorityAudit() }, 0, 5, TimeUnit.MINUTES)
    }

    @SuppressLint("MissingPermission")
    private fun runHighPriorityAudit() {
        isAuditRunning.set(true)
        val startTime = System.currentTimeMillis()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .setDurationMillis(30000)
            .build()

        fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation
                val latency = System.currentTimeMillis() - startTime

                if (loc != null) {
                    // --- NEW: 2-SECOND MODEM WARM-UP ---
                    // We wait 2 seconds AFTER the GPS fix to let the modem refresh neighbors
                    Handler(Looper.getMainLooper()).postDelayed({
                        val cells = telephonyManager.allCellInfo ?: emptyList()
                        cells.forEach { cell ->
                            val (cid, lac) = getCellIds(cell)
                            if (isValidId(cid)) {
                                val label = if (cell.isRegistered) "AUDIT_SERVING" else "AUDIT_NEIGHBOR"
                                logRow(label, cid, lac, getDbm(cell), loc.latitude.toString(), loc.longitude.toString(), loc.accuracy.toString(), latency.toString())
                            }
                        }
                        // Release lock only after logging is finished
                        isAuditRunning.set(false)
                    }, 2000)
                } else {
                    isAuditRunning.set(false)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun setupPassiveListener() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(info: MutableList<CellInfo>) {
                val serving = info.firstOrNull { it.isRegistered } ?: return
                val (cid, lac) = getCellIds(serving)
                if (isValidId(cid) && cid != lastCid) {
                    lastCid = cid
                    logRow("SERVING_HANDOVER", cid, lac, getDbm(serving), "N/A", "N/A", "N/A", "N/A")
                    updateNotification("Tower: $cid")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(Executors.newSingleThreadExecutor(), callback)
        }
    }

    private fun getCellIds(cell: CellInfo): Pair<String, String> {
        return when (val id = cell.cellIdentity) {
            is CellIdentityLte -> id.ci.toString() to id.tac.toString()
            is CellIdentityGsm -> id.cid.toString() to id.lac.toString()
            is CellIdentityWcdma -> id.cid.toString() to id.lac.toString()
            is CellIdentityNr -> id.nci.toString() to id.tac.toString()
            else -> "N/A" to "N/A"
        }
    }

    private fun isValidId(id: String): Boolean {
        // Filter out N/A, Int Max (2147483647), and Long Max (9223372036854775807)
        return id != "N/A" && id != "2147483647" && id != "9223372036854775807" && id != "0" && id != "-1"
    }

    private fun getDbm(cell: CellInfo): String {
        val dbm = when (val s = cell.cellSignalStrength) {
            is CellSignalStrengthLte -> s.dbm
            is CellSignalStrengthGsm -> s.dbm
            is CellSignalStrengthWcdma -> s.dbm
            is CellSignalStrengthNr -> s.dbm
            else -> -999
        }
        return if (dbm == -999 || dbm == 0 || dbm == 2147483647) "N/A" else dbm.toString()
    }

    private fun logRow(event: String, cid: String, lac: String, dbm: String, lat: String, lon: String, acc: String, latency: String) {
        synchronized(fileLock) {
            try {
                val file = File(filesDir, "tower_logs.csv")
                val isNew = !file.exists()
                BufferedWriter(FileWriter(file, true)).use { out ->
                    if (isNew) out.write("Timestamp,Event_Type,CID,LAC_TAC,Signal_dBm,Lat,Lon,Accuracy,Latency_ms\n")
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    out.write("$ts,$event,$cid,$lac,$dbm,$lat,$lon,$acc,$latency\n")
                }
            } catch (e: Exception) {}
        }
    }

    private fun buildNotification(txt: String) = NotificationCompat.Builder(this, "CellLog")
        .setContentTitle("Cell Logger (High Priority)")
        .setContentText(txt)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true).build()

    private fun updateNotification(txt: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("CellLog", "Logger", NotificationManager.IMPORTANCE_LOW))
        }
        nm.notify(101, buildNotification(txt))
    }

    override fun onBind(p0: Intent?) = null
}