package io.github.coderang.laps.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Native Android CalendarContract Manager.
 * Reads and writes workouts directly into the user's existing Google Calendar on Android.
 * Zero credentials.json or OAuth developer setups required.
 */
object CalendarSyncManager {

    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val isPrimary: Boolean
    )

    data class PlannedWorkout(
        val eventId: Long,
        val title: String,
        val description: String,
        val startMillis: Long,
        val dateString: String, // YYYY-MM-DD
        val targetDistanceKm: Double,
        val workoutType: String,
        val targetPace: String,
        val targetHrZone: String,
        val isCompleted: Boolean,
        val isSkipped: Boolean
    )

    data class CalendarEventRecord(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val description: String,
        val startMillis: Long,
        val endMillis: Long
    )

    interface CalendarStore {
        fun getAvailableCalendars(): List<CalendarInfo>
        fun queryEvents(calendarId: Long, startMillis: Long, endMillis: Long): List<CalendarEventRecord>
        fun queryEventById(eventId: Long): CalendarEventRecord?
        fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean
        fun insertEvent(calendarId: Long, title: String, description: String, startMillis: Long, endMillis: Long): Long?
    }

    // Allows unit tests to inject an in-memory FakeCalendarStore on pure JVM
    var storeProvider: ((Context?) -> CalendarStore)? = null

    internal fun getStore(context: Context?): CalendarStore {
        return storeProvider?.invoke(context) ?: AndroidCalendarStore(context!!)
    }

    private class AndroidCalendarStore(private val context: Context) : CalendarStore {
        override fun getAvailableCalendars(): List<CalendarInfo> {
            val calendars = mutableListOf<CalendarInfo>()
            val uri: Uri = CalendarContract.Calendars.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.IS_PRIMARY
            )
            try {
                val cursor: Cursor? = context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
                )
                cursor?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                    val dispNameIdx = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                    val nameIdx = c.getColumnIndex(CalendarContract.Calendars.NAME)
                    val accIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                    val primIdx = c.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                    while (c.moveToNext()) {
                        val isPrim = if (primIdx != -1) c.getInt(primIdx) == 1 else false
                        val dispName = if (dispNameIdx != -1) c.getString(dispNameIdx) else null
                        val altName = if (nameIdx != -1) c.getString(nameIdx) else null
                        val calId = c.getLong(idIdx)
                        val finalName = dispName?.ifBlank { null } ?: altName?.ifBlank { null } ?: "Calendar #$calId"
                        calendars.add(
                            CalendarInfo(
                                id = calId,
                                displayName = finalName,
                                accountName = c.getString(accIdx) ?: "",
                                isPrimary = isPrim
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {}
            return calendars
        }

        override fun queryEvents(calendarId: Long, startMillis: Long, endMillis: Long): List<CalendarEventRecord> {
            val events = mutableListOf<CalendarEventRecord>()
            val uri: Uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            )
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

            try {
                val cursor = context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "${CalendarContract.Events.DTSTART} ASC"
                )
                cursor?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
                    val calIdIdx = c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
                    val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                    val descIdx = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                    val startIdx = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                    val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)

                    while (c.moveToNext()) {
                        val s = c.getLong(startIdx)
                        val e = if (endIdx != -1) c.getLong(endIdx) else s + 3600000L
                        events.add(
                            CalendarEventRecord(
                                id = c.getLong(idIdx),
                                calendarId = c.getLong(calIdIdx),
                                title = c.getString(titleIdx) ?: "",
                                description = if (descIdx != -1) c.getString(descIdx) ?: "" else "",
                                startMillis = s,
                                endMillis = e
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {}
            return events
        }

        override fun queryEventById(eventId: Long): CalendarEventRecord? {
            return try {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.CALENDAR_ID,
                        CalendarContract.Events.TITLE,
                        CalendarContract.Events.DESCRIPTION,
                        CalendarContract.Events.DTSTART,
                        CalendarContract.Events.DTEND
                    ),
                    null,
                    null,
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val s = it.getLong(4)
                        val e = it.getLong(5)
                        CalendarEventRecord(
                            id = it.getLong(0),
                            calendarId = it.getLong(1),
                            title = it.getString(2) ?: "",
                            description = it.getString(3) ?: "",
                            startMillis = s,
                            endMillis = if (e > s) e else s + 3600000L
                        )
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }

        override fun updateEvent(eventId: Long, title: String, startMillis: Long, endMillis: Long): Boolean {
            return try {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                }
                context.contentResolver.update(uri, values, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }

        override fun insertEvent(
            calendarId: Long,
            title: String,
            description: String,
            startMillis: Long,
            endMillis: Long
        ): Long? {
            return try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                val newUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                newUri?.lastPathSegment?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getAvailableCalendars(context: Context? = null): List<CalendarInfo> =
        getStore(context).getAvailableCalendars()

    /**
     * Triggers an expedited sync on Android's Google Calendar SyncAdapter
     * so newly created calendars on the user's Google account appear in the local ContentProvider.
     */
    fun requestSync(context: Context) {
        try {
            val accounts = android.accounts.AccountManager.get(context).accounts
            for (acc in accounts) {
                if (acc.type == "com.google" || acc.type.contains("calendar", ignoreCase = true)) {
                    val bundle = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    }
                    android.content.ContentResolver.requestSync(acc, CalendarContract.AUTHORITY, bundle)
                }
            }
        } catch (_: Exception) {}
    }

    fun getPlannedWorkouts(
        context: Context? = null,
        calendarId: Long,
        startMillis: Long,
        endMillis: Long
    ): List<PlannedWorkout> {
        val records = getStore(context).queryEvents(calendarId, startMillis, endMillis)
        val workouts = mutableListOf<PlannedWorkout>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        for (rec in records) {
            val dateStr = sdf.format(Date(rec.startMillis))
            val (dist, wType, pace, zone) = parseWorkoutMetadata(rec.title, rec.description)
            val isDone = rec.title.contains("✅") || rec.title.uppercase(Locale.US).contains("[DONE]")
            val isSkip = rec.title.uppercase(Locale.US).contains("[SKIPPED]")

            workouts.add(
                PlannedWorkout(
                    eventId = rec.id,
                    title = rec.title,
                    description = rec.description,
                    startMillis = rec.startMillis,
                    dateString = dateStr,
                    targetDistanceKm = dist,
                    workoutType = wType,
                    targetPace = pace,
                    targetHrZone = zone,
                    isCompleted = isDone,
                    isSkipped = isSkip
                )
            )
        }
        return workouts
    }

    fun skipWorkout(context: Context? = null, eventId: Long): Boolean {
        return try {
            val store = getStore(context)
            val event = store.queryEventById(eventId) ?: return false

            if (!event.title.startsWith("[SKIPPED]")) {
                val newTitle = "[SKIPPED] ${event.title}"
                store.updateEvent(eventId, newTitle, event.startMillis, event.endMillis)
            } else true
        } catch (_: Exception) {
            false
        }
    }

    fun shiftWorkout(context: Context? = null, eventId: Long, newDateMillis: Long): Boolean {
        return try {
            val store = getStore(context)
            val event = store.queryEventById(eventId) ?: return false

            val duration = if (event.endMillis > event.startMillis) event.endMillis - event.startMillis else 3600000L
            val rawTitle = event.title.replace("[SKIPPED] ", "").trim()

            // Align newDateMillis to the hour and minute of the original scheduled start
            val oldCal = Calendar.getInstance().apply { timeInMillis = event.startMillis }
            val newCal = Calendar.getInstance().apply {
                timeInMillis = newDateMillis
                set(Calendar.HOUR_OF_DAY, oldCal.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, oldCal.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val actualNewStart = newCal.timeInMillis

            // Update day-of-week abbreviation in title (e.g. W3 Tue -> W3 Wed)
            val cleanTitle = updateDayAbbrInTitle(rawTitle, actualNewStart)

            store.updateEvent(eventId, cleanTitle, actualNewStart, actualNewStart + duration)
        } catch (_: Exception) {
            false
        }
    }

    internal fun updateDayAbbrInTitle(title: String, dateMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val newDayStr = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> return title
        }
        return title.replace(Regex("\\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b"), newDayStr)
    }

    fun insertWorkoutEvent(
        context: Context? = null,
        calendarId: Long,
        title: String,
        description: String,
        startMillis: Long,
        durationMinutes: Int = 60
    ): Long? {
        val store = getStore(context)
        return store.insertEvent(
            calendarId = calendarId,
            title = title,
            description = description,
            startMillis = startMillis,
            endMillis = startMillis + durationMinutes * 60000L
        )
    }

    internal data class ParsedMeta(val dist: Double, val type: String, val pace: String, val zone: String)

    internal fun parseWorkoutMetadata(title: String, description: String): ParsedMeta {
        val combined = "$title $description".lowercase(Locale.US)
        var dist = 0.0

        val totalMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km|k)\\s*total").matcher(combined)
        if (totalMatcher.find()) {
            dist = totalMatcher.group(1)?.toDoubleOrNull() ?: 0.0
        } else {
            val distMatcher = Pattern.compile("(?<![x×\\d.])(\\d+(?:\\.\\d+)?)\\s*(?:km|k\\b)").matcher(combined)
            if (distMatcher.find()) {
                dist = distMatcher.group(1)?.toDoubleOrNull() ?: 0.0
            } else if (combined.contains("half marathon") || combined.contains("21.1")) {
                dist = 21.1
            } else if (combined.contains("marathon") || combined.contains("42.2")) {
                dist = 42.2
            }
        }

        var wType = "Easy Run"
        if (combined.contains("threshold") || combined.contains("tempo")) {
            wType = "Threshold"
        } else if (combined.contains("interval") || combined.contains("reps") || combined.contains("speed")) {
            wType = "Intervals"
        } else if (combined.contains("long") || dist >= 14.0) {
            wType = "Long Run"
        } else if (combined.contains("stride")) {
            wType = "Easy + Strides"
        } else if (combined.contains("recovery")) {
            wType = "Recovery"
        }

        var pace = when (wType) {
            "Threshold" -> "4:55–5:00 /km"
            "Intervals" -> "4:35–4:45 /km"
            "Long Run" -> "5:45–6:15 /km"
            else -> "6:00–6:30 /km"
        }
        val paceMatcher = Pattern.compile("(\\d{1,2}:\\d{2}(?:\\s*[–-]\\s*\\d{1,2}:\\d{2})?)\\s*(?:/km|min/km)").matcher(combined)
        if (paceMatcher.find()) {
            pace = "${paceMatcher.group(1)} /km"
        }

        val zone = when (wType) {
            "Threshold" -> "Zone 4 (163–172 bpm)"
            "Intervals" -> "Zone 5 (173–185 bpm)"
            "Long Run" -> "Zone 2–3 (145–160 bpm)"
            else -> "Zone 2 (<155 bpm)"
        }

        return ParsedMeta(dist, wType, pace, zone)
    }
}
