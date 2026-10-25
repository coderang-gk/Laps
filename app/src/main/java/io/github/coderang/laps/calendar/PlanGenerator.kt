package io.github.coderang.laps.calendar

import android.content.Context
import java.util.*

/**
 * Scientific 80/20 Periodized Plan Builder.
 * Generates structured plans for 5K, 10K, Half Marathon, and Full Marathon based on the athlete's
 * COROS EvoLab profile and publishes them to Google Calendar in one tap.
 */
object PlanGenerator {

    enum class RaceDistance(val label: String, val km: Double) {
        FIVE_K("5K", 5.0),
        TEN_K("10K", 10.0),
        HALF_MARATHON("Half Marathon", 21.1),
        MARATHON("Marathon", 42.2)
    }

    data class PlanWorkoutItem(
        val dayOffset: Int, // days from plan start
        val title: String,
        val description: String,
        val targetDistanceKm: Double,
        val workoutType: String,
        val dateMillis: Long
    )

    /**
     * Computes the upcoming Monday at 06:30 AM.
     * If today is Monday before 06:30 AM, returns today.
     * Otherwise returns next week's Monday so workouts always start fresh in the future.
     */
    fun getUpcomingMonday(baseCal: Calendar = Calendar.getInstance()): Calendar {
        val cal = (baseCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysUntilMonday = when (dayOfWeek) {
            Calendar.MONDAY -> if (baseCal.get(Calendar.HOUR_OF_DAY) < 6) 0 else 7
            Calendar.TUESDAY -> 6
            Calendar.WEDNESDAY -> 5
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 3
            Calendar.SATURDAY -> 2
            Calendar.SUNDAY -> 1
            else -> 7
        }
        cal.add(Calendar.DAY_OF_YEAR, daysUntilMonday)
        return cal
    }

    fun generatePlan(
        context: Context? = null,
        calendarId: Long,
        raceDistance: RaceDistance,
        raceDateMillis: Long? = null,
        startDateMillis: Long? = null,
        planWeeks: Int = 10,
        runningDaysOfWeek: List<Int> = listOf(Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.FRIDAY, Calendar.SUNDAY),
        ltspPace: String = "4:56 /km",
        lthrBpm: Int = 172,
        skipPastEvents: Boolean = false,
        customRaceName: String? = null
    ): List<PlanWorkoutItem> {
        val workouts = mutableListOf<PlanWorkoutItem>()
        val startMonday = when {
            startDateMillis != null -> {
                Calendar.getInstance().apply {
                    timeInMillis = startDateMillis
                    set(Calendar.HOUR_OF_DAY, 6)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            raceDateMillis != null -> {
                Calendar.getInstance().apply {
                    timeInMillis = raceDateMillis
                    // Race day is Sunday of the final week (planWeeks)
                    add(Calendar.WEEK_OF_YEAR, -(planWeeks - 1))
                    while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                        add(Calendar.DAY_OF_YEAR, -1)
                    }
                    set(Calendar.HOUR_OF_DAY, 6)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            else -> getUpcomingMonday()
        }

        // Base peak weekly volume depending on goal
        val peakKm = when (raceDistance) {
            RaceDistance.FIVE_K -> 30.0
            RaceDistance.TEN_K -> 40.0
            RaceDistance.HALF_MARATHON -> 48.0
            RaceDistance.MARATHON -> 68.0
        }

        val nowMillis = System.currentTimeMillis()

        for (week in 1..planWeeks) {
            val isTaper = week >= planWeeks - 1
            val isRecoveryWeek = week % 4 == 0 && !isTaper
            val volumeFactor = when {
                isTaper && week == planWeeks -> 0.45
                isTaper -> 0.70
                isRecoveryWeek -> 0.75
                else -> 0.65 + (week.toDouble() / planWeeks) * 0.35
            }
            val weekTotalKm = peakKm * volumeFactor

            for (dayOfWeek in runningDaysOfWeek) {
                val dayOffsetFromMonday = when (dayOfWeek) {
                    Calendar.MONDAY -> 0
                    Calendar.TUESDAY -> 1
                    Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3
                    Calendar.FRIDAY -> 4
                    Calendar.SATURDAY -> 5
                    Calendar.SUNDAY -> 6
                    else -> 0
                }
                val workoutCal = (startMonday.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + dayOffsetFromMonday)
                }

                val isSundayLongRun = dayOfWeek == Calendar.SUNDAY
                val isQualityDay = dayOfWeek == Calendar.TUESDAY

                val (title, distKm, desc, wType) = when {
                    isSundayLongRun && week == planWeeks -> {
                        // Race Day!
                        val eventName = if (!customRaceName.isNullOrBlank()) customRaceName.trim() else raceDistance.label
                        val rTitle = "🏁 RACE DAY: $eventName (${raceDistance.km}K)"
                        val rDesc = "Target Pace: $ltspPace. Fuel every 45 mins. Execute your negative split plan!"
                        Quad(rTitle, raceDistance.km, rDesc, "Race")
                    }
                    isSundayLongRun -> {
                        val lrDist = when (raceDistance) {
                            RaceDistance.FIVE_K -> (6.0 + week * 0.6).coerceAtMost(10.0)
                            RaceDistance.TEN_K -> (8.0 + week * 0.9).coerceAtMost(14.0)
                            RaceDistance.HALF_MARATHON -> (11.0 + week * 1.1).coerceAtMost(21.0)
                            RaceDistance.MARATHON -> (16.0 + week * 1.8).coerceAtMost(32.0)
                        } * (if (isRecoveryWeek) 0.8 else 1.0)
                        val rDist = (lrDist * 10).toInt() / 10.0
                        val rTitle = "W$week Sun · Long Run ${rDist.toInt()}K"
                        val rDesc = "Aerobic endurance builder. Keep heart rate strictly inside Zone 2 (<${(lthrBpm * 0.90).toInt()} bpm)."
                        Quad(rTitle, rDist, rDesc, "Long Run")
                    }
                    isQualityDay -> {
                        val qDist = (weekTotalKm * 0.22).coerceIn(6.0, 12.0)
                        val rDist = (qDist * 10).toInt() / 10.0
                        val rTitle = if (week % 2 == 1) "W$week Tue · Threshold 2x4K" else "W$week Tue · Tempo ${rDist.toInt()}K"
                        val rDesc = "Lactate threshold stimulus. Target: $ltspPace at Zone 4 ($lthrBpm bpm)."
                        Quad(rTitle, rDist, rDesc, "Threshold")
                    }
                    else -> {
                        val eDist = (weekTotalKm * 0.16).coerceIn(4.0, 9.0)
                        val rDist = (eDist * 10).toInt() / 10.0
                        val hasStrides = dayOfWeek == Calendar.FRIDAY
                        val rTitle = if (hasStrides) "W$week Fri · Easy ${rDist.toInt()}K + 4 Strides" else "W$week Wed · Easy ${rDist.toInt()}K"
                        val rDesc = "Aerobic base recovery. Conversational effort (<152 bpm)."
                        Quad(rTitle, rDist, rDesc, if (hasStrides) "Easy + Strides" else "Easy Run")
                    }
                }

                val item = PlanWorkoutItem(
                    dayOffset = ((workoutCal.timeInMillis - startMonday.timeInMillis) / 86400000L).toInt(),
                    title = title,
                    description = desc,
                    targetDistanceKm = distKm,
                    workoutType = wType,
                    dateMillis = workoutCal.timeInMillis
                )
                workouts.add(item)

                // Insert directly to Google Calendar only if a valid calendar is targeted
                // and the workout is not in the past (unless past events are permitted)
                val isPast = workoutCal.timeInMillis < nowMillis
                if (calendarId > 0L && (!skipPastEvents || !isPast)) {
                    CalendarSyncManager.insertWorkoutEvent(
                        context = context,
                        calendarId = calendarId,
                        title = title,
                        description = desc,
                        startMillis = workoutCal.timeInMillis,
                        durationMinutes = (distKm * 6.5).toInt().coerceIn(30, 180)
                    )
                }
            }
        }
        return workouts
    }

    private data class Quad(val t: String, val d: Double, val desc: String, val w: String)
}
