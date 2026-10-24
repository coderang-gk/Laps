package io.github.coderang.laps.data

import org.junit.Assert.*
import org.junit.Test

class LapsDataModelsTest {

    @Test
    fun missedWorkoutData_defaultSample_hasValidOptions() {
        val sample = MissedWorkoutData.defaultSample()
        assertEquals("W3 Thu · 2x4K Threshold", sample.title)
        assertEquals(10.0, sample.targetDistanceKm, 0.01)
        assertEquals(2, sample.options.size)

        val recommended = sample.options.firstOrNull { it.isRecommended }
        assertNotNull("Must contain an AI recommended option", recommended)
        assertEquals("DROP", recommended?.action)
    }

    @Test
    fun lapsPayload_defaultsToNullMissedWorkout() {
        val payload = LapsPayload()
        assertNull("Default payload should not show ghost missed workouts", payload.missedWorkout)
    }

    @Test
    fun weeklyMileageData_defaultSample_calculatesConsistentRatios() {
        val wm = WeeklyMileageData()
        assertEquals(22.35, wm.actualKm, 0.01)
        assertEquals(44.0, wm.plannedKm, 0.01)
        assertEquals(51, wm.completionPct)
        assertEquals(7, wm.days.size)
    }

    @Test
    fun trainingLoadData_defaultSample_matchesEvoLabProductiveStatus() {
        val tl = TrainingLoadData()
        assertEquals(401, tl.acuteTl)
        assertEquals(172, tl.lthr)
        assertEquals(44, tl.rhr)
        assertEquals("PRODUCTIVE", tl.statusBadge)
    }

    @Test
    fun zoneSpectrumData_polarizationRatios_sumTo100Percent() {
        val zs = ZoneSpectrumData()
        assertEquals(78, zs.aerobicPct)
        assertEquals(22, zs.thresholdPct)
        assertEquals(100, zs.aerobicPct + zs.thresholdPct)
        assertEquals("NO ZONE CREEP", zs.zoneCreepStatus)
    }
}
