package io.github.coderang.laps.data

import android.content.Context
import io.github.coderang.laps.calendar.CalendarSyncManager
import io.github.coderang.laps.coach.EvoLabAnalyzer
import io.github.coderang.laps.coach.GeminiCoachClient
import io.github.coderang.laps.coros.CorosApiClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Central On-Device Repository for Laps.
 * Coordinates direct COROS Training Hub API calls, Android CalendarContract reads/writes,
 * and EvoLab physiological analysis entirely on the device without any server.
 */
object LapsRepository {

    private const val PREFS_NAME = "laps_app_prefs"
    private const val KEY_CACHED_PAYLOAD_JSON = "cached_payload_json"
    private const val KEY_COROS_EMAIL = "coros_email"
    private const val KEY_COROS_PASSWORD = "coros_password"
    private const val KEY_COROS_TOKEN = "coros_access_token"
    private const val KEY_COROS_REGION = "coros_region"
    private const val KEY_CALENDAR_ID = "selected_calendar_id"
    private const val KEY_RACE_NAME = "race_name"
    private const val KEY_RACE_DATE_MILLIS = "race_date_millis"
    private const val KEY_GOAL_TIME = "goal_time"
    private const val KEY_GOAL_PACE = "goal_pace"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val KEY_PLAN_DISTANCE = "active_plan_distance"
    private const val KEY_PLAN_WEEKS = "active_plan_weeks"
    private const val KEY_PLAN_START_MILLIS = "active_plan_start_millis"
    private const val KEY_PLAN_RACE_MILLIS = "active_plan_race_millis"
    private const val KEY_PLAN_CALENDAR_ID = "active_plan_calendar_id"
    private const val KEY_PLAN_CUSTOM_RACE_NAME = "active_plan_custom_race_name"

    fun getActivePlan(context: Context): ActivePlanInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dist = prefs.getString(KEY_PLAN_DISTANCE, null) ?: return null
        val weeks = prefs.getInt(KEY_PLAN_WEEKS, 0)
        val start = prefs.getLong(KEY_PLAN_START_MILLIS, 0L)
        val race = prefs.getLong(KEY_PLAN_RACE_MILLIS, 0L)
        val calId = prefs.getLong(KEY_PLAN_CALENDAR_ID, -1L)
        val customName = prefs.getString(KEY_PLAN_CUSTOM_RACE_NAME, "") ?: ""
        if (weeks <= 0 || start <= 0L || race <= 0L) return null
        return ActivePlanInfo(dist, weeks, start, race, calId, customName)
    }

    fun saveActivePlan(context: Context, plan: ActivePlanInfo) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAN_DISTANCE, plan.distanceLabel)
            .putInt(KEY_PLAN_WEEKS, plan.planWeeks)
            .putLong(KEY_PLAN_START_MILLIS, plan.startDateMillis)
            .putLong(KEY_PLAN_RACE_MILLIS, plan.raceDateMillis)
            .putLong(KEY_PLAN_CALENDAR_ID, plan.calendarId)
            .putString(KEY_PLAN_CUSTOM_RACE_NAME, plan.customRaceName)
            .apply()
    }

    fun clearActivePlan(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PLAN_DISTANCE)
            .remove(KEY_PLAN_WEEKS)
            .remove(KEY_PLAN_START_MILLIS)
            .remove(KEY_PLAN_RACE_MILLIS)
            .remove(KEY_PLAN_CALENDAR_ID)
            .remove(KEY_PLAN_CUSTOM_RACE_NAME)
            .apply()
    }

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

    fun saveCorosToken(context: Context, token: String, region: String? = null) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COROS_TOKEN, token.trim())
            .remove(KEY_COROS_EMAIL)
            .remove(KEY_COROS_PASSWORD)
        if (!region.isNullOrBlank()) {
            editor.putString(KEY_COROS_REGION, region.trim())
        }
        editor.apply()
    }

    fun getCorosCredentials(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(
            prefs.getString(KEY_COROS_EMAIL, "") ?: "",
            prefs.getString(KEY_COROS_PASSWORD, "") ?: ""
        )
    }

    fun saveCorosCredentials(context: Context, email: String, pwd: String, region: String? = null) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COROS_EMAIL, email.trim())
            .putString(KEY_COROS_PASSWORD, pwd.trim())
            .remove(KEY_COROS_TOKEN)
        if (!region.isNullOrBlank()) {
            editor.putString(KEY_COROS_REGION, region.trim())
        }
        editor.apply()
    }

    fun getCorosRegion(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_COROS_REGION, "teamapi.coros.com") ?: "teamapi.coros.com"
    }

    fun saveCorosRegion(context: Context, region: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COROS_REGION, region.trim())
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
            .remove(KEY_COROS_REGION)
            .remove(KEY_GEMINI_KEY)
            .putBoolean(KEY_SETUP_COMPLETED, false)
            .apply()
        resetSamplePayload()
    }

    fun getSelectedCalendarId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_CALENDAR_ID, -1L)
        // If the user has explicitly selected a calendar, always honor it 100%
        if (saved > 0L) return saved

        return try {
            val calendars = CalendarSyncManager.getAvailableCalendars(context)
            val matched = calendars.find { cal ->
                val name = cal.displayName.lowercase(Locale.US)
                name.contains("training") || name.contains("marathon") || name.contains("run")
            }
            if (matched != null) {
                saveSelectedCalendarId(context, matched.id)
                matched.id
            } else -1L
        } catch (_: Exception) {
            -1L
        }
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

    fun getCachedPayload(context: Context? = null): LapsPayload {
        if (!cachedPayload.isRealData && context != null) {
            val disk = loadPayloadFromDisk(context)
            if (disk != null) {
                cachedPayload = disk
            }
        }
        return cachedPayload
    }

    fun setCachedPayload(payload: LapsPayload, context: Context? = null) {
        cachedPayload = payload
        if (payload.isRealData && context != null) {
            savePayloadToDisk(context, payload)
        }
    }

    fun resetSamplePayload(context: Context? = null) {
        cachedPayload = LapsPayload()
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CACHED_PAYLOAD_JSON)
                .apply()
        }
    }

    fun savePayloadToDisk(context: Context, payload: LapsPayload) {
        try {
            val root = JSONObject()
            root.put("updatedLabel", payload.updatedLabel)
            root.put("isMetricsOnly", payload.isMetricsOnly)
            root.put("isRealData", payload.isRealData)

            val ath = JSONObject().apply {
                put("nickname", payload.athlete.nickname)
                put("rhr", payload.athlete.rhr)
                put("maxHr", payload.athlete.maxHr)
                put("lthr", payload.athlete.lthr)
                put("ltspPace", payload.athlete.ltspPace)
                put("weightKg", payload.athlete.weightKg)
            }
            root.put("athlete", ath)

            val wm = JSONObject().apply {
                put("actualKm", payload.weeklyMileage.actualKm)
                put("plannedKm", payload.weeklyMileage.plannedKm)
                put("expectedKmSoFar", payload.weeklyMileage.expectedKmSoFar)
                put("expectedCompletionPct", payload.weeklyMileage.expectedCompletionPct)
                put("remainingKm", payload.weeklyMileage.remainingKm)
                put("completionPct", payload.weeklyMileage.completionPct)
                put("runsCompleted", payload.weeklyMileage.runsCompleted)
                put("runsPlanned", payload.weeklyMileage.runsPlanned)
                put("avgPace", payload.weeklyMileage.avgPace)
                put("totalElevationM", payload.weeklyMileage.totalElevationM)
                val daysArr = JSONArray()
                payload.weeklyMileage.days.forEach { d ->
                    val dObj = JSONObject().apply {
                        put("index", d.index)
                        put("dayLetter", d.dayLetter)
                        put("dayName", d.dayName)
                        put("date", d.date)
                        put("isToday", d.isToday)
                        put("actualKm", d.actualKm)
                        put("plannedKm", d.plannedKm)
                        put("status", d.status)
                        put("workoutType", d.workoutType)
                        put("completionPct", d.completionPct)
                    }
                    daysArr.put(dObj)
                }
                put("days", daysArr)
            }
            root.put("weeklyMileage", wm)

            val tl = JSONObject().apply {
                put("acuteTl", payload.trainingLoad.acuteTl)
                put("weeklyTl", payload.trainingLoad.weeklyTl)
                put("targetTlMin", payload.trainingLoad.targetTlMin)
                put("targetTlMax", payload.trainingLoad.targetTlMax)
                put("loadRatioPct", payload.trainingLoad.loadRatioPct)
                put("statusBadge", payload.trainingLoad.statusBadge)
                put("rhr", payload.trainingLoad.rhr)
                put("lthr", payload.trainingLoad.lthr)
                put("recoveryHours", payload.trainingLoad.recoveryHours)
                put("readinessScore", payload.trainingLoad.readinessScore)
                put("lastAerobicTe", payload.trainingLoad.lastAerobicTe)
                put("lastAnaerobicTe", payload.trainingLoad.lastAnaerobicTe)
            }
            root.put("trainingLoad", tl)

            val nw = JSONObject().apply {
                put("title", payload.nextWorkout.title)
                put("workoutType", payload.nextWorkout.workoutType)
                put("date", payload.nextWorkout.date)
                put("dayBadge", payload.nextWorkout.dayBadge)
                put("countdown", payload.nextWorkout.countdown)
                put("targetDistanceKm", payload.nextWorkout.targetDistanceKm)
                put("targetPace", payload.nextWorkout.targetPace)
                put("targetHrZone", payload.nextWorkout.targetHrZone)
                put("watchSynced", payload.nextWorkout.watchSynced)
            }
            root.put("nextWorkout", nw)

            val rc = JSONObject().apply {
                put("raceName", payload.raceCountdown.raceName)
                put("raceDate", payload.raceCountdown.raceDate)
                put("daysLeft", payload.raceCountdown.daysLeft)
                put("currentWeek", payload.raceCountdown.currentWeek)
                put("totalWeeks", payload.raceCountdown.totalWeeks)
                put("phase", payload.raceCountdown.phase)
                put("goalTime", payload.raceCountdown.goalTime)
                put("goalPace", payload.raceCountdown.goalPace)
                put("currentLtsp", payload.raceCountdown.currentLtsp)
                put("currentLthr", payload.raceCountdown.currentLthr)
                put("paceDeltaSec", payload.raceCountdown.paceDeltaSec)
                put("readinessTag", payload.raceCountdown.readinessTag)
            }
            root.put("raceCountdown", rc)

            val zs = JSONObject().apply {
                put("aerobicPct", payload.zoneSpectrum.aerobicPct)
                put("thresholdPct", payload.zoneSpectrum.thresholdPct)
                put("targetRatio", payload.zoneSpectrum.targetRatio)
                put("easyAvgHr", payload.zoneSpectrum.easyAvgHr)
                put("qualityAvgHr", payload.zoneSpectrum.qualityAvgHr)
                put("z2CeilingHr", payload.zoneSpectrum.z2CeilingHr)
                put("zoneCreepStatus", payload.zoneSpectrum.zoneCreepStatus)
            }
            root.put("zoneSpectrum", zs)

            fun runToJson(r: LastRunData): JSONObject = JSONObject().apply {
                put("name", r.name)
                put("date", r.date)
                put("dayLabel", r.dayLabel)
                put("distanceKm", r.distanceKm)
                put("plannedKm", r.plannedKm)
                put("adherencePct", r.adherencePct)
                put("verdictBadge", r.verdictBadge)
                put("durationMinutes", r.durationMinutes)
                put("avgPace", r.avgPace)
                put("avgHr", r.avgHr)
                put("maxHr", r.maxHr)
                put("cadence", r.cadence)
                put("maxCadence", r.maxCadence)
                put("powerWatts", r.powerWatts)
                put("maxPowerWatts", r.maxPowerWatts)
                put("strideLengthCm", r.strideLengthCm)
                put("trainingLoad", r.trainingLoad)
                put("aerobicTe", r.aerobicTe)
                put("anaerobicTe", r.anaerobicTe)
                put("vo2Max", r.vo2Max)
                put("stamina7d", r.stamina7d)
                put("bestKmPace", r.bestKmPace)
                put("elevGainM", r.elevGainM)
                put("caloriesKcal", r.caloriesKcal)
                put("physiologyNote", r.physiologyNote)
                put("coachGuidance", r.coachGuidance)
                put("aiVerdictHeadline", r.aiVerdictHeadline)
                put("aiExecutiveSummary", r.aiExecutiveSummary)
            }
            root.put("lastRun", runToJson(payload.lastRun))
            val runsArr = JSONArray()
            payload.recentRuns.forEach { runsArr.put(runToJson(it)) }
            root.put("recentRuns", runsArr)

            if (payload.activePlan != null) {
                val ap = JSONObject().apply {
                    put("distanceLabel", payload.activePlan.distanceLabel)
                    put("planWeeks", payload.activePlan.planWeeks)
                    put("startDateMillis", payload.activePlan.startDateMillis)
                    put("raceDateMillis", payload.activePlan.raceDateMillis)
                    put("calendarId", payload.activePlan.calendarId)
                    put("customRaceName", payload.activePlan.customRaceName)
                }
                root.put("activePlan", ap)
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_PAYLOAD_JSON, root.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("LapsRepo", "Failed to cache payload to disk", e)
        }
    }

    fun loadPayloadFromDisk(context: Context): LapsPayload? {
        return try {
            val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CACHED_PAYLOAD_JSON, null) ?: return null
            val root = JSONObject(jsonStr)
            val updatedLabel = root.optString("updatedLabel", "Just now")
            val isMetricsOnly = root.optBoolean("isMetricsOnly", false)
            val isRealData = root.optBoolean("isRealData", false)
            if (!isRealData) return null

            val athObj = root.optJSONObject("athlete")
            val athlete = if (athObj != null) {
                AthleteProfile(
                    nickname = athObj.optString("nickname", "Runner"),
                    rhr = athObj.optInt("rhr", 44),
                    maxHr = athObj.optInt("maxHr", 195),
                    lthr = athObj.optInt("lthr", 172),
                    ltspPace = athObj.optString("ltspPace", "4:56 /km"),
                    weightKg = athObj.optDouble("weightKg", 75.0)
                )
            } else AthleteProfile()

            val wmObj = root.optJSONObject("weeklyMileage")
            val weeklyMileage = if (wmObj != null) {
                val daysArr = wmObj.optJSONArray("days")
                val daysList = mutableListOf<DayBarData>()
                if (daysArr != null) {
                    for (i in 0 until daysArr.length()) {
                        val d = daysArr.getJSONObject(i)
                        daysList.add(
                            DayBarData(
                                index = d.optInt("index", i),
                                dayLetter = d.optString("dayLetter", ""),
                                dayName = d.optString("dayName", ""),
                                date = d.optString("date", ""),
                                isToday = d.optBoolean("isToday", false),
                                actualKm = d.optDouble("actualKm", 0.0),
                                plannedKm = d.optDouble("plannedKm", 0.0),
                                status = d.optString("status", ""),
                                workoutType = d.optString("workoutType", ""),
                                completionPct = d.optInt("completionPct", 0)
                            )
                        )
                    }
                }
                WeeklyMileageData(
                    actualKm = wmObj.optDouble("actualKm", 0.0),
                    plannedKm = wmObj.optDouble("plannedKm", 0.0),
                    expectedKmSoFar = wmObj.optDouble("expectedKmSoFar", 0.0),
                    expectedCompletionPct = wmObj.optInt("expectedCompletionPct", 0),
                    remainingKm = wmObj.optDouble("remainingKm", 0.0),
                    completionPct = wmObj.optInt("completionPct", 0),
                    runsCompleted = wmObj.optInt("runsCompleted", 0),
                    runsPlanned = wmObj.optInt("runsPlanned", 0),
                    avgPace = wmObj.optString("avgPace", "--:-- /km"),
                    totalElevationM = wmObj.optInt("totalElevationM", 0),
                    days = if (daysList.isNotEmpty()) daysList else WeeklyMileageData.defaultDays()
                )
            } else WeeklyMileageData()

            val tlObj = root.optJSONObject("trainingLoad")
            val trainingLoad = if (tlObj != null) {
                TrainingLoadData(
                    acuteTl = tlObj.optInt("acuteTl", 0),
                    weeklyTl = tlObj.optInt("weeklyTl", 0),
                    targetTlMin = tlObj.optInt("targetTlMin", 0),
                    targetTlMax = tlObj.optInt("targetTlMax", 0),
                    loadRatioPct = tlObj.optInt("loadRatioPct", 0),
                    statusBadge = tlObj.optString("statusBadge", "OPTIMAL"),
                    rhr = tlObj.optInt("rhr", 0),
                    lthr = tlObj.optInt("lthr", 0),
                    recoveryHours = tlObj.optInt("recoveryHours", 0),
                    readinessScore = tlObj.optInt("readinessScore", 0),
                    lastAerobicTe = tlObj.optDouble("lastAerobicTe", 0.0),
                    lastAnaerobicTe = tlObj.optDouble("lastAnaerobicTe", 0.0)
                )
            } else TrainingLoadData()

            val nwObj = root.optJSONObject("nextWorkout")
            val nextWorkout = if (nwObj != null) {
                NextWorkoutData(
                    title = nwObj.optString("title", ""),
                    workoutType = nwObj.optString("workoutType", ""),
                    date = nwObj.optString("date", ""),
                    dayBadge = nwObj.optString("dayBadge", ""),
                    countdown = nwObj.optString("countdown", ""),
                    targetDistanceKm = nwObj.optDouble("targetDistanceKm", 0.0),
                    targetPace = nwObj.optString("targetPace", ""),
                    targetHrZone = nwObj.optString("targetHrZone", ""),
                    watchSynced = nwObj.optBoolean("watchSynced", false)
                )
            } else NextWorkoutData()

            val rcObj = root.optJSONObject("raceCountdown")
            val raceCountdown = if (rcObj != null) {
                RaceCountdownData(
                    raceName = rcObj.optString("raceName", ""),
                    raceDate = rcObj.optString("raceDate", ""),
                    daysLeft = rcObj.optInt("daysLeft", 0),
                    currentWeek = rcObj.optInt("currentWeek", 0),
                    totalWeeks = rcObj.optInt("totalWeeks", 0),
                    phase = rcObj.optString("phase", ""),
                    goalTime = rcObj.optString("goalTime", ""),
                    goalPace = rcObj.optString("goalPace", ""),
                    currentLtsp = rcObj.optString("currentLtsp", ""),
                    currentLthr = rcObj.optInt("currentLthr", 0),
                    paceDeltaSec = rcObj.optInt("paceDeltaSec", 0),
                    readinessTag = rcObj.optString("readinessTag", "")
                )
            } else RaceCountdownData()

            val zsObj = root.optJSONObject("zoneSpectrum")
            val zoneSpectrum = if (zsObj != null) {
                ZoneSpectrumData(
                    aerobicPct = zsObj.optInt("aerobicPct", 80),
                    thresholdPct = zsObj.optInt("thresholdPct", 20),
                    targetRatio = zsObj.optString("targetRatio", "80 / 20"),
                    easyAvgHr = zsObj.optInt("easyAvgHr", 0),
                    qualityAvgHr = zsObj.optInt("qualityAvgHr", 0),
                    z2CeilingHr = zsObj.optInt("z2CeilingHr", 0),
                    zoneCreepStatus = zsObj.optString("zoneCreepStatus", "OPTIMAL")
                )
            } else ZoneSpectrumData()

            fun runFromObj(ro: JSONObject): LastRunData {
                return LastRunData(
                    name = ro.optString("name", "Run"),
                    date = ro.optString("date", ""),
                    dayLabel = ro.optString("dayLabel", ""),
                    distanceKm = ro.optDouble("distanceKm", 0.0),
                    plannedKm = ro.optDouble("plannedKm", 0.0),
                    adherencePct = ro.optInt("adherencePct", 0),
                    verdictBadge = ro.optString("verdictBadge", ""),
                    durationMinutes = ro.optDouble("durationMinutes", 0.0),
                    avgPace = ro.optString("avgPace", "--:--"),
                    avgHr = ro.optInt("avgHr", 0),
                    maxHr = ro.optInt("maxHr", 0),
                    cadence = ro.optInt("cadence", 0),
                    maxCadence = ro.optInt("maxCadence", 0),
                    powerWatts = ro.optInt("powerWatts", 0),
                    maxPowerWatts = ro.optInt("maxPowerWatts", 0),
                    strideLengthCm = ro.optInt("strideLengthCm", 0),
                    trainingLoad = ro.optInt("trainingLoad", 0),
                    aerobicTe = ro.optDouble("aerobicTe", 0.0),
                    anaerobicTe = ro.optDouble("anaerobicTe", 0.0),
                    vo2Max = ro.optInt("vo2Max", 0),
                    stamina7d = ro.optDouble("stamina7d", 0.0),
                    bestKmPace = ro.optString("bestKmPace", "--:--"),
                    elevGainM = ro.optInt("elevGainM", 0),
                    caloriesKcal = ro.optInt("caloriesKcal", 0),
                    physiologyNote = ro.optString("physiologyNote", ""),
                    coachGuidance = ro.optString("coachGuidance", ""),
                    aiVerdictHeadline = ro.optString("aiVerdictHeadline", ""),
                    aiExecutiveSummary = ro.optString("aiExecutiveSummary", "")
                )
            }

            val lastRunObj = root.optJSONObject("lastRun")
            val lastRun = if (lastRunObj != null) runFromObj(lastRunObj) else LastRunData.empty()

            val recentArr = root.optJSONArray("recentRuns")
            val recentList = mutableListOf<LastRunData>()
            if (recentArr != null) {
                for (i in 0 until recentArr.length()) {
                    recentList.add(runFromObj(recentArr.getJSONObject(i)))
                }
            }

            val apObj = root.optJSONObject("activePlan")
            val activePlan = if (apObj != null) {
                ActivePlanInfo(
                    distanceLabel = apObj.optString("distanceLabel", "Half Marathon"),
                    planWeeks = apObj.optInt("planWeeks", 10),
                    startDateMillis = apObj.optLong("startDateMillis", 0L),
                    raceDateMillis = apObj.optLong("raceDateMillis", 0L),
                    calendarId = apObj.optLong("calendarId", -1L),
                    customRaceName = apObj.optString("customRaceName", "")
                )
            } else null

            LapsPayload(
                updatedLabel = updatedLabel,
                athlete = athlete,
                weeklyMileage = weeklyMileage,
                trainingLoad = trainingLoad,
                nextWorkout = nextWorkout,
                zoneSpectrum = zoneSpectrum,
                raceCountdown = raceCountdown,
                lastRun = lastRun,
                missedWorkout = null,
                activePlan = activePlan,
                recentRuns = if (recentList.isNotEmpty()) recentList else listOf(lastRun),
                isMetricsOnly = isMetricsOnly,
                isRealData = true
            )
        } catch (e: Exception) {
            android.util.Log.e("LapsRepo", "Failed to load payload from disk", e)
            null
        }
    }

    fun syncOnDevice(context: Context): Result<LapsPayload> {
        val token = getCorosToken(context)
        val (email, password) = getCorosCredentials(context)
        val savedRegion = getCorosRegion(context)
        if (token.isBlank() && (email.isBlank() || password.isBlank())) {
            return Result.success(getCachedPayload(context)) // Demo fallback state
        }

        return try {
            val coros = CorosApiClient(region = savedRegion)
            val profile = if (token.isNotBlank()) {
                val tokenResult = coros.authenticateWithToken(token)
                if (tokenResult.isFailure) {
                    // Gracefully fallback to email/pwd if token is stale/invalid
                    if (email.isNotBlank() && password.isNotBlank()) {
                        val loginResult = coros.login(email, password)
                        if (loginResult.isFailure) {
                            return Result.failure(loginResult.exceptionOrNull() ?: IllegalStateException("COROS authentication failed"))
                        }
                        if (coros.region != savedRegion) {
                            saveCorosRegion(context, coros.region)
                        }
                        loginResult.getOrNull() ?: CorosApiClient.CorosProfile()
                    } else {
                        return Result.failure(tokenResult.exceptionOrNull() ?: IllegalStateException("COROS token authentication failed"))
                    }
                } else {
                    if (coros.region != savedRegion) {
                        saveCorosRegion(context, coros.region)
                    }
                    tokenResult.getOrNull() ?: CorosApiClient.CorosProfile(nickname = "COROS Athlete")
                }
            } else {
                val loginResult = coros.login(email, password)
                if (loginResult.isFailure) {
                    return Result.failure(loginResult.exceptionOrNull() ?: IllegalStateException("COROS login failed"))
                }
                if (coros.region != savedRegion) {
                    saveCorosRegion(context, coros.region)
                }
                loginResult.getOrNull() ?: CorosApiClient.CorosProfile()
            }

            val actResult = coros.fetchActivities(limit = 25)
            if (actResult.isFailure) {
                return Result.failure(actResult.exceptionOrNull() ?: IllegalStateException("Failed to fetch COROS activities"))
            }
            val activities = actResult.getOrNull() ?: emptyList()

            // 1. Calculate current week (Monday..Sunday)
            val cal = Calendar.getInstance()
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
            cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val mondayDate = cal.time
            val mondayMillis = cal.timeInMillis

            val sunCal = Calendar.getInstance().apply {
                time = mondayDate
                add(Calendar.DAY_OF_YEAR, 6)
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            val sundayDate = sunCal.time
            val sundayMillis = sunCal.timeInMillis
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = sdf.format(Date())
            val mondayStr = sdf.format(mondayDate)
            val sundayStr = sdf.format(sundayDate)

            // 2. Query Calendar for Planned Workouts (COROS Training Hub Calendar & local Google Calendar)
            val calId = getSelectedCalendarId(context)
            val activePlan = getActivePlan(context)
            val hasConfiguredGCalPlan = activePlan != null && activePlan.calendarId == calId && calId != -1L

            // Fetch COROS Training Hub Calendar Schedule
            val calStart = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -14) }
            val calEnd = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 70) }
            val startDayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(calStart.time)
            val endDayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(calEnd.time)

            val corosScheduleResult = coros.fetchSchedule(startDayStr, endDayStr)
            val corosSchedule = corosScheduleResult.getOrNull()
            val corosWorkouts = corosSchedule?.workouts ?: emptyList()
            val corosWorkoutsByDate = corosWorkouts.associateBy { it.dateStr }

            // Local Google Calendar planned workouts
            val gcalPlannedList = if (hasConfiguredGCalPlan) {
                CalendarSyncManager.getPlannedWorkouts(
                    context = context,
                    calendarId = calId,
                    startMillis = Math.max(mondayMillis - 86400000L, activePlan.startDateMillis),
                    endMillis = Math.min(sundayMillis + 864000000L, activePlan.raceDateMillis + 86400000L)
                ).filter { it.startMillis >= activePlan.startDateMillis }
            } else if (calId != -1L) {
                CalendarSyncManager.getPlannedWorkouts(
                    context = context,
                    calendarId = calId,
                    startMillis = mondayMillis - (7 * 86400000L),
                    endMillis = sundayMillis + (180 * 86400000L)
                )
            } else emptyList()
            val gcalPlannedByDate = gcalPlannedList.associateBy { it.dateString }

            val hasCorosPlan = corosSchedule != null && (corosSchedule.planName.isNotBlank() || corosWorkouts.isNotEmpty())
            val hasActivePlanThisWeek = hasConfiguredGCalPlan || hasCorosPlan || gcalPlannedList.isNotEmpty()
            val isMetricsOnly = !hasActivePlanThisWeek

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
                val dayActualKm = dayActs.sumOf { it.distanceKm }

                // Planned distance from COROS calendar first, then Google Calendar
                val corosWk = corosWorkoutsByDate[dStr]
                val gcalWk = gcalPlannedByDate[dStr]
                val dayPlannedKm = if (corosWk != null && corosWk.distanceKm > 0) {
                    corosWk.distanceKm
                } else {
                    gcalWk?.targetDistanceKm ?: 0.0
                }

                actualWkKm += dayActualKm
                plannedWkKm += dayPlannedKm
                if (dayActualKm > 0) runsDone++

                val isToday = dStr == todayStr
                val status = when {
                    dayActualKm > 0 -> "COMPLETED"
                    isToday && dayPlannedKm > 0 -> "TODAY"
                    hasActivePlanThisWeek && dStr > todayStr && dayPlannedKm > 0 -> "PLANNED"
                    hasActivePlanThisWeek && dStr < todayStr && dayPlannedKm > 0 -> "MISSED"
                    else -> "REST"
                }

                val workoutTitle = when {
                    dayActs.isNotEmpty() -> dayActs.first().name
                    corosWk != null -> corosWk.name
                    gcalWk != null -> gcalWk.title.replace("✅", "").trim()
                    else -> "Rest"
                }

                daysMatrix.add(
                    DayBarData(
                        index = i,
                        dayLetter = dayLetters[i],
                        dayName = dayNames[i],
                        date = dStr,
                        isToday = isToday,
                        actualKm = ((dayActualKm * 100).toInt()) / 100.0,
                        plannedKm = ((dayPlannedKm * 100).toInt()) / 100.0,
                        status = status,
                        workoutType = workoutTitle
                    )
                )
                iterCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            actualWkKm = ((actualWkKm * 100).toInt()) / 100.0
            plannedWkKm = if (hasActivePlanThisWeek) ((plannedWkKm * 100).toInt()) / 100.0 else 0.0
            val completionPct = if (plannedWkKm > 0) ((actualWkKm / plannedWkKm) * 100.0 + 0.5).toInt() else 0

            // 4. Upcoming Workout (from COROS calendar first, then Google Calendar)
            val upcomingCoros = corosWorkouts.firstOrNull { it.dateStr >= todayStr && !it.isCompleted }
            val upcomingGCal = gcalPlannedList.firstOrNull { it.dateString >= todayStr && !it.isCompleted && !it.isSkipped }

            val nwData = if (upcomingCoros != null) {
                val nowTime = System.currentTimeMillis()
                val targetMillis = try {
                    sdf.parse(upcomingCoros.dateStr)?.time ?: nowTime
                } catch (_: Exception) { nowTime }
                val daysUntil = ((targetMillis - nowTime) / 86400000L).toInt()
                val countdownLabel = when {
                    daysUntil <= 0 -> "Today"
                    daysUntil == 1 -> "Tomorrow"
                    else -> "In $daysUntil days"
                }
                val badgeFmt = try {
                    SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(targetMillis))
                } catch (_: Exception) { upcomingCoros.dateStr }

                NextWorkoutData(
                    title = upcomingCoros.name,
                    workoutType = upcomingCoros.workoutType,
                    date = upcomingCoros.dateStr,
                    dayBadge = badgeFmt,
                    countdown = countdownLabel,
                    targetDistanceKm = upcomingCoros.distanceKm,
                    targetPace = if (upcomingCoros.targetPace.isNotBlank()) upcomingCoros.targetPace else profile.ltspPace,
                    targetHrZone = if (upcomingCoros.targetHrZone.isNotBlank()) upcomingCoros.targetHrZone else "Zone 2"
                )
            } else if (upcomingGCal != null) {
                val nowTime = System.currentTimeMillis()
                val daysUntil = ((upcomingGCal.startMillis - nowTime) / 86400000L).toInt()
                val countdownLabel = when {
                    daysUntil <= 0 -> "Today"
                    daysUntil == 1 -> "Tomorrow"
                    else -> "In $daysUntil days"
                }
                val badgeFmt = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(upcomingGCal.startMillis))

                NextWorkoutData(
                    title = upcomingGCal.title,
                    workoutType = upcomingGCal.workoutType,
                    date = upcomingGCal.dateString,
                    dayBadge = badgeFmt,
                    countdown = countdownLabel,
                    targetDistanceKm = upcomingGCal.targetDistanceKm,
                    targetPace = upcomingGCal.targetPace,
                    targetHrZone = upcomingGCal.targetHrZone
                )
            } else {
                NextWorkoutData(
                    title = "No Scheduled Workout",
                    workoutType = "Free Run",
                    date = todayStr,
                    dayBadge = "Live Sync",
                    countdown = "All runs tracked via COROS",
                    targetDistanceKm = 0.0,
                    targetPace = "--",
                    targetHrZone = "Auto"
                )
            }

            // 5. Training Load & 80/20 Zone Spectrum (7-day rolling window for Acute TL)
            val nowSec = System.currentTimeMillis() / 1000L
            val sevenDaysAgoSec = nowSec - (7 * 86400L)
            val rolling7dRuns = activities.filter { it.timestamp >= sevenDaysAgoSec }
            val wkRuns = activities.filter { it.dateOnly in mondayStr..sundayStr }
            val acuteTl = if (rolling7dRuns.isNotEmpty()) rolling7dRuns.sumOf { it.trainingLoad } else wkRuns.sumOf { it.trainingLoad }
            val totalWeekSec = wkRuns.sumOf { it.durationSeconds }
            val wkAvgPace = if (actualWkKm > 0 && totalWeekSec > 0) {
                val secPerKm = (totalWeekSec / actualWkKm).toInt()
                "%d:%02d /km".format(secPerKm / 60, secPerKm % 60)
            } else "--:--"
            val totalElev = wkRuns.sumOf { it.elevGainM }.toInt()
            val easyKm = wkRuns.filter { it.avgHr < (profile.lthr * 0.901).toInt() }.sumOf { it.distanceKm }
            val aeroPct = if (actualWkKm > 0) ((easyKm / actualWkKm) * 100.0 + 0.5).toInt().coerceIn(30, 95) else 78

            val targetTlMax = 580
            val targetTlMin = 380
            val tlStatus = when {
                acuteTl > targetTlMax * 1.1 -> "OVERREACHING"
                acuteTl >= targetTlMin * 0.65 -> "PRODUCTIVE"
                acuteTl > 0 -> "BUILDING"
                else -> "RESTING"
            }
            val loadRatio = if (targetTlMax > 0) ((acuteTl.toDouble() / targetTlMax.toDouble()) * 100.0).toInt().coerceIn(0, 100) else 0

            val lastRunAct = activities.firstOrNull()
            val lastRunTs = lastRunAct?.timestamp ?: 0L
            val elapsedHours = if (lastRunTs > 0) Math.max(0.0, (System.currentTimeMillis() / 1000.0 - lastRunTs) / 3600.0) else 0.0
            val remainingRecoveryH = Math.max(2, (Math.max(0.0, 18.0 - elapsedHours) + 0.5).toInt())
            val readinessScore = Math.max(65, Math.min(98, 94 - (remainingRecoveryH * 0.6).toInt()))

            // 6. Post-Run Analysis for Recent Runs
            val recentList = mutableListOf<LastRunData>()
            for ((index, act) in activities.take(5).withIndex()) {
                val gcalWk = gcalPlannedByDate[act.dateOnly]
                val corosWk = corosWorkoutsByDate[act.dateOnly]
                val p = gcalWk ?: if (corosWk != null && corosWk.distanceKm > 0) {
                    CalendarSyncManager.PlannedWorkout(
                        eventId = -1L,
                        title = corosWk.name,
                        description = corosWk.name,
                        startMillis = 0L,
                        dateString = corosWk.dateStr,
                        targetDistanceKm = corosWk.distanceKm,
                        workoutType = corosWk.workoutType,
                        targetPace = corosWk.targetPace,
                        targetHrZone = corosWk.targetHrZone,
                        isCompleted = corosWk.isCompleted,
                        isSkipped = false
                    )
                } else null

                val detail = if (index == 0) coros.fetchActivityDetail(act.activityId, act.sportType).getOrNull() else null

                val maxHr = if (detail != null && detail.maxHr > 0) detail.maxHr else act.maxHr
                val maxPower = detail?.maxPowerWatts ?: 0
                val maxCad = detail?.maxCadence ?: 0
                val strideCm = detail?.strideLengthCm ?: 0
                val vo2Max = detail?.vo2Max ?: 51
                val stamina = detail?.stamina7d ?: 96.0
                val bestKm = detail?.bestKmPace ?: "--:--"
                val aeroTe = if (detail != null && detail.aerobicTe > 0.0) detail.aerobicTe else act.aerobicTe
                val anaeroTe = if (detail != null && detail.anaerobicTe > 0.0) detail.anaerobicTe else act.anaerobicTe
                val elevM = if (detail != null && detail.elevGainM > 0) detail.elevGainM else act.elevGainM.toInt()
                val kcal = if (detail != null && detail.caloriesKcal > 0) detail.caloriesKcal else act.caloriesKcal

                val analysis = EvoLabAnalyzer.analyzeRun(
                    run = act.copy(maxHr = maxHr, aerobicTe = aeroTe, anaerobicTe = anaeroTe),
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
                        maxHr = maxHr,
                        cadence = act.cadence,
                        maxCadence = maxCad,
                        powerWatts = act.powerWatts,
                        maxPowerWatts = maxPower,
                        strideLengthCm = strideCm,
                        trainingLoad = act.trainingLoad,
                        aerobicTe = aeroTe,
                        anaerobicTe = anaeroTe,
                        vo2Max = vo2Max,
                        stamina7d = stamina,
                        bestKmPace = bestKm,
                        elevGainM = elevM,
                        caloriesKcal = kcal,
                        aiVerdictHeadline = analysis.headline,
                        aiExecutiveSummary = analysis.summary,
                        aiInsights = analysis.insights,
                        aiTips = analysis.tips,
                        hrZones = detail?.hrZones ?: emptyList(),
                        splits = detail?.splits ?: emptyList()
                    )
                )
            }

            // 7. Detect Missed Workout (only if user has a structured training plan active this week)
            val missedEvent = if (hasActivePlanThisWeek) {
                gcalPlannedList.firstOrNull {
                    it.dateString >= mondayStr && it.dateString < todayStr && !it.isCompleted && !it.isSkipped && (activitiesByDate[it.dateString]?.isEmpty() ?: true)
                }
            } else null
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

            val raceEvent = if (calId != -1L) {
                CalendarSyncManager.findRaceEvent(
                    context = context,
                    calendarId = calId,
                    fromMillis = if (hasConfiguredGCalPlan) activePlan.startDateMillis - 86400000L else System.currentTimeMillis() - 86400000L
                )
            } else null

            val isCorosPlanFuture = corosSchedule != null && corosSchedule.endDayInt > 0 && run {
                val endDay = corosSchedule.endDayInt
                val endY = endDay / 10000
                val endM = (endDay % 10000) / 100
                val endD = endDay % 100
                val raceTargetCal = Calendar.getInstance().apply {
                    set(endY, endM - 1, endD, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                raceTargetCal.timeInMillis >= todayCal.timeInMillis
            }

            val cleanRaceTitle = raceEvent?.title
                ?.replace("🏁 RACE DAY: ", "")
                ?.replace("🏁 RACE DAY:", "")
                ?.replace("🏁 ", "")
                ?.trim()
                ?.ifBlank { "RACE DAY" } ?: "RACE DAY"

            val maxWeekInCal = gcalPlannedList.mapNotNull {
                Regex("W(\\d+)").find(it.title)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull() ?: 10
            val totalWeeks = if (maxWeekInCal > 0) maxWeekInCal else 10

            var currentWeek = 0
            val thisWeekWorkout = gcalPlannedList.firstOrNull { it.startMillis in mondayMillis..sundayMillis }
            val weekMatch = if (thisWeekWorkout != null) Regex("W(\\d+)").find(thisWeekWorkout.title) else null
            if (weekMatch != null) {
                currentWeek = weekMatch.groupValues[1].toIntOrNull() ?: 0
            }

            val rcData = if (isCorosPlanFuture && corosSchedule != null) {
                val endDay = corosSchedule.endDayInt
                val endY = endDay / 10000
                val endM = (endDay % 10000) / 100
                val endD = endDay % 100
                val raceTargetCal = Calendar.getInstance().apply {
                    set(endY, endM - 1, endD, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffDays = ((raceTargetCal.timeInMillis - todayCal.timeInMillis) / 86400000L).toInt().coerceAtLeast(0)
                val weeksLeft = (diffDays / 7) + (if (diffDays % 7 > 0) 1 else 0)

                val rawName = corosSchedule.planName.trim()
                val planName = when {
                    rawName.isBlank() -> "COROS TARGET RACE"
                    rawName.matches(Regex("^[Ss][0-9]+$")) -> "COROS TARGET RACE"
                    else -> rawName.uppercase()
                }
                val raceDateStr = "%04d-%02d-%02d".format(endY, endM, endD)

                RaceCountdownData(
                    raceName = planName,
                    raceDate = raceDateStr,
                    daysLeft = diffDays,
                    currentWeek = if (weeksLeft > 0) weeksLeft else 0,
                    totalWeeks = if (weeksLeft > 0) weeksLeft else 0,
                    phase = if (diffDays in 1..42) "$weeksLeft WEEKS TO RACE · PEAK PREP" else "COROS TRAINING PLAN",
                    goalTime = "Race Ready",
                    goalPace = profile.ltspPace.ifBlank { "4:56 /km" },
                    currentLtsp = profile.ltspPace.ifBlank { "4:56 /km" },
                    currentLthr = profile.lthr,
                    readinessTag = if (diffDays <= 14) "PEAK TAPER" else "ON TRACK"
                )
            } else if (hasConfiguredGCalPlan) {
                val nowCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStartMillis = nowCal.timeInMillis

                val raceCal = Calendar.getInstance().apply {
                    timeInMillis = activePlan.raceDateMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val raceTargetMillis = raceCal.timeInMillis
                val diffDays = ((raceTargetMillis - todayStartMillis) / 86400000L).toInt().coerceAtLeast(0)

                val totalWeeks = activePlan.planWeeks
                val planStartCal = Calendar.getInstance().apply {
                    timeInMillis = activePlan.startDateMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val planStartMillis = planStartCal.timeInMillis

                val isPrePlan = todayStartMillis < planStartMillis
                val currentWeek = if (isPrePlan) {
                    0
                } else {
                    (((todayStartMillis - planStartMillis) / (7 * 86400000L)).toInt() + 1).coerceIn(1, totalWeeks)
                }

                val daysUntilStart = ((planStartMillis - todayStartMillis) / 86400000L).toInt().coerceAtLeast(0)

                val phaseName = when {
                    isPrePlan -> "PRE-PLAN BASE · STARTS IN $daysUntilStart DAYS"
                    currentWeek >= totalWeeks - 1 -> "TAPER & RACE READINESS"
                    currentWeek >= (totalWeeks * 0.7).toInt() -> "PEAK AEROBIC VOLUME"
                    currentWeek >= (totalWeeks * 0.4).toInt() -> "THRESHOLD & TEMPO BUILD"
                    else -> "AEROBIC BASE FOUNDATION"
                }

                val distKey = activePlan.distanceLabel.uppercase()
                val raceName = if (activePlan.customRaceName.isNotBlank()) {
                    activePlan.customRaceName.trim().uppercase()
                } else {
                    distKey
                }
                val raceDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(raceTargetMillis))

                RaceCountdownData(
                    raceName = raceName,
                    raceDate = raceDateStr,
                    daysLeft = diffDays,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    phase = phaseName,
                    goalTime = when {
                        distKey.contains("5K") -> "0:24:00"
                        distKey.contains("10K") -> "0:49:30"
                        distKey.contains("HALF") -> "1:45:00"
                        distKey.contains("MARATHON") -> "3:40:00"
                        else -> "1:45:00"
                    },
                    goalPace = when {
                        distKey.contains("5K") -> "4:48 /km"
                        distKey.contains("10K") -> "4:57 /km"
                        distKey.contains("HALF") -> "4:58 /km"
                        distKey.contains("MARATHON") -> "5:12 /km"
                        else -> "4:58 /km"
                    },
                    currentLtsp = profile.ltspPace.ifBlank { "4:56 /km" },
                    currentLthr = profile.lthr,
                    readinessTag = if (diffDays <= 7) "PEAK TAPER" else "ON TRACK"
                )
            } else if (raceEvent != null && calId != -1L) {
                val nowCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val raceCal = Calendar.getInstance().apply {
                    timeInMillis = raceEvent.startMillis
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val diffDays = ((raceCal.timeInMillis - nowCal.timeInMillis) / 86400000L).toInt().coerceAtLeast(0)
                if (currentWeek == 0) {
                    currentWeek = (totalWeeks - (diffDays / 7)).coerceIn(1, totalWeeks)
                }

                RaceCountdownData(
                    raceName = cleanRaceTitle.uppercase(),
                    raceDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(raceEvent.startMillis)),
                    daysLeft = diffDays,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    phase = if (currentWeek > 0) "WEEK $currentWeek OF $totalWeeks · AEROBIC + THRESHOLD" else "CALENDAR RACE · $diffDays DAYS LEFT",
                    goalTime = when {
                        cleanRaceTitle.contains("5K", ignoreCase = true) -> "0:24:00"
                        cleanRaceTitle.contains("10K", ignoreCase = true) -> "0:49:30"
                        cleanRaceTitle.contains("HALF", ignoreCase = true) -> "1:45:00"
                        cleanRaceTitle.contains("MARATHON", ignoreCase = true) -> "3:40:00"
                        else -> "1:45:00"
                    },
                    goalPace = when {
                        cleanRaceTitle.contains("5K", ignoreCase = true) -> "4:48 /km"
                        cleanRaceTitle.contains("10K", ignoreCase = true) -> "4:57 /km"
                        cleanRaceTitle.contains("HALF", ignoreCase = true) -> "4:58 /km"
                        cleanRaceTitle.contains("MARATHON", ignoreCase = true) -> "5:12 /km"
                        else -> "4:58 /km"
                    },
                    currentLtsp = profile.ltspPace.ifBlank { "4:56 /km" },
                    currentLthr = profile.lthr,
                    readinessTag = if (diffDays <= 7) "PEAK TAPER" else "OPTIMAL"
                )
            } else {
                RaceCountdownData(
                    raceName = "COROS ATHLETE",
                    raceDate = todayStr,
                    daysLeft = 0,
                    currentWeek = 0,
                    totalWeeks = 0,
                    phase = "METRICS & LIVE WIDGETS",
                    readinessTag = "OPTIMAL"
                )
            }

            val lastRunData = recentList.firstOrNull() ?: LastRunData.empty()

            val effectivePlan = when {
                hasConfiguredGCalPlan -> activePlan
                raceEvent != null && calId != -1L -> {
                    val totalWeeks = if (maxWeekInCal > 0) maxWeekInCal else 10
                    val startMillis = mondayMillis - ((currentWeek.coerceAtLeast(1) - 1) * 7 * 86400000L)
                    ActivePlanInfo(
                        distanceLabel = if (cleanRaceTitle.contains("MARATHON", ignoreCase = true) && !cleanRaceTitle.contains("HALF", ignoreCase = true)) "Marathon" else "Half Marathon",
                        planWeeks = totalWeeks,
                        startDateMillis = startMillis,
                        raceDateMillis = raceEvent.startMillis,
                        calendarId = calId,
                        customRaceName = cleanRaceTitle
                    )
                }
                corosSchedule != null && corosSchedule.planName.isNotBlank() -> {
                    ActivePlanInfo(
                        distanceLabel = corosSchedule.planName,
                        planWeeks = 10,
                        startDateMillis = mondayMillis,
                        raceDateMillis = mondayMillis + 35 * 86400000L,
                        calendarId = -1L,
                        customRaceName = corosSchedule.planName
                    )
                }
                else -> null
            }

            if (effectivePlan != null) {
                saveActivePlan(context, effectivePlan)
            }

            val payload = LapsPayload(
                updatedLabel = SimpleDateFormat("h:mm a", Locale.US).format(Date()),
                athlete = AthleteProfile(profile.nickname, profile.rhr, profile.maxHr, profile.lthr, profile.ltspPace, profile.weightKg),
                weeklyMileage = WeeklyMileageData(
                    actualKm = actualWkKm,
                    plannedKm = plannedWkKm,
                    remainingKm = Math.max(0.0, plannedWkKm - actualWkKm),
                    completionPct = completionPct,
                    runsCompleted = runsDone,
                    runsPlanned = if (hasActivePlanThisWeek) daysMatrix.count { it.plannedKm > 0 } else runsDone,
                    avgPace = wkAvgPace,
                    totalElevationM = totalElev,
                    days = daysMatrix
                ),
                trainingLoad = TrainingLoadData(
                    acuteTl = acuteTl,
                    weeklyTl = acuteTl,
                    targetTlMin = targetTlMin,
                    targetTlMax = targetTlMax,
                    loadRatioPct = loadRatio,
                    statusBadge = tlStatus,
                    rhr = profile.rhr,
                    lthr = profile.lthr,
                    recoveryHours = remainingRecoveryH,
                    readinessScore = readinessScore,
                    lastAerobicTe = lastRunData.aerobicTe,
                    lastAnaerobicTe = lastRunData.anaerobicTe
                ),
                nextWorkout = nwData,
                raceCountdown = rcData,
                zoneSpectrum = ZoneSpectrumData(aerobicPct = aeroPct, thresholdPct = 100 - aeroPct),
                lastRun = lastRunData,
                missedWorkout = missedWorkoutData,
                activePlan = effectivePlan,
                recentRuns = recentList,
                isMetricsOnly = isMetricsOnly,
                isRealData = true
            )

            cachedPayload = payload
            savePayloadToDisk(context, payload)
            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
