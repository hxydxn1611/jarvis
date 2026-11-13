package com.assistant.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class WorkoutExercise(
    val name: String,
    val targetMuscles: String,
    val sets: Int,
    val reps: String,
    val formTip: String,
    val jarvisTip: String
)

data class WorkoutProgram(
    val id: String,
    val name: String,
    val icon: String,
    val exercises: List<WorkoutExercise>
)

object WorkoutManager {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var activeTimerJob: Job? = null

    val programs: Map<String, WorkoutProgram> = mapOf(
        "CHEST" to WorkoutProgram(
            id = "CHEST",
            name = "Iron Chest Hypertrophy",
            icon = "💪",
            exercises = listOf(
                WorkoutExercise(
                    name = "Standard Push-ups",
                    targetMuscles = "Pectoralis Major, Anterior Deltoids, Triceps",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Keep core braced and elbows at 45 degrees. Lower chest to floor and explode upward.",
                    jarvisTip = "Controlled descent, Sir. Momentum is the enemy of muscular recruitment."
                ),
                WorkoutExercise(
                    name = "Diamond Push-ups",
                    targetMuscles = "Inner Chest & Triceps Brachii",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Place thumbs and index fingers together under chest. Lock out at the peak.",
                    jarvisTip = "Peak tricep engagement. Maintain neutral cervical spine."
                ),
                WorkoutExercise(
                    name = "Wide Grip Push-ups",
                    targetMuscles = "Outer Sternal Pectoralis",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Hands placed wider than shoulders. Focus on deep stretch at the bottom.",
                    jarvisTip = "Feel the myofibrillar stretch across your pectorals, Sir."
                ),
                WorkoutExercise(
                    name = "Chair / Bench Dips",
                    targetMuscles = "Lower Pectorals & Tricep Long Head",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Keep back close to bench. Lower until elbows form 90 degrees.",
                    jarvisTip = "Do not allow shoulders to roll forward, Sir. Protect the rotator cuffs."
                )
            )
        ),
        "ARMS" to WorkoutProgram(
            id = "ARMS",
            name = "Vibranium Arms Routine",
            icon = "🦾",
            exercises = listOf(
                WorkoutExercise(
                    name = "Bicep Towel / Dumbbell Curls",
                    targetMuscles = "Biceps Brachii Short & Long Heads",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Pin elbows to torso. Squeeze bicep at peak contraction for 1 second.",
                    jarvisTip = "Full supination at the peak, Sir. Do not swing your lower back."
                ),
                WorkoutExercise(
                    name = "Hammer Curls",
                    targetMuscles = "Brachialis & Brachioradialis (Forearm Width)",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Neutral grip with palms facing each other. Controlled cadence.",
                    jarvisTip = "The brachialis will elevate your biceps peak substantially, Sir."
                ),
                WorkoutExercise(
                    name = "Overhead Tricep Extension",
                    targetMuscles = "Tricep Long Head",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Lower resistance behind head keeping upper arms vertical.",
                    jarvisTip = "The long head accounts for two-thirds of your upper arm mass, Sir."
                ),
                WorkoutExercise(
                    name = "Close-Grip Push-ups",
                    targetMuscles = "Tricep Lateral & Medial Heads",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Hands placed shoulder-width apart, elbows grazing your ribs.",
                    jarvisTip = "Unrelenting tension, Sir. Finish the set strong."
                )
            )
        ),
        "BACK" to WorkoutProgram(
            id = "BACK",
            name = "Titan Back Protocol",
            icon = "🏋️",
            exercises = listOf(
                WorkoutExercise(
                    name = "Doorframe / Pull-ups",
                    targetMuscles = "Latissimus Dorsi & Teres Major",
                    sets = 3,
                    reps = "10 Reps",
                    formTip = "Initiate movement by depressing shoulder blades. Pull chest to bar.",
                    jarvisTip = "Think of driving elbows downward into your back pockets, Sir."
                ),
                WorkoutExercise(
                    name = "Inverted / Dumbbell Rows",
                    targetMuscles = "Rhomboids, Middle Trapezius & Lats",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Pull elbows back pinching shoulder blades together tightly.",
                    jarvisTip = "Postural correction protocol active. Excellent upper back squeeze."
                ),
                WorkoutExercise(
                    name = "Supermans / Back Hyper-extension",
                    targetMuscles = "Erector Spinae & Posterior Kinetic Chain",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Lie prone on stomach. Raise chest, arms, and thighs simultaneously.",
                    jarvisTip = "Crucial spinal support for suit mobility and desk posture, Sir."
                )
            )
        ),
        "LEGS" to WorkoutProgram(
            id = "LEGS",
            name = "Arc Kinetic Legs",
            icon = "🦵",
            exercises = listOf(
                WorkoutExercise(
                    name = "Deep Bodyweight Squats",
                    targetMuscles = "Quadriceps & Gluteus Maximus",
                    sets = 4,
                    reps = "20 Reps",
                    formTip = "Hips back, knees tracking over toes. Break parallel and drive through heels.",
                    jarvisTip = "Deep hip flexion recruits maximum motor units, Sir."
                ),
                WorkoutExercise(
                    name = "Forward & Reverse Lunges",
                    targetMuscles = "Hamstrings, Quads & Gluteal Stabilizers",
                    sets = 3,
                    reps = "12 Reps per leg",
                    formTip = "Step forward into 90-degree angles. Torso upright.",
                    jarvisTip = "Unilateral strength eliminates kinetic imbalances, Sir."
                ),
                WorkoutExercise(
                    name = "Bulgarian Split Squats",
                    targetMuscles = "Glutes & Vastus Lateralis",
                    sets = 3,
                    reps = "10 Reps per leg",
                    formTip = "Rear foot elevated on bench/chair. Lower rear knee toward floor.",
                    jarvisTip = "A test of fortitude, Sir. The burn indicates systemic conditioning."
                ),
                WorkoutExercise(
                    name = "Standing Calf Raises",
                    targetMuscles = "Gastrocnemius & Soleus",
                    sets = 4,
                    reps = "25 Reps",
                    formTip = "Rise onto balls of feet, hold 1 second at top, stretch at bottom.",
                    jarvisTip = "Spring elasticity for high-velocity mobility, Sir."
                )
            )
        ),
        "CORE" to WorkoutProgram(
            id = "CORE",
            name = "Core Reactor Hardening",
            icon = "⚡",
            exercises = listOf(
                WorkoutExercise(
                    name = "High Plank Hold",
                    targetMuscles = "Transverse Abdominis & Deep Core",
                    sets = 3,
                    reps = "45 Seconds",
                    formTip = "Push floor away, tuck pelvis, squeeze glutes and abs simultaneously.",
                    jarvisTip = "Anti-extension stability. Do not allow your lumbar spine to sag, Sir."
                ),
                WorkoutExercise(
                    name = "Bicycle Crunches",
                    targetMuscles = "Rectus Abdominis & Internal/External Obliques",
                    sets = 3,
                    reps = "20 Reps",
                    formTip = "Slow rotation bringing elbow to opposite knee without yanking neck.",
                    jarvisTip = "Rotation under control. Time under tension is paramount."
                ),
                WorkoutExercise(
                    name = "Leg Raises",
                    targetMuscles = "Lower Rectus Abdominis",
                    sets = 3,
                    reps = "15 Reps",
                    formTip = "Lower legs slowly without lower back lifting off the floor.",
                    jarvisTip = "Control the eccentric descent, Sir. That is where abdominal strength is forged."
                ),
                WorkoutExercise(
                    name = "Mountain Climbers",
                    targetMuscles = "Core Dynamics & Metabolic Conditioning",
                    sets = 3,
                    reps = "30 Seconds",
                    formTip = "Drive knees toward chest in rhythmic cadence keeping hips level.",
                    jarvisTip = "Core engaged and heart rate climbing. Outstanding rhythm."
                )
            )
        ),
        "HIIT" to WorkoutProgram(
            id = "HIIT",
            name = "Mark VII Metabolic Burn",
            icon = "🔥",
            exercises = listOf(
                WorkoutExercise(
                    name = "Burpees",
                    targetMuscles = "Full Body Cardiovascular & Muscular Endurance",
                    sets = 3,
                    reps = "12 Reps",
                    formTip = "Drop to floor chest down, jump feet up, and explode into vertical jump.",
                    jarvisTip = "Maximum oxygen consumption protocol engaged, Sir."
                ),
                WorkoutExercise(
                    name = "Jumping Jacks",
                    targetMuscles = "Calves, Deltoids & Cardio System",
                    sets = 3,
                    reps = "45 Seconds",
                    formTip = "Land softly on balls of feet with rhythmic arm sweep.",
                    jarvisTip = "Maintain steady nasal breathing, Sir."
                ),
                WorkoutExercise(
                    name = "High Knees Sprint",
                    targetMuscles = "Hip Flexors, Quads & Heart Rate",
                    sets = 3,
                    reps = "30 Seconds",
                    formTip = "Drive knees above waist level with rapid arm pump.",
                    jarvisTip = "Anaerobic threshold reached, Sir. Push for the final seconds."
                ),
                WorkoutExercise(
                    name = "Shadow Boxing",
                    targetMuscles = "Shoulders, Core Rotation & Agility",
                    sets = 3,
                    reps = "60 Seconds",
                    formTip = "Jab, cross, hook combinations with light footwork.",
                    jarvisTip = "Fluidity and power, Sir. Excellent hand speed."
                )
            )
        )
    )

    fun getProgram(key: String): WorkoutProgram {
        val upper = key.uppercase().trim()
        return programs[upper]
            ?: programs.entries.firstOrNull { it.key.contains(upper) || upper.contains(it.key) }?.value
            ?: programs["CHEST"]!!
    }

    fun startTimer(
        durationSeconds: Int,
        onTick: (Int, String) -> Unit,
        onComplete: () -> Unit
    ) {
        activeTimerJob?.cancel()
        activeTimerJob = scope.launch {
            for (remaining in durationSeconds downTo 0) {
                val mins = remaining / 60
                val secs = remaining % 60
                val formatted = String.format("%02d:%02d", mins, secs)
                onTick(remaining, formatted)
                if (remaining > 0) {
                    delay(1000)
                }
            }
            onComplete()
        }
    }

    fun stopTimer() {
        activeTimerJob?.cancel()
        activeTimerJob = null
    }

    val motivationalQuotes = listOf(
        "Remember Sir, the suit cannot compensate for physical atrophy.",
        "Momentum is the enemy of muscular recruitment. Control the cadence.",
        "Lactic threshold achieved, Sir. That is where growth occurs.",
        "Postural discipline builds tactical readiness.",
        "Remarkable fortitude, Sir. Only a few repetitions remaining.",
        "Hydrate and recalibrate. We move to the next set shortly."
    )

    fun getRandomQuote(): String = motivationalQuotes.random()
}
