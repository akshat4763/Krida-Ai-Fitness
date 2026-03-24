package com.google.mediapipe.examples.poselandmarker.sprint

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.ActivitySprintBinding

class SprintActivity : AppCompatActivity(), SprintTracker.SprintTrackerListener {

    private lateinit var binding: ActivitySprintBinding
    private lateinit var sprintTracker: SprintTracker

    private val timerHandler  = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var startTimeMs   = 0L
    private var isTracking    = false
    private var lastSummary: SprintSummary? = null

    private val speedEntries = mutableListOf<Entry>()
    private val accelEntries = mutableListOf<Entry>()

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.all { it.value }) {
                startSprintWithPermission()
            } else {
                Toast.makeText(this, "Location permission required for sprint tracking", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySprintBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sprintTracker = SprintTracker(this, this)

        setupChart()
        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnStartStop.setOnClickListener {
            if (isTracking) stopSprint() else checkPermissionsAndStart()
        }

        binding.btnReset.setOnClickListener {
            if (!isTracking) resetUI()
        }

        binding.switchAutoDetect.setOnCheckedChangeListener { _, checked ->
            sprintTracker.isAutoDetect = checked
            binding.tvAutoDetectLabel.text = if (checked) "Auto-start: ON" else "Auto-start: OFF"
        }

        showReadyState()
    }

    private fun setupChart() {
        // Concrete ValueFormatter subclass — fixes "cannot instantiate abstract class"
        val secondsFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                "%.1fs".format(value)
        }

        binding.speedChart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled         = false
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            legend.textColor      = Color.WHITE
            legend.textSize       = 11f

            xAxis.apply {
                position          = XAxis.XAxisPosition.BOTTOM
                textColor         = Color.parseColor("#888888")
                setDrawGridLines(false)
                granularity       = 1f
                valueFormatter    = secondsFormatter   // ✔ concrete subclass
            }
            axisLeft.apply {
                textColor         = Color.parseColor("#888888")
                setDrawGridLines(true)
                gridColor         = Color.parseColor("#222222")
                axisMinimum       = 0f
            }
            axisRight.isEnabled   = false
            setNoDataText("Tap START to begin sprint tracking")
            setNoDataTextColor(Color.parseColor("#888888"))
        }
    }

    // ── Permission handling ───────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startSprintWithPermission()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Only called after permissions are confirmed granted
    private fun startSprintWithPermission() {
        isTracking  = true
        startTimeMs = System.currentTimeMillis()
        speedEntries.clear()
        accelEntries.clear()
        binding.speedChart.clear()
        lastSummary = null

        try {
            sprintTracker.startTracking()   // internally annotated @RequiresPermission — wrapped here
        } catch (e: SecurityException) {
            isTracking = false
            Toast.makeText(this, "Location permission denied: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        startTimer()
        showTrackingState()
        Toast.makeText(this, "Sprint tracking started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopSprint() {
        isTracking = false
        stopTimer()
        val summary = sprintTracker.stopTracking()
        lastSummary = summary
        showResultState(summary)
        val path = SprintSessionManager.save(this, summary)
        if (path != null) Toast.makeText(this, "Session saved!", Toast.LENGTH_SHORT).show()
    }

    private fun resetUI() {
        speedEntries.clear()
        accelEntries.clear()
        binding.speedChart.clear()
        binding.speedChart.invalidate()
        showReadyState()
    }

    // ── SprintTrackerListener ─────────────────────────────────────────────────

    override fun onSprintDataPoint(point: SprintDataPoint) {
        runOnUiThread {
            val elapsedSec = point.elapsedMs / 1000f

            binding.tvLiveSpeed.text    = "%.1f".format(point.smoothedSpeedMs * 3.6f)
            binding.tvLiveAccel.text    = "%.2f".format(point.instantAccelMs2)
            binding.tvLiveDistance.text = "%.1f m".format(point.distanceM)

            speedEntries.add(Entry(elapsedSec, point.smoothedSpeedMs * 3.6f))
            accelEntries.add(Entry(elapsedSec, point.accelMagnitude))
            updateChart()

            val maxExpectedKmh = 36f
            val progress = ((point.smoothedSpeedMs * 3.6f / maxExpectedKmh) * 100).toInt().coerceIn(0, 100)
            binding.progressSpeed.progress = progress
        }
    }

    override fun onAutoStartDetected() {
        runOnUiThread {
            if (!isTracking) {
                Toast.makeText(this, "Sprint detected! Auto-starting…", Toast.LENGTH_SHORT).show()
                checkPermissionsAndStart()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    // ── Chart update ──────────────────────────────────────────────────────────

    private fun updateChart() {
        val speedSet = LineDataSet(speedEntries.toList(), "Speed (km/h)").apply {
            color             = Color.parseColor("#00E5FF")
            setDrawCircles(false)
            lineWidth         = 2.5f
            mode              = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
            fillAlpha         = 40
            fillColor         = Color.parseColor("#00E5FF")
            setDrawFilled(true)
        }
        val accelSet = LineDataSet(accelEntries.toList(), "Accel (m/s²)").apply {
            color             = Color.parseColor("#FF9800")
            setDrawCircles(false)
            lineWidth         = 1.5f
            mode              = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
        }
        binding.speedChart.data = LineData(speedSet, accelSet)
        binding.speedChart.notifyDataSetChanged()
        binding.speedChart.invalidate()
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                val s = (System.currentTimeMillis() - startTimeMs) / 1000
                binding.tvTimer.text = "%d:%02d".format(s / 60, s % 60)
                timerHandler.postDelayed(this, 100)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    // ── UI States ─────────────────────────────────────────────────────────────

    private fun showReadyState() {
        binding.tvStatus.text          = "READY"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.btnStartStop.text      = "▶  START"
        binding.btnStartStop.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.mp_color_primary)
        binding.btnReset.visibility    = View.GONE
        binding.cardResults.visibility = View.GONE
        binding.tvTimer.text           = "0:00"
        binding.tvLiveSpeed.text       = "0.0"
        binding.tvLiveAccel.text       = "0.00"
        binding.tvLiveDistance.text    = "0.0 m"
        binding.progressSpeed.progress = 0
    }

    private fun showTrackingState() {
        binding.tvStatus.text          = "● TRACKING"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.color_error))
        binding.btnStartStop.text      = "■  STOP"
        binding.btnStartStop.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.btnReset.visibility    = View.GONE
        binding.cardResults.visibility = View.GONE
    }

    private fun showResultState(summary: SprintSummary) {
        binding.tvStatus.text = "COMPLETE"
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.color_good))
        binding.btnStartStop.text = "▶  NEW SPRINT"
        binding.btnStartStop.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.mp_color_primary)
        binding.btnReset.visibility    = View.VISIBLE
        binding.cardResults.visibility = View.VISIBLE

        binding.tvResultDistance.text      = summary.totalDistanceFormatted
        binding.tvResultMaxSpeed.text      = "%.1f km/h".format(summary.maxSpeedKmh)
        binding.tvResultAvgSpeed.text      = "%.1f km/h".format(summary.avgSpeedKmh)
        binding.tvResultMaxAccel.text      = "%.2f m/s²".format(summary.maxAccelMs2)
        binding.tvResultExplosiveness.text = "%.1f m/s".format(summary.peakExplosiveness)
        binding.tvResultDuration.text      = summary.durationFormatted
        binding.tvResultTimeToPeak.text    = "%.2f s".format(summary.timeToPeakSpeedMs / 1000f)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        if (isTracking) sprintTracker.stopTracking()
    }
}