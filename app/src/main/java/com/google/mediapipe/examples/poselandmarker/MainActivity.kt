package com.google.mediapipe.examples.poselandmarker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityMainBinding
import com.google.mediapipe.examples.poselandmarker.sprint.SprintActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── 1. Speed & Acceleration → SprintActivity ──────────────────────
        binding.cardSpeed.setOnClickListener {
            startActivity(Intent(this, SprintActivity::class.java))
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
        }

        // ── 2. Strength & Endurance → ExerciseActivity ────────────────────
        binding.cardStrength.setOnClickListener {
            startActivity(
                Intent(this, ExerciseActivity::class.java)
                    .putExtra(ExerciseActivity.EXTRA_EXERCISE_TYPE, ExerciseActivity.EXERCISE_PUSHUP)
            )
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
        }

        // ── 3–8: Coming soon ───────────────────────────────────────────────
        val comingSoonCards: List<CardView> = listOf(
            binding.cardPower,
            binding.cardEndurance,
            binding.cardAgility,
            binding.cardMobility,
            binding.cardBalance,
            binding.cardReaction
        )
        comingSoonCards.forEach { card ->
            card.setOnClickListener {
                Toast.makeText(this, "Coming soon! Stay tuned.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}