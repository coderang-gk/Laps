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

    fun getAvailableCalendars(context: Context): List<CalendarInfo> {
        val calendars = mutableListOf<CalendarInfo>()
        val uri: Uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
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
                val nameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val primIdx = c.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                while (c.moveToNext()) {
                    val isPrim = if (primIdx != -1) c.getInt(primIdx) == 1 else false
                    calendars.add(
                        CalendarInfo(
                            id = c.getLong(idIdx),
                            displayName = c.getString(nameIdx) ?: "Calendar",
                            accountName = c.getString(accIdx) ?: "",
                            isPrimary = isPrim
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return calendars
    }

    fun getPlannedWorkouts(
        context: Context,
        calendarId: Long,
        startMillis: Long,
        endMillis: Long
    ): List<PlannedWorkout> {
        val workouts = mutableListOf<PlannedWorkout>()
        val uri: Uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(calendarId.toString(), startMillis.toString(), endMillis.toString())

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val descIdx = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val startIdx = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)

                while (c.moveToNext()) {
                    val title = c.getString(titleIdx) ?: ""
                    val desc = if (descIdx != -1) c.getString(descIdx) ?: "" else ""
                    val start = c.getLong(startIdx)
                    val dateStr = sdf.format(Date(start))

                    val (dist, wType, pace, zone) = parseWorkoutMetadata(title, desc)
                    val isDone = title.contains("✅") || title.uppercase().contains("[DONE]")
                    val isSkip = title.uppercase().contains("[SKIPPED]")

                    workouts.add(
                        PlannedWorkout(
                            eventId = c.getLong(idIdx),
                            title = title,
                            description = desc,
                            startMillis = start,
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
            }
        } catch (_: SecurityException) {
        }
        return workouts
    }

    fun skipWorkout(context: Context, eventId: Long): Boolean {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val cursor = context.contentResolver.query(uri, arrayOf(CalendarContract.Events.TITLE), null, null, null)
            var currentTitle = ""
            cursor?.use { if (it.moveToFirst()) currentTitle = it.getString(0) ?: "" }

            if (!currentTitle.startsWith("[SKIPPED]")) {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, "[SKIPPED] $currentTitle")
                }
                context.contentResolver.update(uri, values, null, null) > 0
            } else true
        } catch (_: Exception) {
            false
        }
    }

    fun shiftWorkout(context: Context, eventId: Long, newDateMillis: Long): Boolean {
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
                null,
                null,
                null
            )
            var title = ""
            var oldStart = 0L
            var oldEnd = 0L
            cursor?.use {
                if (it.moveToFirst()) {
                    title = it.getString(0) ?: ""
                    oldStart = it.getLong(1)
                    oldEnd = it.getLong(2)
                }
            }

            val duration = if (oldEnd > oldStart) oldEnd - oldStart else 3600000L
            val cleanTitle = title.replace("[SKIPPED] ", "").trim()

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, cleanTitle)
                put(CalendarContract.Events.DTSTART, newDateMillis)
                put(CalendarContract.Events.DTEND, newDateMillis + duration)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    fun insertWorkoutEvent(
        context: Context,
        calendarId: Long,
        title: String,
        description: String,
        startMillis: Long,
        durationMinutes: Int = 60
    ): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val newUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            newUri?.lastPathSegment?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private data class ParsedMeta(val dist: Double, val type: String, val pace: String, val zone: String)

    private fun parseWorkoutMetadata(title: strig, description: String): ParsedMeta {
        // Safe regex parsing for distance, pace, and zones
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
        if (combined.contains("interval") || combined.contains("reps") || combined.contains("speed")) {
            wType = "Intervals"
        } else if (combined.contains("threshold") || combined.contains("tempo") || combined.contains("2x")) {
            wType = "Threshold"
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
typealias strig = String
