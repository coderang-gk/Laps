package io.github.coderang.laps.data

import android.content.Context
import io.github.coderang.laps.calendar.CalendarSyncManager
import io.github.coderang.laps.coach.EvoLabAnalyzer
import io.github.coderang.laps.coach.GeminiCoachClient
import io.github.coderang.laps.coros.CorosApiClient
import java.text.SimpleDateFormat
import java.util.*

/**
 * Central On-Device Repository for Laps.
 * Coordinates direct COROS Training Hub API calls, Android CalendarContract reads/writes,
 * and EvoLab physiological analysis entirely on the device without any server.
 */
object LapsRepository {

    private const val PREFS_NAME = "laps_app_prefs"
    private const val KEY_COROS_EMAIL = "coros_email"
    private const val KEY_COROS_PASSWORD = "coros_password"
    private const val KEY_COROS_TOKEN = "coros_access_token"
    private const val KEY_CALENDAR_ID = "selected_calendar_id"
    private const val KEY_RACE_NAME = "race_name"
    private const val KEY_RACE_DATE_MILLIS = "race_date_millis"
    private const val KEY_GOAL_TIME = "goal_time"
    private const val KEY_GOAL_PACE = "goal_pace"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_SETUP_COMPLETED = "setup_completed"

    fun isSetupCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_COMPLETED, completed)
            .apply()
    }

    fun hasCredentials(context: Context): Boolean {
        val token = getCorosToken(context)
        if (token.isNotBlank()) return true
        val (email, pwd) = getCorosCredentials(context)
        return email.isNotBlank() && pwd.isNotBlank()
    }

    fun getCorosToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COROS_TOKEN, "") ?: ""
    }

    fun saveCorosToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COROS_TOKEN, token.trim())
            .apply()
    }

    fun getCorosCredentials(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(
            prefs.getString(KEY_COROS_EMAIL, "") ?: "",
            prefs.getString(KEY_COROS_PASSWORD, "") ?: ""
        )
    }

    fun saveCorosCredentials(context: Context, email: String, pwd: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COROS_EMAIL, email.trim())
            .putString(KEY_COROS_PASSWORD, pwd.trim())
            .apply()
    }

    /**
     * Atomically purges all authentication tokens, passwords, and API keys from disk
     * and resets in-memory cache to demo state.
     */
    fun clearAllCredentials(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COROS_EMAIL)
            .remove(KEY_COROS_PASSWORD)
            .remove(KEY_COROS_TOKEN)
            .remove(KEY_GEMINI_KEY)
            .putBoolean(KEY_SETUP_COMPLETED, false)
            .apply()
        resetSamplePayload()
    }

    fun getSelectedCalendarId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_CALENDAR_ID, -1L)
    }

    fun saveSelectedCalendarId(context: Context, calendarId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CALENDAR_ID, calendarId)
            .apply()
    }

    fun getGeminiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_KEY, "") ?: ""
    }

    fun saveGeminiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GEMINI_KEY, key.trim())
            .apply()
    }

    fun getStyleForTile(context: Context, tileType: ExpressiveTileType): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("style_${tileType.name}", tileType.ordinal)
    }

    fun setStyleForTile(context: Context, tileType: ExpressiveTileType, styleIdx: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("style_${tileType.name}", styleIdx)
            .apply()
    }

    private var cachedPayload: LapsPayload = LapsPayload()

    fun getCachedPayload(): LapsPayload = cachedPayload

    fun setCachedPayload(payload: LapsPayload) {
        cachedPayload = payload
    }

    fun resetSamplePayload() {
        cachedPayload = LapsPayload()
    }

    fun syncOnDevice(context: Context): Result<LapsPayload> {
        val token = getCorosToken(context)
        val (email, password) = getCorosCredentials(context)
        if (token.isBlank() && (email.isBlank() || password.isBlank())) {
            return Result.success(cachedPayload) // Demo fallback state
        }

        return try {
            val coros = CorosApiClient()
            val profile = if (token.isNotBlank()) {
                val tokenResult = coros.authenticateWithToken(token)
                tokenResult.getOrNull() ?: CorosApiClient.CorosProfile(nickname = "COROS Athlete")
            } else {
                val loginResult = coros.login(email, password)
                loginResult.getOrNull() ?: CorosApiClient.CorosProfile()
            }

            val actResult = coros.fetchActivities(limit = 25)
            val activities = actResult.getOrNull() ?: emptyList()

            // 1. Calculate current week (Monday..Sunday)
            val cal = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            val todayDate = cal.time
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = sdf.format(todayDate)

            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val mondayMillis = cal.timeInMillis
            val mondayDate = cal.time
            cal.add(Calendar.DAY_OF_YEAR, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            val sundayMillis = cal.timeInMillis

            // 2. Query Calendar for Planned Workouts (Mon..Sun + 10 days)
            val calId = getSelectedCalendarId(context)
            val plannedList = if (calId != -1L) {
                CalendarSyncManager.getPlannedWorkouts(
                    context = context,
                    calendarId = calId,
                    startMillis = mondayMillis - 86400000L,
                    endMillis = sundayMillis + 864000000L
                )
            } else emptyList()

            val plannedByDate = plannedList.associateBy { it.dateString }
            val activitiesByDate = activities.groupBy { it.dateOnly }

            // 3. Build Weekly Mileage Matrix
            val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
            val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            val daysMatrix = mutableListOf<DayBarData>()
            var actualWkKm = 0.0
            var plannedWkKm = 0.0
            var runsDone = 0

            val iterCal = Calendar.getInstance().apply { time = mondayDate }
            for (i in 0 until 7) {
                val dStr = sdf.format(iterCal.time)
                val dayActs = activitiesByDate[dStr] ?: emptyList()
                val actualKm = dayActs.sumOf { it.distanceKm }
                val planned = plannedByDate[dStr]
                val plannedKm = planned?.targetDistanceKm ?: (if (actualKm > 0) actualKm else 0.0)

                actualWkKm += actualKm
                plannedWkKm += plannedKm
                if (actualKm > 0) runsDone++

                val isToday = dStr == todayStr
                val status = when {
                    actualKm > 0 -> "COMPLETED"
                    isToday && plannedKm > 0 -> "TODAY"
                    dStr > todayStr && plannedKm > 0 -> "PLANNED"
                    dStr < todayStr && plannedKm > 0 -> "MISSED"
                    else -> "REST"
                }

                daysMatrix.add(
                    DayBarData(
                        index = i,
                        dayLetter = dayLetters[i],
                        dayName = dayNames[i],
                        date = dStr,
                        isToday = isToday,
                        actualKm = ((actualKm * 100).toInt()) / 100.0,
                        plannedKm = ((plannedKm * 100).toInt()) / 100.0,
                        status = status,
                        workoutType = planned?.workoutType ?: if (actualKm > 0) "Run" else "Rest"
                    )
                )
                iterCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            actualWkKm = ((actualWkKm * 100).toInt()) / 100.0
            plannedWkKm = ((plannedWkKm * 100).toInt()) / 100.0
            val completionPct = if (plannedWkKm > 0) ((actualWkKm / plannedWkKm) * 100.0 + 0.5).toInt() else 0

            // 4. Upcoming Workout (only populate if planned in calendar; otherwise clean metrics-only state)
            val upcoming = plannedList.firstOrNull { it.dateString >= todayStr && !it.isCompleted && !it.isSkipped }
            val nwData = if (upcoming != null) {
                NextWorkoutData(
                    title = upcoming.title,
                    workoutType = upcoming.workoutType,
                    date = upcoming.dateString,
                    dayBadge = upcoming.dateString,
                    countdown = "Upcoming",
                    targetDistanceKm = upcoming.targetDistanceKm,
                    targetPace = upcoming.targetPace,
                    targetHrZone = upcoming.targetHrZone
                )
            } else {
                NextWorkoutData(
                    title = "No Scheduled Workout",
                    workoutType = "Free Run",
                    date = todayStr,
                    dayBadge = "Metrics Only",
                    countdown = "All runs tracked via COROS",
                    targetDistanceKm = 0.0,
                    targetPace = "--",
                    targetHrZone = "Auto"
                )
            }

            // 5. Training Load & 80/20 Zone Spectrum
            val wkRuns = activities.filter { it.dateOnly >= sdf.format(mondayDate) && it.dateOnly <= sdf.format(sundayMillis) }
            val acuteTl = wkRuns.sumOf { it.trainingLoad }
            val easyKm = wkRuns.filter { it.avgHr < 162 }.sumOf { it.distanceKm }
            val aeroPct = if (actualWkKm > 0) ((easyKm / actualWkKm) * 100.0 + 0.5).toInt().coerceIn(30, 95) else 78

            // 6. Post-Run Analysis for Recent Runs
            val lastRunAct = activities.firstOrNull()
            val recentList = mutableListOf<LastRunData>()
            for (act in activities.take(5)) {
                val p = plannedByDate[act.dateOnly]
                val analysis = EvoLabAnalyzer.analyzeRun(
                    run = act,
                    planned = p,
                    lthr = profile.lthr,
                    nextWorkoutTitle = nwData.title,
                    nextWorkoutPace = nwData.targetPace,
                    nextWorkoutZone = nwData.targetHrZone
                )
                recentList.add(
                    LastRunData(
                        name = act.name,
                        date = act.dateOnly,
                        dayLabel = act.dateOnly,
                        distanceKm = act.distanceKm,
                        plannedKm = p?.targetDistanceKm ?: act.distanceKm,
                        adherencePct = if ((p?.targetDistanceKm ?: 0.0) > 0) ((act.distanceKm / p!!.targetDistanceKm) * 100.0).toInt() else 100,
                        verdictBadge = "Completed",
                        durationMinutes = act.durationMinutes,
                        avgPace = act.avgPace,
                        avgHr = act.avgHr,
                        maxHr = act.maxHr,
                        cadence = act.cadence,
                        powerWatts = act.powerWatts,
                        trainingLoad = act.trainingLoad,
                        aerobicTe = act.aerobicTe,
                        anaerobicTe = act.anaerobicTe,
                        aiVerdictHeadline = analysis.headline,
                        aiExecutiveSummary = analysis.summary,
                        aiInsights = analysis.insights,
                        aiTips = analysis.tips
                    )
                )
            }

            // 7. Detect Missed Workout
            val missedEvent = plannedList.firstOrNull {
                it.dateString >= sdf.format(mondayDate) && it.dateString < todayStr && !it.isCompleted && !it.isSkipped && (activitiesByDate[it.dateString]?.isEmpty() ?: true)
            }
            val missedWorkoutData = if (missedEvent != null) {
                val geminiKey = getGeminiKey(context)
                val reasoning = if (geminiKey.isNotBlank()) {
                    GeminiCoachClient.reasonMissedWorkout(
                        apiKey = geminiKey,
                        missedTitle = missedEvent.title,
                        missedDistKm = missedEvent.targetDistanceKm,
                        nextTitle = nwData.title,
                        nextDistKm = nwData.targetDistanceKm,
                        todayDateStr = todayStr,
                        currentTl = acuteTl,
                        lthr = profile.lthr
                    ) ?: EvoLabAnalyzer.reasonMissedWorkout(missedEvent.title, missedEvent.targetDistanceKm, nwData.title, nwData.targetDistanceKm, todayStr)
                } else {
                    EvoLabAnalyzer.reasonMissedWorkout(missedEvent.title, missedEvent.targetDistanceKm, nwData.title, nwData.targetDistanceKm, todayStr)
                }

                MissedWorkoutData(
                    eventId = missedEvent.eventId,
                    title = missedEvent.title,
                    date = missedEvent.dateString,
                    targetDistanceKm = missedEvent.targetDistanceKm,
                    workoutType = missedEvent.workoutType,
                    coachAdvice = reasoning.rationale,
                    options = reasoning.options.map {
                        MissedWorkoutOption(it.label, it.sublabel, it.action, it.targetDate, it.isRecommended)
                    }
                )
            } else null

            val rcData = if (calId != -1L && plannedList.isNotEmpty()) {
                RaceCountdownData()
            } else {
                RaceCountdownData(
                    raceName = "COROS ATHLETE",
                    raceDate = todayStr,
                    daysLeft = 0,
                    phase = "METRICS & LIVE WIDGETS",
                    readinessTag = "OPTIMAL"
                )
            }

            val payload = LapsPayload(
                updatedLabel = SimpleDateFormat("h:mm a", Locale.US).format(Date()),
                athlete = AthleteProfile(profile.nickname, profile.rhr, profile.maxHr, profile.lthr, profile.ltspPace, profile.weightKg),
                weeklyMileage = WeeklyMileageData(
                    actualKm = actualWkKm,
                    plannedKm = plannedWkKm,
                    completionPct = completionPct,
                    runsCompleted = runsDone,
                    runsPlanned = daysMatrix.count { it.plannedKm > 0 },
                    days = daysMatrix
                ),
                trainingLoad = TrainingLoadData(acuteTl = acuteTl, lthr = profile.lthr, rhr = profile.rhr),
                nextWorkout = nwData,
                raceCountdown = rcData,
                zoneSpectrum = ZoneSpectrumData(aerobicPct = aeroPct, thresholdPct = 100 - aeroPct),
                lastRun = recentList.firstOrNull() ?: LastRunData(),
                missedWorkout = missedWorkoutData,
                recentRuns = recentList
            )

            cachedPayload = payload
            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
