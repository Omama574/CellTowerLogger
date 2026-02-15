package com.omama.celltowerlogger

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // Handles permission result
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            checkLocationSettingsAndStart()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Handles GPS enable dialog result
    private val locationResolutionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startLoggerService()
            } else {
                Toast.makeText(this, "Location is required", Toast.LENGTH_SHORT).show()
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
            permissionLauncher.launch(permissions)
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, CellLoggerService::class.java).apply {
                action = "ACTION_STOP"
            }
            startService(stopIntent)
            txtStatus.text = "Status: Stopped"
        }

        btnShare.setOnClickListener {
            shareCsv()
        }
    }

    // 🔥 Proper Google Play Services location settings check
    private fun checkLocationSettingsAndStart() {

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        ).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            startLoggerService()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest =
                        IntentSenderRequest.Builder(exception.resolution).build()

                    locationResolutionLauncher.launch(intentSenderRequest)

                } catch (sendEx: IntentSender.SendIntentException) {
                    sendEx.printStackTrace()
                }
            } else {
                Toast.makeText(this, "Location settings unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startLoggerService() {
        val intent = Intent(this, CellLoggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        txtStatus.text = "Status: Running"
    }

    private fun shareCsv() {
        val file = File(filesDir, "survival_logs.csv")
        if (!file.exists()) {
            Toast.makeText(this, "No logs found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share Logs"))
    }
}
