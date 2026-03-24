package com.google.mediapipe.examples.poselandmarker

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityExerciseBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ExerciseActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        const val EXTRA_EXERCISE_TYPE = "exercise_type"
        const val EXERCISE_PUSHUP = "pushup"
        const val EXERCISE_SQUAT = "squat"
        private const val TAG = "ExerciseActivity"

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityExerciseBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var exerciseType: String
    private lateinit var repCounter: RepCounter

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera and audio permissions are required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExerciseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exerciseType = intent.getStringExtra(EXTRA_EXERCISE_TYPE) ?: EXERCISE_PUSHUP
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        repCounter = RepCounter(exerciseType)

        setupUI()

        backgroundExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }

        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = this,
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = this
            )
        }
    }

    private fun setupUI() {
        val exerciseName = if (exerciseType == EXERCISE_PUSHUP) "PUSH-UPS" else "SQUATS"
        binding.tvExerciseTitle.text = exerciseName
        binding.tvRepCount.text = "0"
        binding.tvPhaseLabel.text = "GET READY"
        binding.tvFeedback.text = ""

        binding.btnBack.setOnClickListener {
            if (isRecording) stopRecording()
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }

        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        binding.btnReset.setOnClickListener {
            repCounter.reset()
            runOnUiThread {
                binding.tvRepCount.text = "0"
                binding.tvPhaseLabel.text = "GET READY"
                binding.tvFeedback.text = ""
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // Use front camera for exercise tracking
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(backgroundExecutor) { imageProxy ->
                    detectPose(imageProxy)
                }
            }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                videoCapture
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Camera failed to start: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if (::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = true
            )
        } else {
            imageProxy.close()
        }
    }

    private fun startRecording() {
        val videoCapture = videoCapture ?: return

        isRecording = true
        binding.btnRecord.text = "⏹  STOP"
        binding.btnRecord.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        binding.recordingIndicator.visibility = View.VISIBLE

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exerciseName = if (exerciseType == EXERCISE_PUSHUP) "pushup" else "squat"
        val fileName = "FitTracker_${exerciseName}_$timeStamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FitTracker")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val recording = videoCapture.output.prepareRecording(this, mediaStoreOutputOptions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            recording.withAudioEnabled()
        }
        activeRecording = recording
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Recording started: $fileName")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(
                                this,
                                "Workout saved to Movies/FitTracker",
                                Toast.LENGTH_LONG
                            ).show()
                            Log.d(TAG, "Recording saved: ${recordEvent.outputResults.outputUri}")
                        } else {
                            Log.e(TAG, "Recording error code: ${recordEvent.error}")
                            Toast.makeText(
                                this,
                                "Recording failed. Try again.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        activeRecording = null
                    }
                }
            }
    }

    private fun stopRecording() {
        isRecording = false
        binding.btnRecord.text = "⏺  REC"
        binding.btnRecord.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.mp_color_primary)
        binding.recordingIndicator.visibility = View.GONE
        activeRecording?.stop()
    }

    // Called by PoseLandmarkerHelper on every frame result (LIVE_STREAM mode)
    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        runOnUiThread {
            // Update the skeleton overlay
            binding.overlay.setResults(
                resultBundle.results.first(),
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM
            )

            val result = resultBundle.results.firstOrNull() ?: return@runOnUiThread

            if (result.landmarks().isNotEmpty()) {
                val update = repCounter.processLandmarks(result.landmarks()[0])

                binding.tvRepCount.text = update.repCount.toString()
                binding.tvPhaseLabel.text = update.phase
                binding.tvFeedback.text = update.feedback

                // Animate the rep count when a new rep is completed
                if (update.newRep) {
                    binding.tvRepCount.animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .setDuration(120)
                        .withEndAction {
                            binding.tvRepCount.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120)
                                .start()
                        }
                        .start()
                }

                // Color-code feedback text
                val feedbackColor = when {
                    update.feedback.contains("REP") -> R.color.color_good
                    update.feedback.contains("!") -> R.color.color_warning
                    else -> R.color.white
                }
                binding.tvFeedback.setTextColor(ContextCompat.getColor(this, feedbackColor))
            }

            binding.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Log.e(TAG, "PoseLandmarker error: $error (code $errorCode)")
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
        if (::poseLandmarkerHelper.isInitialized) {
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onResume() {
        super.onResume()
        backgroundExecutor.execute {
            if (::poseLandmarkerHelper.isInitialized && poseLandmarkerHelper.isClose()) {
                poseLandmarkerHelper.setupPoseLandmarker()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        backgroundExecutor.shutdown()
    }
}