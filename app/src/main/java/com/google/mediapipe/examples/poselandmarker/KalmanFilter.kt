package com.google.mediapipe.examples.poselandmarker.sprint

/**
 * Lightweight 1-D Kalman filter for smoothing noisy GPS speed values.
 *
 * Model:
 *   state      x  = speed (m/s)
 *   process noise Q = how much we expect speed to change per step
 *   measurement noise R = how noisy the GPS speed reading is
 *   error covariance  P = uncertainty in our current estimate
 *
 * Usage:
 *   val kf = KalmanFilter(processNoise = 0.5f, measurementNoise = 2.0f)
 *   val smoothed = kf.update(rawGpsSpeed)
 */
class KalmanFilter(
    private val processNoise: Float = 0.5f,       // Q – tune higher for faster response
    private val measurementNoise: Float = 2.0f     // R – tune higher for more smoothing
) {
    private var estimate = 0f        // x̂
    private var errorCovariance = 1f // P

    fun reset() {
        estimate = 0f
        errorCovariance = 1f
    }

    /**
     * Feed in a new raw measurement and get back the smoothed estimate.
     */
    fun update(measurement: Float): Float {
        // Predict
        errorCovariance += processNoise

        // Kalman gain
        val gain = errorCovariance / (errorCovariance + measurementNoise)

        // Update estimate
        estimate += gain * (measurement - estimate)

        // Update error covariance
        errorCovariance *= (1f - gain)

        return estimate
    }

    fun getEstimate(): Float = estimate
}

/**
 * Simple moving-average smoother as an alternative / secondary smoother
 * for accelerometer magnitude.
 */
class MovingAverage(private val windowSize: Int = 5) {
    private val buffer = ArrayDeque<Float>(windowSize)

    fun reset() = buffer.clear()

    fun update(value: Float): Float {
        if (buffer.size >= windowSize) buffer.removeFirst()
        buffer.addLast(value)
        return buffer.average().toFloat()
    }
}