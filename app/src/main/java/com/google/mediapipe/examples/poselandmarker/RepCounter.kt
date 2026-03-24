package com.google.mediapipe.examples.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

class RepCounter(private val exerciseType: String) {

    data class ExerciseUpdate(
        val repCount: Int,
        val phase: String,
        val feedback: String,
        val newRep: Boolean = false
    )

    private enum class Phase { UP, DOWN }

    private var currentPhase = Phase.UP
    private var repCount = 0
    private var smoothedAngle = 0.0
    private var isFirstFrame = true
    private val alpha = 0.35

    companion object {
        private const val LEFT_SHOULDER  = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_ELBOW     = 13
        private const val RIGHT_ELBOW    = 14
        private const val LEFT_WRIST     = 15
        private const val RIGHT_WRIST    = 16
        private const val LEFT_HIP       = 23
        private const val RIGHT_HIP      = 24
        private const val LEFT_KNEE      = 25
        private const val RIGHT_KNEE     = 26
        private const val LEFT_ANKLE     = 27
        private const val RIGHT_ANKLE    = 28

        private const val PUSHUP_DOWN_ANGLE = 90.0
        private const val PUSHUP_UP_ANGLE   = 155.0
        private const val SQUAT_DOWN_ANGLE  = 100.0
        private const val SQUAT_UP_ANGLE    = 160.0
    }

    fun reset() {
        currentPhase = Phase.UP
        repCount = 0
        smoothedAngle = 0.0
        isFirstFrame = true
    }

    fun processLandmarks(landmarks: List<NormalizedLandmark>): ExerciseUpdate {
        return when (exerciseType) {
            ExerciseActivity.EXERCISE_PUSHUP -> processPushup(landmarks)
            ExerciseActivity.EXERCISE_SQUAT  -> processSquat(landmarks)
            else -> ExerciseUpdate(repCount, "UNKNOWN", "Select an exercise")
        }
    }

    private fun processPushup(landmarks: List<NormalizedLandmark>): ExerciseUpdate {
        if (landmarks.size < 17) {
            return ExerciseUpdate(repCount, currentPhaseName(), "Step back so full body is visible")
        }

        val leftAngle = calculateAngle(
            landmarks[LEFT_SHOULDER], landmarks[LEFT_ELBOW], landmarks[LEFT_WRIST]
        )
        val rightAngle = calculateAngle(
            landmarks[RIGHT_SHOULDER], landmarks[RIGHT_ELBOW], landmarks[RIGHT_WRIST]
        )
        smoothedAngle = smooth((leftAngle + rightAngle) / 2.0)

        val bodyIsAligned = isBodyAligned(landmarks)
        val hipsAreLevel  = areHipsLevel(landmarks)

        val feedback = when {
            !bodyIsAligned                    -> "Straighten your body!"
            !hipsAreLevel                     -> "Keep hips level"
            smoothedAngle < 70.0              -> "Almost there - push up!"
            smoothedAngle < PUSHUP_DOWN_ANGLE -> "Good depth! Push up!"
            smoothedAngle > PUSHUP_UP_ANGLE   -> "Lower yourself down"
            currentPhase == Phase.DOWN        -> "Keep going down..."
            else                              -> "Great form!"
        }

        var newRep = false
        if (currentPhase == Phase.UP) {
            if (smoothedAngle <= PUSHUP_DOWN_ANGLE) {
                currentPhase = Phase.DOWN
            }
        } else if (currentPhase == Phase.DOWN) {
            if (smoothedAngle >= PUSHUP_UP_ANGLE) {
                currentPhase = Phase.UP
                repCount++
                newRep = true
            }
        }

        val finalFeedback = if (newRep) "REP $repCount! Keep going!" else feedback
        return ExerciseUpdate(repCount, currentPhaseName(), finalFeedback, newRep)
    }

    private fun processSquat(landmarks: List<NormalizedLandmark>): ExerciseUpdate {
        if (landmarks.size < 29) {
            return ExerciseUpdate(repCount, currentPhaseName(), "Step back so full body is visible")
        }

        val leftAngle = calculateAngle(
            landmarks[LEFT_HIP], landmarks[LEFT_KNEE], landmarks[LEFT_ANKLE]
        )
        val rightAngle = calculateAngle(
            landmarks[RIGHT_HIP], landmarks[RIGHT_KNEE], landmarks[RIGHT_ANKLE]
        )
        smoothedAngle = smooth((leftAngle + rightAngle) / 2.0)

        val kneesCaveIn = doKneesCaveIn(landmarks)

        val feedback = when {
            kneesCaveIn                      -> "Push knees out!"
            smoothedAngle < 85.0             -> "Great depth! Stand up!"
            smoothedAngle < SQUAT_DOWN_ANGLE -> "Good squat! Stand up!"
            smoothedAngle > SQUAT_UP_ANGLE   -> "Squat lower!"
            currentPhase == Phase.DOWN       -> "Keep squatting..."
            else                             -> "Good form!"
        }

        var newRep = false
        if (currentPhase == Phase.UP) {
            if (smoothedAngle <= SQUAT_DOWN_ANGLE) {
                currentPhase = Phase.DOWN
            }
        } else if (currentPhase == Phase.DOWN) {
            if (smoothedAngle >= SQUAT_UP_ANGLE) {
                currentPhase = Phase.UP
                repCount++
                newRep = true
            }
        }

        val finalFeedback = if (newRep) "REP $repCount! Keep going!" else feedback
        return ExerciseUpdate(repCount, currentPhaseName(), finalFeedback, newRep)
    }

    private fun calculateAngle(
        a: NormalizedLandmark,
        vertex: NormalizedLandmark,
        b: NormalizedLandmark
    ): Double {
        val ax = (a.x() - vertex.x()).toDouble()
        val ay = (a.y() - vertex.y()).toDouble()
        val bx = (b.x() - vertex.x()).toDouble()
        val by = (b.y() - vertex.y()).toDouble()

        val dot  = ax * bx + ay * by
        val magA = sqrt(ax * ax + ay * ay)
        val magB = sqrt(bx * bx + by * by)

        if (magA < 1e-6 || magB < 1e-6) return 0.0

        return Math.toDegrees(acos((dot / (magA * magB)).coerceIn(-1.0, 1.0)))
    }

    private fun smooth(raw: Double): Double {
        if (isFirstFrame) {
            isFirstFrame = false
            smoothedAngle = raw
        }
        return smoothedAngle * (1.0 - alpha) + raw * alpha
    }

    private fun isBodyAligned(landmarks: List<NormalizedLandmark>): Boolean {
        return calculateAngle(
            landmarks[LEFT_SHOULDER], landmarks[LEFT_HIP], landmarks[LEFT_ANKLE]
        ) > 150.0
    }

    private fun areHipsLevel(landmarks: List<NormalizedLandmark>): Boolean {
        return abs(landmarks[LEFT_HIP].y() - landmarks[RIGHT_HIP].y()) < 0.08f
    }

    private fun doKneesCaveIn(landmarks: List<NormalizedLandmark>): Boolean {
        val leftCaving  = (landmarks[LEFT_KNEE].x()  - landmarks[LEFT_ANKLE].x())  > 0.12f
        val rightCaving = (landmarks[RIGHT_ANKLE].x() - landmarks[RIGHT_KNEE].x()) > 0.12f
        return leftCaving || rightCaving
    }

    private fun currentPhaseName(): String {
        return if (currentPhase == Phase.UP) {
            if (exerciseType == ExerciseActivity.EXERCISE_PUSHUP) "UP ↑" else "STANDING ↑"
        } else {
            if (exerciseType == ExerciseActivity.EXERCISE_PUSHUP) "DOWN ↓" else "SQUAT ↓"
        }
    }
}