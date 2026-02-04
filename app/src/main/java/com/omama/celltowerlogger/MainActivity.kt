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
    private lateinit var lastFixInfo: TextView

    // Activity Result Launcher for standard permissions
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val phoneState = permissions[Manifest.permission.READ_PHONE_STATE] ?: false

        // Android 13+ Notification permission check
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineLocation && phoneState && notifications) {
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "Location, Phone State, and Notifications are required.", Toast.LENGTH_LONG).show()
            updateButtonStates()
        }
    }

    // Separate launcher for Background Location (Android requirements)
    private val requestBackgroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Background Location is essential for logging while the screen is off.", Toast.LENGTH_LONG).show()
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
        lastFixInfo = findViewById(R.id.lastFixInfo)

        btnStart.setOnClickListener { handleStartClick() }
        btnStop.setOnClickListener { handleStopClick() }
        btnDownload.setOnClickListener { shareLogFile() }

        // Initial check on launch
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // This ensures if the user comes back to the app, buttons reflect reality
        updateButtonStates()
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (CellLoggerService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun handleStartClick() {
        // Re-check permissions right before starting as an edge-case safety
        if (allPermissionsGranted()) {
            val serviceIntent = Intent(this, CellLoggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateButtonStates()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun handleStopClick() {
        stopService(Intent(this, CellLoggerService::class.java))
        // Small delay to allow service to clean up before UI refresh
        btnStop.postDelayed({ updateButtonStates() }, 200)
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
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
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val phoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

        return fineLocation && phoneState && backgroundLocation && playServices && notifications
    }

    private fun updateButtonStates() {
        val running = isServiceRunning()
        val readyToStart = allPermissionsGranted()

        btnStart.isEnabled = readyToStart && !running
        btnStop.isEnabled = running
        btnDownload.isEnabled = !running // Prevent sharing while file is being written to

        statusText.text = if (running) "Status: Recording..." else "Status: Idle"

        if (!readyToStart && !running) {
            statusText.text = "Status: Missing Permissions"
        }
    }

    private fun shareLogFile() {
        val logFile = File(filesDir, "tower_logs.csv")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "No logs found to share.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, fileUri)
                type = "text/csv"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Download Logs"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file.", Toast.LENGTH_SHORT).show()
        }
    }
}