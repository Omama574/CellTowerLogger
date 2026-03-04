package com.omama.celltowerlogger

import android.content.Context
import android.location.Location
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvLogger {

    fun logEvent(context: Context, event: String, location: Location?) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lat = location?.latitude ?: "N/A"
        val lon = location?.longitude ?: "N/A"
        val acc = location?.accuracy ?: "N/A"
        val logLine = "$timestamp,$event,$lat,$lon,$acc"

        writeToCsv(context, logLine)
    }

    private fun writeToCsv(context: Context, data: String) {
        val logFile = File(context.filesDir, "survival_log.csv")
        val isNewFile = !logFile.exists()
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                if (isNewFile) {
                    writer.write("Timestamp,Event_Type,Latitude,Longitude,Accuracy(m)")
                    writer.newLine()
                }
                writer.write(data)
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}