package io.github.coderang.laps.calendar

import io.github.coderang.laps.testing.FakeCalendarStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class PlanGeneratorTest {

    private lateinit var fakeStore: FakeCalendarStore

    @Before
    fun setUp() {
        fakeStore = FakeCalendarStore()
        CalendarSyncManager.storeProvider = { fakeStore }
    }

    @After
    fun tearDown() {
        CalendarSyncManager.storeProvider = null
    }

    @Test
    fun generatePlan_halfMarathon_generatesCorrectWorkoutsAndInsertsToCalendar() {
        val raceCal = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 13, 6, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val planWeeks = 10

        val plan = PlanGenerator.generatePlan(
            context = null,
            calendarId = 1L,
            raceDistance = PlanGenerator.RaceDistance.HALF_MARATHON,
            raceDateMillis = raceCal.timeInMillis,
            planWeeks = planWeeks
        )

        // 4 running days per week x 10 weeks = 40 workouts
        assertEquals(40, plan.size)

        // Verifies events were inserted into the fake calendar store
        val storedEvents = fakeStore.getAllEvents()
        assertEquals(40, storedEvents.size)

        // Final event must be Race Day
        val raceDayEvent = plan.last()
        assertTrue(raceDayEvent.title.contains("RACE DAY"))
        assertEquals(21.1, raceDayEvent.targetDistanceKm, 0.01)

        // Sunday Long Runs must progress up to 21 km
        val sundayLongRuns = plan.filter { it.workoutType == "Long Run" }
        assertTrue("Must contain multiple progressive long runs", sundayLongRuns.size >= 8)
        assertTrue(sundayLongRuns.any { it.targetDistanceKm >= 18.0 })
    }

    @Test
    fun generatePlan_marathon_peaksVolumeProperly() {
        val raceCal = Calendar.getInstance().apply {
            set(2027, Calendar.JANUARY, 17, 6, 0, 0)
        }
        val plan = PlanGenerator.generatePlan(
            context = null,
            calendarId = 1L,
            raceDistance = PlanGenerator.RaceDistance.MARATHON,
            raceDateMillis = raceCal.timeInMillis,
            planWeeks = 16
        )

        assertEquals(64, plan.size)
        val marathonRaceDay = plan.last()
        assertEquals(42.2, marathonRaceDay.targetDistanceKm, 0.01)

        val peakLongRun = plan.filter { it.workoutType == "Long Run" }.maxByOrNull { it.targetDistanceKm }
        assertNotNull(peakLongRun)
        assertTrue("Marathon long run must peak at >= 30 km", peakLongRun!!.targetDistanceKm >= 30.0)
    }

    @Test
    fun generatePlan_negativeOrInvalidCalendarId_doesNotInsertEventsToCalendar() {
        val raceCal = Calendar.getInstance().apply {
            set(2026, Calendar.DECEMBER, 13, 6, 30, 0)
        }
        val plan = PlanGenerator.generatePlan(
            context = null,
            calendarId = -1L,
            raceDistance = PlanGenerator.RaceDistance.HALF_MARATHON,
            raceDateMillis = raceCal.timeInMillis,
            planWeeks = 8
        )

        // Workouts are generated in memory for preview
        assertEquals(32, plan.size)

        // BUT zero events are written to calendar storage because calendarId is -1 (no calendar selected)
        val storedEvents = fakeStore.getAllEvents()
        assertEquals(0, storedEvents.size)
    }
}
