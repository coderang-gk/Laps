package io.github.coderang.laps.coach

import io.github.coderang.laps.coros.CorosApiClient
import org.junit.Assert.*
import org.junit.Test

class EvoLabAnalyzerTest {

    @Test
    fun reasonMissedWorkout_protectsUpcomingLongRun_recommendsDrop() {
        val res = EvoLabAnalyzer.reasonMissedWorkout(
            missedTitle = "W3 Fri · Easy 7K + Strides",
            missedDistKm = 7.0,
            nextTitle = "W3 Sun · Long Run 20K",
            nextDistKm = 20.0,
            todayDateStr = "2026-10-24"
        )

        // Must recommend DROP to protect glycogen and fresh legs for Sunday's 20K
        val recommended = res.options.first { it.isRecommended }
        assertEquals("DROP", recommended.action)
        assertTrue(recommended.label.contains("Protect Long Run"))
        assertTrue(res.rationale.contains("glycogen depletion"))
    }

    @Test
    fun reasonMissedWorkout_missedQuality_recommendsSwapToToday() {
        val res = EvoLabAnalyzer.reasonMissedWorkout(
            missedTitle = "W3 Tue · Threshold 2x4K",
            missedDistKm = 10.0,
            nextTitle = "W3 Wed · Easy 7K",
            nextDistKm = 7.0,
            todayDateStr = "2026-10-21"
        )

        // For missed threshold session before an easy day, recommends SHIFT to today
        val recommended = res.options.first { it.isRecommended }
        assertEquals("SHIFT", recommended.action)
        assertEquals("2026-10-21", recommended.targetDate)
        assertTrue(recommended.label.contains("Swap to Today"))
    }

    @Test
    fun reasonMissedWorkout_missedEasyRun_recommendsDropRatherThanChasingMiles() {
        val res = EvoLabAnalyzer.reasonMissedWorkout(
            missedTitle = "W3 Wed · Easy 7K",
            missedDistKm = 7.0,
            nextTitle = "W3 Fri · Easy 7K + Strides",
            nextDistKm = 7.0,
            todayDateStr = "2026-10-22"
        )

        // For missed easy miles, recommends DROP (never chase missed aerobic mileage)
        val recommended = res.options.first { it.isRecommended }
        assertEquals("DROP", recommended.action)
        assertTrue(recommended.sublabel.contains("Never chase missed aerobic miles"))
    }

    @Test
    fun analyzeRun_qualityThreshold_detectsLactateStimulus() {
        val activity = CorosApiClient.CorosActivity(
            distanceKm = 10.0,
            durationSeconds = 2960,
            avgPace = "4:56",
            avgPaceSec = 296,
            avgHr = 172,
            maxHr = 178,
            trainingLoad = 124,
            aerobicTe = 3.8,
            anaerobicTe = 1.2,
            name = "Threshold Intervals"
        )

        val analysis = EvoLabAnalyzer.analyzeRun(
            run = activity,
            planned = null,
            lthr = 172,
            nextWorkoutTitle = "Easy 7K",
            nextWorkoutPace = "6:15 /km",
            nextWorkoutZone = "Zone 2"
        )

        assertTrue(analysis.headline.contains("Lactate Clearance Stimulus"))
        assertTrue(analysis.summary.contains("Sharpening race-pace fatigue resistance") || analysis.summary.contains("Aerobic TE"))
    }

    @Test
    fun analyzeRun_textbookZone2_detectsDiscipline() {
        val activity = CorosApiClient.CorosActivity(
            distanceKm = 7.0,
            durationSeconds = 2640,
            avgPace = "6:17",
            avgPaceSec = 377,
            avgHr = 142,
            maxHr = 149,
            trainingLoad = 62,
            aerobicTe = 2.7,
            anaerobicTe = 0.0,
            name = "Morning Easy Run"
        )

        val analysis = EvoLabAnalyzer.analyzeRun(
            run = activity,
            planned = null,
            lthr = 172,
            nextWorkoutTitle = "Threshold 2x4K",
            nextWorkoutPace = "4:56 /km",
            nextWorkoutZone = "Zone 4"
        )

        assertTrue(analysis.headline.contains("Zone 2"))
        assertTrue(analysis.summary.contains("capillary") || analysis.summary.contains("aerobic"))
    }
}
