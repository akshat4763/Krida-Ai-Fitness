package com.google.mediapipe.examples.poselandmarker.sprint

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists sprint sessions as JSON files in the app's private files directory.
 * Each session is stored as: sprint_yyyyMMdd_HHmmss.json
 */
object SprintSessionManager {

    private const val TAG = "SprintSessionManager"
    private const val DIR_NAME = "sprint_sessions"

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Save a SprintSummary to disk. Returns the file path or null on failure. */
    fun save(context: Context, summary: SprintSummary): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(getDir(context), "sprint_$timestamp.json")

            val root = JSONObject().apply {
                put("timestamp",        summary.dataPoints.firstOrNull()?.timestampMs ?: 0L)
                put("durationMs",       summary.durationMs)
                put("totalDistanceM",   summary.totalDistanceM)
                put("maxSpeedMs",       summary.maxSpeedMs)
                put("avgSpeedMs",       summary.avgSpeedMs)
                put("maxAccelMs2",      summary.maxAccelMs2)
                put("peakExplosiveness", summary.peakExplosiveness)
                put("timeToPeakSpeedMs", summary.timeToPeakSpeedMs)

                val pts = JSONArray()
                summary.dataPoints.forEach { p ->
                    pts.put(JSONObject().apply {
                        put("elapsedMs",       p.elapsedMs)
                        put("lat",             p.latitude)
                        put("lng",             p.longitude)
                        put("rawSpeed",        p.rawSpeedMs)
                        put("smoothedSpeed",   p.smoothedSpeedMs)
                        put("accel",           p.instantAccelMs2)
                        put("distance",        p.distanceM)
                        put("accelMag",        p.accelMagnitude)
                    })
                }
                put("dataPoints", pts)
            }

            file.writeText(root.toString(2))
            Log.d(TAG, "Session saved: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            null
        }
    }

    /** List all saved session files, newest first. */
    fun listSessions(context: Context): List<File> {
        return getDir(context)
            .listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Load a SprintSummary from a saved JSON file. */
    fun load(file: File): SprintSummary? {
        return try {
            val root = JSONObject(file.readText())
            val ptsArray = root.getJSONArray("dataPoints")
            val points = (0 until ptsArray.length()).map { i ->
                val o = ptsArray.getJSONObject(i)
                SprintDataPoint(
                    timestampMs      = 0L,
                    elapsedMs        = o.getLong("elapsedMs"),
                    latitude         = o.getDouble("lat"),
                    longitude        = o.getDouble("lng"),
                    rawSpeedMs       = o.getDouble("rawSpeed").toFloat(),
                    smoothedSpeedMs  = o.getDouble("smoothedSpeed").toFloat(),
                    instantAccelMs2  = o.getDouble("accel").toFloat(),
                    distanceM        = o.getDouble("distance").toFloat(),
                    accelX = 0f, accelY = 0f, accelZ = 0f,
                    accelMagnitude   = o.getDouble("accelMag").toFloat()
                )
            }

            SprintSummary(
                totalDistanceM    = root.getDouble("totalDistanceM").toFloat(),
                maxSpeedMs        = root.getDouble("maxSpeedMs").toFloat(),
                avgSpeedMs        = root.getDouble("avgSpeedMs").toFloat(),
                maxAccelMs2       = root.getDouble("maxAccelMs2").toFloat(),
                peakExplosiveness = root.getDouble("peakExplosiveness").toFloat(),
                timeToPeakSpeedMs = root.getLong("timeToPeakSpeedMs"),
                durationMs        = root.getLong("durationMs"),
                dataPoints        = points
            )
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            null
        }
    }

    /** Delete a session file. */
    fun delete(file: File): Boolean = file.delete()

    /** Export summary as a CSV string for sharing. */
    fun exportCsv(summary: SprintSummary): String {
        val sb = StringBuilder()
        sb.appendLine("elapsedMs,smoothedSpeedMs,smoothedSpeedKmh,instantAccelMs2,distanceM,accelMagnitude")
        summary.dataPoints.forEach { p ->
            sb.appendLine("${p.elapsedMs},%.3f,%.2f,%.3f,%.2f,%.3f".format(
                p.smoothedSpeedMs,
                p.smoothedSpeedMs * 3.6f,
                p.instantAccelMs2,
                p.distanceM,
                p.accelMagnitude
            ))
        }
        return sb.toString()
    }
}