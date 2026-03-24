package com.google.mediapipe.examples.poselandmarker.sprint

/**
 * A single time-stamped sample captured during a sprint session.
 *
 * @param timestampMs     Wall-clock time in milliseconds (System.currentTimeMillis)
 * @param elapsedMs       Milliseconds since sprint started
 * @param latitude        GPS latitude (0.0 if not yet acquired)
 * @param longitude       GPS longitude (0.0 if not yet acquired)
 * @param rawSpeedMs      Raw speed from Location.getSpeed() in m/s (0 if unavailable)
 * @param smoothedSpeedMs Kalman/moving-average smoothed speed in m/s
 * @param instantAccelMs2 Δspeed / Δtime  in m/s²
 * @param distanceM       Cumulative distance from start in metres
 * @param accelX          Accelerometer X axis (m/s²) – world frame after gravity removal
 * @param accelY          Accelerometer Y axis
 * @param accelZ          Accelerometer Z axis
 * @param accelMagnitude  √(x²+y²+z²) of linear acceleration (gravity removed)
 */
data class SprintDataPoint(
    val timestampMs: Long,
    val elapsedMs: Long,
    val latitude: Double,
    val longitude: Double,
    val rawSpeedMs: Float,
    val smoothedSpeedMs: Float,
    val instantAccelMs2: Float,
    val distanceM: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val accelMagnitude: Float
)

/**
 * Summary statistics computed from the full session data points list.
 */
data class SprintSummary(
    val totalDistanceM: Float,
    val maxSpeedMs: Float,
    val avgSpeedMs: Float,
    val maxAccelMs2: Float,
    val peakExplosiveness: Float,   // max Δspeed in first 3 seconds (start explosiveness)
    val timeToPeakSpeedMs: Long,    // elapsed ms when max speed was first reached
    val durationMs: Long,
    val dataPoints: List<SprintDataPoint>
) {
    val maxSpeedKmh: Float get() = maxSpeedMs * 3.6f
    val avgSpeedKmh: Float get() = avgSpeedMs * 3.6f
    val totalDistanceFormatted: String get() = if (totalDistanceM >= 1000f)
        "%.2f km".format(totalDistanceM / 1000f) else "%.1f m".format(totalDistanceM)
    val durationFormatted: String get() {
        val s = durationMs / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}