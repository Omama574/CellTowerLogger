package com.omama.celltowerlogger

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDownload: Button
    private lateinit var statusText: TextView

    private val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val phoneState = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineLocation && phoneState && notifications) {
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "All permissions are required to operate.", Toast.LENGTH_LONG).show()
            updateButtonStates()
        }
    }

    private val requestBackgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Background location is essential for train tracking.", Toast.LENGTH_LONG).show()
        }
        updateButtonStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDownload = findViewById(R.id.btnDownload)
        statusText = findViewById(R.id.statusText)

        btnStart.setOnClickListener { handleStartClick() }
        btnStop.setOnClickListener { handleStopClick() }
        btnDownload.setOnClickListener { shareLogFile() }

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates() // Sync UI with Service state
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (CellLoggerService::class.java.name == service.service.className) return true
        }
        return false
    }

    private fun handleStartClick() {
        if (allPermissionsGranted()) {
            val serviceIntent = Intent(this, CellLoggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            // Small delay to let the system start the service before checking state
            btnStart.postDelayed({ updateButtonStates() }, 300)
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun handleStopClick() {
        stopService(Intent(this, CellLoggerService::class.java))
        btnStop.postDelayed({ updateButtonStates() }, 300)
    }

    private fun checkAndRequestPermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needsRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(needsRequest.toTypedArray())
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                updateButtonStates()
            }
        } else {
            updateButtonStates()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        val play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

        return fine && phone && bg && play
    }

    private fun updateButtonStates() {
        val running = isServiceRunning()
        val ready = allPermissionsGranted()

        btnStart.isEnabled = ready && !running
        btnStop.isEnabled = running
        btnDownload.isEnabled = !running
        statusText.text = if (running) "Status: Recording..." else if (!ready) "Status: Missing Permissions" else "Status: Idle"
    }

    private fun shareLogFile() {
        val logFile = File(filesDir, "tower_logs.csv")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "No logs available.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Download Logs"))
    }
}