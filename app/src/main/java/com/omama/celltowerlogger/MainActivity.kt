package com.omama.celltowerlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView

    // Initial permissions needed to start the app
    private val basePermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            // If GPS and Phone are granted, check for the critical Background Location
            checkBackgroundLocation()
        } else {
            Toast.makeText(this, "All permissions are required for logging", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnShare = findViewById<Button>(R.id.btnShare)

        btnStart.setOnClickListener {
            permissionLauncher.launch(basePermissions)
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, CellLoggerService::class.java))
            txtStatus.text = "Status: Stopped"
        }

        btnShare.setOnClickListener {
            shareCsvFile()
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBackground) {
                // Android requires background location for "Allow all the time"
                Toast.makeText(this, "Set Location to 'Allow all the time' for long tests", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } else {
                startLoggerService()
            }
        } else {
            startLoggerService()
        }
    }

    private fun startLoggerService() {
        val intent = Intent(this, CellLoggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        txtStatus.text = "Status: Running (High Priority)"
    }

    private fun shareCsvFile() {
        val file = File(filesDir, "tower_logs.csv")
        if (!file.exists()) {
            Toast.makeText(this, "No log file found yet", Toast.LENGTH_SHORT).show()
            return
        }

        // Authority must match the one in AndroidManifest.xml provider
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Tower Logs"))
    }
}