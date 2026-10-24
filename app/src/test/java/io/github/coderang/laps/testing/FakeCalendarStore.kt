package io.github.coderang.laps.testing

import io.github.coderang.laps.calendar.CalendarSyncManager

/**
 * Hermetic in-memory Fake Calendar Store (matching Canta's testing philosophy).
 * Runs on pure JVM without Android runtime or ContentProvider mocks.
 */
class FakeCalendarStore : CalendarSyncManager.CalendarStore {

    private val calendars = mutableListOf(
        CalendarSyncManager.CalendarInfo(
            id = 1L,
            displayName = "Primary Calendar",
            accountName = "runner@example.com",
            isPrimary = true
        )
    )

    private val events = mutableMapOf<Long, CalendarSyncManager.CalendarEventRecord>()
    private var nextId = 100L

    fun seedEvent(record: CalendarSyncManager.CalendarEventRecord) {
        events[record.id] = record
    }

    fun getAllEvents(): List<CalendarSyncManager.CalendarEventRecord> = events.values.toList()

    override fun getAvailableCalendars(): List<CalendarSyncManager.CalendarInfo> = calendars

    override fun queryEvents(
        calendarId: Long,
        startMillis: Long,
        endMillis: Long
    ): List<CalendarSyncManager.CalendarEventRecord> {
        return events.values
            .filter { it.calendarId == calendarId && it.startMillis in startMillis..endMillis }
            .sortedBy { it.startMillis }
    }

    override fun queryEventById(eventId: Long): CalendarSyncManager.CalendarEventRecord? {
        return events[eventId]
    }

    override fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean {
        val existing = events[eventId] ?: return false
        events[eventId] = existing.copy(
            title = title,
            startMillis = startMillis,
            endMillis = endMillis
        )
        return true
    }

    override fun insertEvent(
        calendarId: Long,
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long
    ): Long? {
        val id = nextId++
        events[id] = CalendarSyncManager.CalendarEventRecord(
            id = id,
            calendarId = calendarId,
            title = title,
            description = description,
            startMillis = startMillis,
            endMillis = endMillis
        )
        return id
    }

    override fun clearPlanEvents(calendarId: Long): Int {
        val toRemove = events.filter { (_, v) ->
            v.calendarId == calendarId && (v.title.matches(Regex("^W\\d+.*·.*")) || v.title.startsWith("🏁 RACE DAY"))
        }.keys
        toRemove.forEach { events.remove(it) }
        return toRemove.size
    }

    override fun findRaceEvent(calendarId: Long, fromMillis: Long): CalendarSyncManager.CalendarEventRecord? {
        return events.values
            .filter {
                it.calendarId == calendarId &&
                    it.startMillis >= fromMillis &&
                    (it.title.contains("RACE DAY", ignoreCase = true) || it.title.contains("Race", ignoreCase = true))
            }
            .minByOrNull { it.startMillis }
    }
}
