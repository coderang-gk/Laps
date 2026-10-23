package io.github.coderang.laps.calendar

import io.github.coderang.laps.testing.FakeCalendarStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class CalendarSyncManagerTest {

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
    fun skipWorkout_prefixesSkippedTag() {
        val initialStart = 1761373800000L // Tuesday 6:30 AM
        val initialEnd = initialStart + 3600000L
        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 101L,
                calendarId = 1L,
                title = "W3 Tue · Threshold 2x4K",
                description = "Lactate threshold stimulus",
                startMillis = initialStart,
                endMillis = initialEnd
            )
        )

        val success = CalendarSyncManager.skipWorkout(eventId = 101L)
        assertTrue("skipWorkout should return true on success", success)

        val updated = fakeStore.queryEventById(101L)
        assertNotNull(updated)
        assertEquals("[SKIPPED] W3 Tue · Threshold 2x4K", updated?.title)
        assertEquals(initialStart, updated?.startMillis)
        assertEquals(initialEnd, updated?.endMillis)
    }

    @Test
    fun skipWorkout_isIdempotent_doesNotDuplicateSkippedTag() {
        val start = 1761373800000L
        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 102L,
                calendarId = 1L,
                title = "[SKIPPED] W3 Tue · Threshold 2x4K",
                description = "Already skipped",
                startMillis = start,
                endMillis = start + 3600000L
            )
        )

        val success = CalendarSyncManager.skipWorkout(eventId = 102L)
        assertTrue(success)

        val updated = fakeStore.queryEventById(102L)
        assertEquals("[SKIPPED] W3 Tue · Threshold 2x4K", updated?.title)
    }

    @Test
    fun shiftWorkout_updatesDateAndRewritesDayAbbr() {
        // Tuesday Oct 20, 2026 6:30 AM
        val tuesdayCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 20, 6, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tuesdayStart = tuesdayCal.timeInMillis
        val tuesdayEnd = tuesdayStart + 3600000L

        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 201L,
                calendarId = 1L,
                title = "W3 Tue · Threshold 2x4K",
                description = "10K total",
                startMillis = tuesdayStart,
                endMillis = tuesdayEnd
            )
        )

        // Target Date: Wednesday Oct 21, 2026 (passed as midnight timestamp)
        val wednesdayTarget = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 21, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val success = CalendarSyncManager.shiftWorkout(eventId = 201L, newDateMillis = wednesdayTarget)
        assertTrue("shiftWorkout should succeed", success)

        val updated = fakeStore.queryEventById(201L)
        assertNotNull(updated)

        // Asserts title rewrote day abbreviation from "Tue" to "Wed"
        assertEquals("W3 Wed · Threshold 2x4K", updated?.title)

        // Asserts start time preserved original 6:30 AM
        val updatedCal = Calendar.getInstance().apply { timeInMillis = updated!!.startMillis }
        assertEquals(Calendar.WEDNESDAY, updatedCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(6, updatedCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, updatedCal.get(Calendar.MINUTE))
        assertEquals(3600000L, updated!!.endMillis - updated!!.startMillis)
    }

    @Test
    fun shiftWorkout_removesSkippedTagIfPreviouslySkipped() {
        val start = System.currentTimeMillis()
        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 202L,
                calendarId = 1L,
                title = "[SKIPPED] W3 Tue · Threshold 2x4K",
                description = "Was skipped earlier",
                startMillis = start,
                endMillis = start + 3600000L
            )
        )

        val newDate = start + 86400000L // +1 day
        CalendarSyncManager.shiftWorkout(eventId = 202L, newDateMillis = newDate)

        val updated = fakeStore.queryEventById(202L)
        assertFalse(
            "Shifted workout must not contain [SKIPPED] tag",
            updated?.title?.contains("[SKIPPED]") ?: true
        )
    }

    @Test
    fun parseWorkoutMetadata_correctlyExtractsDistanceAndType() {
        val meta1 = CalendarSyncManager.parseWorkoutMetadata(
            title = "W3 Sun · Long Run 20K",
            description = "Aerobic endurance builder. Keep HR in Zone 2"
        )
        assertEquals(20.0, meta1.dist, 0.01)
        assertEquals("Long Run", meta1.type)
        assertEquals("5:45–6:15 /km", meta1.pace)

        val meta2 = CalendarSyncManager.parseWorkoutMetadata(
            title = "W3 Tue · Threshold 2x4K (10K total)",
            description = "Lactate threshold reps at 4:56 /km"
        )
        assertEquals(10.0, meta2.dist, 0.01)
        assertEquals("Threshold", meta2.type)
        assertEquals("4:56 /km", meta2.pace)
    }

    @Test
    fun getPlannedWorkouts_accuratelyParsesDoneAndSkippedFlags() {
        val now = System.currentTimeMillis()
        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 301L,
                calendarId = 1L,
                title = "W3 Tue · Threshold 2x4K ✅",
                description = "Completed",
                startMillis = now,
                endMillis = now + 3600000L
            )
        )
        fakeStore.seedEvent(
            CalendarSyncManager.CalendarEventRecord(
                id = 302L,
                calendarId = 1L,
                title = "[SKIPPED] W3 Wed · Easy 7K",
                description = "Skipped",
                startMillis = now + 1000L,
                endMillis = now + 3601000L
            )
        )

        val workouts = CalendarSyncManager.getPlannedWorkouts(
            calendarId = 1L,
            startMillis = now - 10000L,
            endMillis = now + 20000L
        )

        assertEquals(2, workouts.size)
        assertTrue(workouts[0].isCompleted)
        assertFalse(workouts[0].isSkipped)

        assertFalse(workouts[1].isCompleted)
        assertTrue(workouts[1].isSkipped)
    }
}
