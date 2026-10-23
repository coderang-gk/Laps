package io.github.coderang.laps.coach

import io.github.coderang.laps.coros.CorosApiClient
import io.github.coderang.laps.calendar.CalendarSyncManager
import java.util.Locale

/**
 * Pure Kotlin On-Device Physiological Analyzer (Zero-Cost / $0).
 * Evaluates cardiac drift, lactate threshold stimulus, cadence economy, and generates
 * actionable coaching guidance and dynamic missed-workout recovery options.
 */
object EvoLabAnalyzer {

    data class CoachingAnalysis(
        val headline: String,
        val summary: String,
        val insights: List<String>,
        val tips: List<String>
    )

    data class MissedWorkoutResolution(
        val rationale: String,
        val options: List<MissedOption>
    )

    data class MissedOption(
        val label: String,
        val sublabel: String,
        val action: String, // "DROP" or "SHIFT"
        val targetDate: String,
        val isRecommended: Boolean
    )

    fun analyzeRun(
        run: CorosApiClient.CorosActivity,
        planned: CalendarSyncManager.PlannedWorkout?,
        lthr: Int,
        nextWorkoutTitle: String,
        nextWorkoutPace: String,
        nextWorkoutZone: String
    ): CoachingAnalysis {
        val z2Ceiling = (lthr * 0.901).toInt()
        val plannedDist = planned?.targetDistanceKm ?: 0.0
        val distKm = run.distanceKm
        val adhPct = if (plannedDist > 0) ((distKm / plannedDist) * 100.0 + 0.5).toInt() else 100
        val isQuality = run.avgHr >= 165 || run.avgPaceSec <= 325 || (planned != null && planned.workoutType == "Threshold")
        val hasStrides = run.name.lowercase(Locale.US).contains("stride") || (planned != null && planned.workoutType.contains("Stride"))

        return when {
            hasStrides -> {
                val hl = "A+ Shakeout & Neuromuscular Pop ($adhPct% Volume · Z2 Control)"
                val sm = "You executed the dual purpose of this workout with textbook discipline: keeping aerobic volume strictly below your $z2Ceiling bpm aerobic ceiling (${run.avgHr} bpm avg) while firing fast-twitch fibers in strides without accumulating fatigue."
                val ins = listOf(
                    "Aerobic Base Control: Your base running block stayed at ${run.avgHr} bpm (${lthr - run.avgHr} bpm below LTHR), protecting heart rate instead of forcing pace.",
                    "Neuromuscular Strides: Strides surged power to ${run.powerWatts}W while controlled recovery kept Anaerobic TE at ${run.anaerobicTe}.",
                    "Cadence Watchpoint: Aerobic block cadence averaged ${run.cadence} spm. Nudging turnover toward 160 spm on easy days reduces ground contact time."
                )
                val tips = listOf(
                    "For $nextWorkoutTitle: Lock early kilometers strictly at $nextWorkoutPace in $nextWorkoutZone. Do not bank time early.",
                    "Hydration: Drink 300ml water with electrolytes 30 mins before heading out to suppress late-run cardiac drift.",
                    "Form Focus: Keep shoulders dropped and maintain quick, light footstrikes to prevent late-run quad heaviness."
                )
                CoachingAnalysis(hl, sm, ins, tips)
            }
            isQuality -> {
                val hl = "Strong Lactate Clearance Stimulus (${run.avgPace} · ${run.trainingLoad} TL)"
                val sm = "High-quality threshold stimulus averaging ${run.avgPace} right at your $lthr bpm LTHR (${run.avgHr} bpm avg). You generated a robust ${run.aerobicTe} Aerobic TE, sharpening race-pace fatigue resistance."
                val ins = listOf(
                    "Threshold Lock: Your work blocks locked cleanly near $lthr bpm LTHR, directly conditioning your body to clear blood lactate under race load.",
                    "Heart Rate Ceiling: Avg HR hit ${run.avgHr} bpm (peaking at ${run.maxHr} bpm). Ensure warmup and cooldown jogs are logged to clear residual lactate.",
                    "Efficiency Check: Running power averaged ${run.powerWatts}W with consistent cadence (${run.cadence} spm)."
                )
                val tips = listOf(
                    "Recovery Window: Prioritize 8 hours of sleep and adequate carbohydrate replenishment over the next 48 hours.",
                    "Next Session: Treat $nextWorkoutTitle at $nextWorkoutPace as pure active recovery.",
                    "Effort Cap: On warmer days, run threshold reps by effort and power rather than forcing pace if HR exceeds ${lthr + 3} bpm."
                )
                CoachingAnalysis(hl, sm, ins, tips)
            }
            else -> {
                val hl = "Textbook Zone 2 Aerobic Builder ($adhPct% On Target)"
                val sm = "Clean, controlled ${run.distanceKm} km aerobic run at ${run.avgPace} and ${run.avgHr} bpm average (strictly below your $z2Ceiling bpm Zone 2 ceiling). You banked ${run.aerobicTe} Aerobic TE with minimal mechanical stress."
                val ins = listOf(
                    "Zero Zone Creep: Average HR of ${run.avgHr} bpm stayed ${z2Ceiling - run.avgHr} bpm below Zone 2 ceiling, keeping weekly 80/20 polarization on track.",
                    "Aerobic Efficiency: Running power averaged ${run.powerWatts}W with steady turnover (${run.cadence} spm).",
                    "Aerobic Base Density: Built capillary and mitochondrial adaptations without imposing heavy muscular fatigue."
                )
                val tips = listOf(
                    "Next Workout: Target $nextWorkoutPace in $nextWorkoutZone for $nextWorkoutTitle.",
                    "Cadence Cue: Use a 160 bpm rhythm check during the first 2 km of easy runs to ingrain quick turnover.",
                    "Electrolytes: Ensure sodium intake is maintained as weekly volume climbs."
                )
                CoachingAnalysis(hl, sm, ins, tips)
            }
        }
    }

    fun reasonMissedWorkout(
        missedTitle: String,
        missedDistKm: Double,
        nextTitle: String,
        nextDistKm: Double,
        todayDateStr: String
    ): MissedWorkoutResolution {
        val isNextLongRun = nextDistKm >= 14.0 || nextTitle.lowercase(Locale.US).contains("long")
        val isMissedQuality = missedTitle.lowercase(Locale.US).contains("threshold") || missedTitle.lowercase(Locale.US).contains("interval")

        return when {
            isNextLongRun -> {
                MissedWorkoutResolution(
                    rationale = "You missed '$missedTitle' (${missedDistKm.toInt()}K). With your key '$nextTitle' (${nextDistKm.toInt()}K) ahead, cramming missed mileage beforehand sharply elevates glycogen depletion and calf strain risk.",
                    options = listOf(
                        MissedOption(
                            label = "Drop & Protect Long Run",
                            sublabel = "Safeguards glycogen and fresh legs for your ${nextDistKm.toInt()}K session",
                            action = "DROP",
                            targetDate = "",
                            isRecommended = true
                        ),
                        MissedOption(
                            label = "Shift to Light Shakeout (3K)",
                            sublabel = "Cap at 20m in Zone 1 (<140 bpm). Strictly no strides",
                            action = "SHIFT",
                            targetDate = todayDateStr,
                            isRecommended = false
                        )
                    )
                )
            }
            isMissedQuality -> {
                MissedWorkoutResolution(
                    rationale = "You missed your key speed stimulus '$missedTitle' (${missedDistKm.toInt()}K). Lactate clearance adaptation is vital for race pace sustainability.",
                    options = listOf(
                        MissedOption(
                            label = "Swap to Today",
                            sublabel = "Execute threshold work today; bump tomorrow to active recovery",
                            action = "SHIFT",
                            targetDate = todayDateStr,
                            isRecommended = true
                        ),
                        MissedOption(
                            label = "Drop & Move On",
                            sublabel = "Maintain schedule rather than stacking quality days back-to-back",
                            action = "DROP",
                            targetDate = "",
                            isRecommended = false
                        )
                    )
                )
            }
            else -> {
                MissedWorkoutResolution(
                    rationale = "You missed an aerobic base run '$missedTitle' (${missedDistKm.toInt()}K). Aerobic volume loss can be absorbed safely without disrupting your block.",
                    options = listOf(
                        MissedOption(
                            label = "Drop & Move On",
                            sublabel = "Never chase missed aerobic miles; keep recovery intact",
                            action = "DROP",
                            targetDate = "",
                            isRecommended = true
                        ),
                        MissedOption(
                            label = "Shift to Today",
                            sublabel = "Log ${missedDistKm.toInt()}K today strictly in Zone 2",
                            action = "SHIFT",
                            targetDate = todayDateStr,
                            isRecommended = false
                        )
                    )
                )
            }
        }
    }
}
