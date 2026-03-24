package com.google.mediapipe.examples.poselandmarker.sprint

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * SprintTracker is the core engine that:
 *  1. Requests high-frequency fused GPS at ~200 ms intervals
 *  2. Reads accelerometer + gyroscope via SensorManager
 *  3. Applies a Kalman filter to smooth GPS speed
 *  4. Computes instant speed, acceleration, cumulative distance
 *  5. Auto-detects sprint start via accelerometer threshold
 *  6. Emits SprintDataPoint samples via a callback
 */
class SprintTracker(
    private val context: Context,
    private val listener: SprintTrackerListener
) : SensorEventListener {

    interface SprintTrackerListener {
        fun onSprintDataPoint(point: SprintDataPoint)
        fun onAutoStartDetected()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SprintTracker"
        private const val LOCATION_INTERVAL_MS = 200L      // 200ms = ~5 Hz GPS
        private const val LOCATION_FASTEST_MS  = 100L
        private const val MIN_DISPLACEMENT_M   = 0.3f      // ignore moves < 0.3 m (drift filter)
        private const val AUTO_START_THRESHOLD = 3.5f      // m/s² linear accel to auto-start
        private const val SPEED_NOISE_THRESHOLD = 0.15f    // m/s — ignore sub-threshold speeds
        private const val EARLY_PHASE_MS        = 3000L    // first 3 s = explosiveness window
    }

    // ── Location ─────────────────────────────────────────────────────────────
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_INTERVAL_MS
    )
        .setMinUpdateIntervalMillis(LOCATION_FASTEST_MS)
        .setWaitForAccurateLocation(false)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onNewLocation(it) }
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────────
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // ── Filters ───────────────────────────────────────────────────────────────
    private val speedKalman   = KalmanFilter(processNoise = 0.8f, measurementNoise = 1.5f)
    private val accelSmoother = MovingAverage(windowSize = 7)

    // ── State ─────────────────────────────────────────────────────────────────
    private val dataPoints = mutableListOf<SprintDataPoint>()

    var isTracking   = false
        private set
    var isAutoDetect = true   // if true, sprint starts automatically on accel spike

    private var startTimeMs     = 0L
    private var lastLocation: Location? = null
    private var totalDistanceM  = 0f
    private var lastSpeedMs     = 0f
    private var lastSpeedTimeMs = 0L

    // Latest sensor values (updated on sensor thread)
    @Volatile private var linAccelX = 0f
    @Volatile private var linAccelY = 0f
    @Volatile private var linAccelZ = 0f
    @Volatile private var linAccelMag = 0f
    @Volatile private var smoothedAccelMag = 0f

    // Explosiveness tracking
    private var maxSpeedInEarlyPhase = 0f
    private var earlyPhaseFinished   = false

    // ── Public API ────────────────────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startTracking() {
        if (isTracking) return
        isTracking      = true
        startTimeMs     = System.currentTimeMillis()
        lastLocation    = null
        totalDistanceM  = 0f
        lastSpeedMs     = 0f
        lastSpeedTimeMs = startTimeMs
        maxSpeedInEarlyPhase = 0f
        earlyPhaseFinished   = false
        dataPoints.clear()
        speedKalman.reset()
        accelSmoother.reset()

        registerSensors()
        requestLocationUpdates()
        Log.d(TAG, "Sprint tracking started")
    }

    fun stopTracking(): SprintSummary {
        isTracking = false
        fusedClient.removeLocationUpdates(locationCallback)
        unregisterSensors()
        Log.d(TAG, "Sprint tracking stopped. Points: ${dataPoints.size}")
        return buildSummary()
    }

    fun getDataPoints(): List<SprintDataPoint> = dataPoints.toList()

    // ── Internal: Location ────────────────────────────────────────────────────

    @androidx.annotation.RequiresPermission(
        allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION]
    )
    private fun requestLocationUpdates() {
        try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            listener.onError("Location permission not granted: ${e.message}")
        }
    }

    private fun onNewLocation(location: Location) {
        if (!isTracking) return

        val nowMs     = System.currentTimeMillis()
        val elapsedMs = nowMs - startTimeMs

        // ── Distance ─────────────────────────────────────────────────────────
        var stepDistanceM = 0f
        lastLocation?.let { prev ->
            val results = FloatArray(1)
            Location.distanceBetween(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude,
                results
            )
            stepDistanceM = results[0]
            // Drift filter: ignore tiny movements
            if (stepDistanceM >= MIN_DISPLACEMENT_M) {
                totalDistanceM += stepDistanceM
            } else {
                stepDistanceM = 0f
            }
        }

        // ── Speed ─────────────────────────────────────────────────────────────
        // Prefer location.speed if available and valid, else derive from Δdistance/Δtime
        val rawSpeedMs: Float = if (location.hasSpeed() && location.speed > SPEED_NOISE_THRESHOLD) {
            location.speed
        } else if (stepDistanceM > 0f && lastLocation != null) {
            val dtSec = (nowMs - (lastSpeedTimeMs)).coerceAtLeast(1L) / 1000f
            stepDistanceM / dtSec
        } else {
            0f
        }

        val smoothedSpeedMs = speedKalman.update(rawSpeedMs)

        // ── Acceleration ──────────────────────────────────────────────────────
        val dtSec = ((nowMs - lastSpeedTimeMs).coerceAtLeast(1L)) / 1000f
        val instantAccelMs2 = (smoothedSpeedMs - lastSpeedMs) / dtSec

        // ── Explosiveness tracking ────────────────────────────────────────────
        if (!earlyPhaseFinished) {
            if (elapsedMs > EARLY_PHASE_MS) earlyPhaseFinished = true
            else if (smoothedSpeedMs > maxSpeedInEarlyPhase) maxSpeedInEarlyPhase = smoothedSpeedMs
        }

        // ── Build data point ──────────────────────────────────────────────────
        val point = SprintDataPoint(
            timestampMs      = nowMs,
            elapsedMs        = elapsedMs,
            latitude         = location.latitude,
            longitude        = location.longitude,
            rawSpeedMs       = rawSpeedMs,
            smoothedSpeedMs  = smoothedSpeedMs,
            instantAccelMs2  = instantAccelMs2,
            distanceM        = totalDistanceM,
            accelX           = linAccelX,
            accelY           = linAccelY,
            accelZ           = linAccelZ,
            accelMagnitude   = smoothedAccelMag
        )

        dataPoints.add(point)
        listener.onSprintDataPoint(point)

        lastLocation    = location
        lastSpeedMs     = smoothedSpeedMs
        lastSpeedTimeMs = nowMs
    }

    // ── Internal: Sensors ─────────────────────────────────────────────────────

    private fun registerSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linAccelX = event.values[0]
                linAccelY = event.values[1]
                linAccelZ = event.values[2]
                val mag = Math.sqrt(
                    (linAccelX * linAccelX + linAccelY * linAccelY + linAccelZ * linAccelZ).toDouble()
                ).toFloat()
                linAccelMag       = mag
                smoothedAccelMag  = accelSmoother.update(mag)

                // Auto-detect sprint start
                if (!isTracking && isAutoDetect && mag >= AUTO_START_THRESHOLD) {
                    listener.onAutoStartDetected()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    // ── Summary ───────────────────────────────────────────────────────────────

    private fun buildSummary(): SprintSummary {
        if (dataPoints.isEmpty()) {
            return SprintSummary(0f, 0f, 0f, 0f, 0f, 0L,
                System.currentTimeMillis() - startTimeMs, emptyList())
        }

        val maxSpeed    = dataPoints.maxOf { it.smoothedSpeedMs }
        val avgSpeed    = dataPoints.map { it.smoothedSpeedMs }.average().toFloat()
        val maxAccel    = dataPoints.maxOf { it.instantAccelMs2 }
        val peakExpl    = maxSpeedInEarlyPhase   // max speed in first 3 s = explosiveness
        val durationMs  = dataPoints.last().elapsedMs

        // Time to first peak speed
        val peakPoint   = dataPoints.maxByOrNull { it.smoothedSpeedMs }
        val timeToPeak  = peakPoint?.elapsedMs ?: 0L

        return SprintSummary(
            totalDistanceM   = totalDistanceM,
            maxSpeedMs       = maxSpeed,
            avgSpeedMs       = avgSpeed,
            maxAccelMs2      = maxAccel,
            peakExplosiveness = peakExpl,
            timeToPeakSpeedMs = timeToPeak,
            durationMs       = durationMs,
            dataPoints       = dataPoints.toList()
        )
    }
}