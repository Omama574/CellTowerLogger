package com.omama.celltowerlogger

import android.Manifest
import android.annotation.SuppressLint
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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDownload: Button
    private lateinit var statusText: TextView
    private lateinit var lastFixInfo: TextView

    private val requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false

        if (fineLocationGranted && phoneStateGranted) {
            // Permissions are granted, now check for background location permission
            checkBackgroundLocationPermission()
        } else {
            Toast.makeText(this, "High Accuracy Location and Phone State permissions are required.", Toast.LENGTH_LONG).show()
            btnStart.isEnabled = false
        }
    }

    private val requestBackgroundLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Background location permission granted.", Toast.LENGTH_SHORT).show()
            btnStart.isEnabled = true
        } else {
            Toast.makeText(this, "Background Location is essential for logging while the app is not visible.", Toast.LENGTH_LONG).show()
            btnStart.isEnabled = false
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDownload = findViewById(R.id.btnDownload)
        statusText = findViewById(R.id.statusText)
        lastFixInfo = findViewById(R.id.lastFixInfo)

        btnStart.isEnabled = false // Disable button by default

        btnStart.setOnClickListener {
            val serviceIntent = Intent(this, CellLoggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusText.text = "Status: Recording..."
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, CellLoggerService::class.java))
            statusText.text = "Status: Idle"
        }

        btnDownload.setOnClickListener {
            shareLogFile()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            // Permissions are already granted, check for background location
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Background Location is needed to log towers while the screen is off", Toast.LENGTH_LONG).show()
                requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                btnStart.isEnabled = true
            }
        } else {
            btnStart.isEnabled = true // No background permission needed for older versions
        }
    }

    private fun shareLogFile() {
        val logFile = File(filesDir, "tower_logs.csv")
        if (!logFile.exists()) {
            Toast.makeText(this, "Log file not found.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            logFile
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, fileUri)
            type = "text/csv"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "Share Log File"))
    }
}