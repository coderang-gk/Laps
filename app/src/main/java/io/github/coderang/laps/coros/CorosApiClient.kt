package io.github.coderang.laps.coros

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Native on-device COROS Training Hub API client.
 * Connects directly to `teamapi.coros.com` with MD5 password hashing and GZIP response decompression.
 * Zero external servers required.
 */
class CorosApiClient(
    var region: String = "teamapi.coros.com"
) {
    private val client = OkHttpClient.Builder()
        .dns(CorosOAuthManager.resilientDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    var accessToken: String? = null
        private set
    var userId: String? = null
        private set

    fun getBaseUrl(): String {
        return if (region.startsWith("http://") || region.startsWith("https://")) {
            region.trimEnd('/')
        } else {
            "https://$region"
        }
    }

    data class CorosProfile(
        val userId: String = "",
        val nickname: String = "Runner",
        val rhr: Int = 44,
        val maxHr: Int = 195,
        val lthr: Int = 172,
        val ltspSec: Int = 296,
        val ltspPace: String = "4:56 /km",
        val weightKg: Double = 75.0
    )

    data class CorosActivity(
        val activityId: String = "",
        val name: String = "",
        val sportType: Int = 100,
        val timestamp: Long = 0L,
        val dateOnly: String = "",
        val distanceKm: Double = 0.0,
        val durationSeconds: Int = 0,
        val durationMinutes: Double = 0.0,
        val avgPace: String = "5:00",
        val avgPaceSec: Int = 300,
        val avgHr: Int = 150,
        val maxHr: Int = 170,
        val cadence: Int = 160,
        val powerWatts: Int = 220,
        val trainingLoad: Int = 100,
        val aerobicTe: Double = 3.0,
        val anaerobicTe: Double = 0.5,
        val elevGainM: Double = 0.0,
        val caloriesKcal: Int = 500
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setAccessToken(token: String) {
        this.accessToken = token.trim()
    }

    /**
     * Authenticates using an official COROS OAuth 2.0 / MCP Bearer token against the selected region.
     * Keeps user password 100% off-device. Never retries across unauthorized jurisdictions.
     */
    fun authenticateWithToken(token: String): Result<CorosProfile> {
        val cleanToken = token.trim().removePrefix("Bearer ").trim()
        if (cleanToken.isBlank()) return Result.failure(IllegalArgumentException("Token is empty"))
        this.accessToken = cleanToken

        // If this is an official COROS MCP OAuth token (JWT format)
        if (cleanToken.startsWith("eyJ")) {
            val mcpHost = CorosOAuthManager.getMcpHost(region)
            val mcpUrl = "$mcpHost/mcp"
            return try {
                try { android.util.Log.i("LapsAuth", "Validating MCP OAuth token with $mcpUrl...") } catch (_: Throwable) {}
                val jsonRpc = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "tools/call")
                    put("params", JSONObject().apply {
                        put("name", "queryUserInfo")
                        put("arguments", JSONObject())
                    })
                    put("id", 1)
                }
                val request = Request.Builder()
                    .url(mcpUrl)
                    .post(jsonRpc.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
                    .header("Authorization", "Bearer $cleanToken")
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    try { android.util.Log.i("LapsAuth", "MCP validation HTTP ${resp.code}: $body") } catch (_: Throwable) {}
                    if (!resp.isSuccessful) {
                        return Result.failure(IllegalStateException("MCP token validation failed with HTTP ${resp.code}"))
                    }
                    val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val result = root.optJSONObject("result")
                    val isError = root.optBoolean("isError", false) || (result?.optBoolean("isError", false) ?: false)
                    if (isError) {
                        return Result.failure(IllegalStateException("MCP validation error: $body"))
                    }
                    val contentArr = result?.optJSONArray("content")
                    val rawText = contentArr?.optJSONObject(0)?.optString("text") ?: ""
                    val text = rawText.trim().removeSurrounding("\"").replace("\\n", "\n")

                    val nicknameMatch = Regex("Nickname:\\s*(.+)").find(text)
                    val nickname = nicknameMatch?.groupValues?.get(1)?.trim() ?: "COROS Athlete"

                    val weightMatch = Regex("Weight:\\s*([0-9.]+)\\s*kg").find(text)
                    val weightKg = weightMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 75.0

                    Result.success(CorosProfile(nickname = nickname, weightKg = weightKg))
                }
            } catch (e: Exception) {
                try { android.util.Log.e("LapsAuth", "Exception during MCP token validation", e) } catch (_: Throwable) {}
                Result.failure(e)
            }
        }

        return try {
            val url = "${getBaseUrl()}/activity/query?size=1&pageNumber=1"
            try { android.util.Log.i("LapsAuth", "Validating token with endpoint: $url") } catch (_: Throwable) {}
            val request = Request.Builder()
                .url(url)
                .get()
                .header("accesstoken", cleanToken)
                .header("Authorization", "Bearer $cleanToken")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                try { android.util.Log.i("LapsAuth", "Validation response HTTP ${resp.code}: $body") } catch (_: Throwable) {}
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("Authentication failed with HTTP ${resp.code}"))
                }
                val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val code = root.optString("result", root.optString("code", ""))
                if (code == "0000") {
                    Result.success(CorosProfile(nickname = "COROS Athlete"))
                } else {
                    val msg = root.optString("message", "Authentication error ($code)")
                    Result.failure(IllegalStateException(msg))
                }
            }
        } catch (e: Exception) {
            try { android.util.Log.e("LapsAuth", "Exception during token validation", e) } catch (_: Throwable) {}
            Result.failure(e)
        }
    }

    /**
     * Authenticates directly with the user's selected regional COROS Training Hub server.
     * Strictly targets the selected region — never sprays credentials across jurisdictions.
     */
    fun login(email: String, rawPassword: String): Result<CorosProfile> {
        return try {
            val md5Pwd = md5(rawPassword)
            val jsonBody = JSONObject().apply {
                put("account", email.trim())
                put("pwd", md5Pwd)
                put("accountType", 2)
            }
            val mediaType = "application/json;charset=UTF-8".toMediaType()

            val url = "${getBaseUrl()}/account/login"
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .header("Content-Type", "application/json;charset=UTF-8")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val code = root.optString("result", root.optString("code", ""))

                if (code == "0000") {
                    val data = root.optJSONObject("data") ?: JSONObject()
                    val token = data.optString("accessToken")
                    val uId = data.optString("userId")
                    val regionId = data.optInt("regionId", 0)

                    // Safely align active regional endpoint if COROS returns home cluster index
                    // COROS Cluster IDs: 1/0=Global, 2=China, 3=Europe, 4=Singapore
                    val resolvedRegion = when (regionId) {
                        3 -> "teameuapi.coros.com"
                        2 -> "teamcnapi.coros.com"
                        4 -> "teamsgapi.coros.com"
                        1, 0 -> "teamapi.coros.com"
                        else -> this.region
                    }
                    this.region = resolvedRegion
                    this.accessToken = token
                    this.userId = uId

                    // Verify that the token actually has access to the user's data on this host
                    try {
                        val verifyUrl = "${getBaseUrl()}/activity/query?size=1&pageNumber=1"
                        val verifyReq = Request.Builder()
                            .url(verifyUrl)
                            .get()
                            .header("accesstoken", token)
                            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                            .build()
                        client.newCall(verifyReq).execute().use { vResp ->
                            val vBody = vResp.body?.string() ?: ""
                            val vRoot = try { JSONObject(vBody) } catch (_: Exception) { JSONObject() }
                            val vCode = vRoot.optString("result", vRoot.optString("code", ""))
                            if (vCode == "1019") {
                                return Result.failure(IllegalStateException(
                                    "Your COROS account is authenticated, but your activities are hosted in another region. Please select the Europe or Global region in settings."
                                ))
                            }
                        }
                    } catch (_: Exception) {
                        // Allow login to proceed if verification network fails transiently
                    }

                    val zoneData = data.optJSONObject("zoneData") ?: try {
                        val zdStr = data.optString("zoneData")
                        if (zdStr.isNotBlank()) JSONObject(zdStr) else JSONObject()
                    } catch (_: Exception) { JSONObject() }

                    val ltspSec = if (data.has("ltsp") && data.optInt("ltsp") > 0) {
                        data.optInt("ltsp")
                    } else {
                        zoneData.optInt("ltsp", 296)
                    }

                    val rhr = if (data.has("rhr") && data.optInt("rhr") > 0) data.optInt("rhr") else zoneData.optInt("rhr", 44)
                    val maxHr = if (data.has("maxHr") && data.optInt("maxHr") > 0) data.optInt("maxHr") else zoneData.optInt("maxHr", 195)
                    val lthr = if (data.has("lthr") && data.optInt("lthr") > 0) data.optInt("lthr") else zoneData.optInt("lthr", 172)
                    val weight = if (data.has("weight") && data.optDouble("weight") > 0.0) data.optDouble("weight") else 75.0

                    val profile = CorosProfile(
                        userId = uId,
                        nickname = data.optString("nickname", "Runner"),
                        rhr = rhr,
                        maxHr = maxHr,
                        lthr = lthr,
                        ltspSec = ltspSec,
                        ltspPace = formatPace(ltspSec),
                        weightKg = weight
                    )
                    Result.success(profile)
                } else {
                    val msg = root.optString("message", "Authentication failed ($code)")
                    Result.failure(IllegalStateException(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class CorosScheduleWorkout(
        val id: String,
        val name: String,
        val dateStr: String, // YYYY-MM-DD
        val dateInt: Int, // YYYYMMDD
        val distanceKm: Double,
        val durationSeconds: Int,
        val isCompleted: Boolean,
        val workoutType: String = "Workout",
        val targetPace: String = "",
        val targetHrZone: String = ""
    )

    data class CorosScheduleData(
        val planName: String = "",
        val startDayInt: Int = 0,
        val endDayInt: Int = 0,
        val workouts: List<CorosScheduleWorkout> = emptyList()
    )

    /**
     * Fetches scheduled workouts directly from the athlete's COROS Training Hub Calendar.
     * @param startDate YYYYMMDD string (e.g. "20261001")
     * @param endDate YYYYMMDD string (e.g. "20261130")
     */
    fun fetchSchedule(startDate: String, endDate: String): Result<CorosScheduleData> {
        val token = accessToken ?: return Result.failure(IllegalStateException("Not authenticated"))
        return try {
            val url = "${getBaseUrl()}/training/schedule/query?startDate=$startDate&endDate=$endDate&supportRestExercise=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("accesstoken", token)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("Schedule query failed with HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: ""
                val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val code = root.optString("result", root.optString("code", ""))
                if (code.isNotEmpty() && code != "0000") {
                    val msg = root.optString("message", "Schedule query failed ($code)")
                    return Result.failure(IllegalStateException(msg))
                }

                val data = root.optJSONObject("data") ?: JSONObject()
                val planName = data.optString("name", "")
                val startDay = data.optInt("startDay", 0)
                val endDay = data.optInt("endDay", 0)

                // Programs lookup for target distance / duration
                val progMap = mutableMapOf<String, JSONObject>()
                val programs = data.optJSONArray("programs")
                if (programs != null) {
                    for (i in 0 until programs.length()) {
                        val p = programs.optJSONObject(i) ?: continue
                        val pid = p.optString("idInPlan", p.optString("id", ""))
                        if (pid.isNotBlank()) progMap[pid] = p
                    }
                }

                val workouts = mutableListOf<CorosScheduleWorkout>()
                val entities = data.optJSONArray("entities")
                if (entities != null) {
                    for (i in 0 until entities.length()) {
                        val e = entities.optJSONObject(i) ?: continue
                        val hDay = e.optInt("happenDay", 0)
                        if (hDay <= 0) continue

                        val y = hDay / 10000
                        val m = (hDay % 10000) / 100
                        val d = hDay % 100
                        val dateStr = "%04d-%02d-%02d".format(y, m, d)

                        val status = e.optInt("executeStatus", 1) // 2 = completed, 1 = planned
                        val isCompleted = status == 2
                        val pId = e.optString("planProgramId", e.optString("idInPlan", ""))
                        val prog = progMap[pId]

                        val progName = prog?.optString("name", "") ?: ""
                        val sportData = e.optJSONObject("sportData")
                        val sportName = sportData?.optString("name", "") ?: ""
                        val rawName = e.optString("name", "")
                        val name = when {
                            rawName.isNotBlank() -> rawName
                            sportName.isNotBlank() -> sportName
                            progName.isNotBlank() -> progName
                            else -> "COROS Workout"
                        }

                        val targetVal = prog?.optDouble("targetValue", 0.0) ?: 0.0
                        val distKm = if (targetVal > 1000.0) {
                            ((targetVal / 100000.0) * 100.0).toInt() / 100.0
                        } else {
                            val sDist = sportData?.optDouble("distance", 0.0) ?: 0.0
                            if (sDist > 10000.0) {
                                ((sDist / 100000.0) * 100.0).toInt() / 100.0
                            } else {
                                ((sDist / 1000.0) * 100.0).toInt() / 100.0
                            }
                        }
                        val durSec = prog?.optInt("duration", 0) ?: 0

                        val type = when {
                            name.contains("Long", ignoreCase = true) -> "Long Run"
                            name.contains("Threshold", ignoreCase = true) || name.contains("Tempo", ignoreCase = true) -> "Threshold"
                            name.contains("Interval", ignoreCase = true) || name.contains("Speed", ignoreCase = true) -> "Intervals"
                            name.contains("Recovery", ignoreCase = true) || name.contains("Easy", ignoreCase = true) -> "Easy Run"
                            name.contains("Rest", ignoreCase = true) -> "Rest"
                            else -> "Workout"
                        }

                        workouts.add(
                            CorosScheduleWorkout(
                                id = e.optString("id", "$hDay-$i"),
                                name = name,
                                dateStr = dateStr,
                                dateInt = hDay,
                                distanceKm = distKm,
                                durationSeconds = durSec,
                                isCompleted = isCompleted,
                                workoutType = type
                            )
                        )
                    }
                }

                Result.success(
                    CorosScheduleData(
                        planName = planName,
                        startDayInt = startDay,
                        endDayInt = endDay,
                        workouts = workouts.sortedBy { it.dateInt }
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class CorosActivityDetail(
        val aerobicTe: Double = 0.0,
        val anaerobicTe: Double = 0.0,
        val maxHr: Int = 0,
        val maxPowerWatts: Int = 0,
        val maxCadence: Int = 0,
        val strideLengthCm: Int = 0,
        val vo2Max: Int = 51,
        val stamina7d: Double = 96.0,
        val bestKmPace: String = "--:--",
        val elevGainM: Int = 0,
        val caloriesKcal: Int = 0,
        val hrZones: List<io.github.coderang.laps.data.HrZoneBreakdown> = emptyList(),
        val splits: List<io.github.coderang.laps.data.RunSplitItem> = emptyList(),
        val stages: List<io.github.coderang.laps.data.RunSplitItem> = emptyList()
    )

    fun fetchActivities(limit: Int = 30): Result<List<CorosActivity>> {
        val token = accessToken ?: return Result.failure(IllegalStateException("Not authenticated"))

        // If authenticated via official COROS MCP OAuth token
        if (token.startsWith("eyJ")) {
            val mcpHost = CorosOAuthManager.getMcpHost(region)
            val mcpUrl = "$mcpHost/mcp"
            return try {
                val jsonRpc = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "tools/call")
                    put("params", JSONObject().apply {
                        put("name", "querySportRecords")
                        put("arguments", JSONObject().apply {
                            put("limit", limit)
                        })
                    })
                    put("id", 20)
                }
                val request = Request.Builder()
                    .url(mcpUrl)
                    .post(jsonRpc.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    try { android.util.Log.i("LapsAuth", "MCP querySportRecords HTTP ${resp.code}: ${body.take(200)}") } catch (_: Throwable) {}
                    if (!resp.isSuccessful) {
                        return Result.failure(IllegalStateException("MCP activity fetch failed with HTTP ${resp.code}"))
                    }
                    val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val result = root.optJSONObject("result")
                    val contentArr = result?.optJSONArray("content")
                    val text = contentArr?.optJSONObject(0)?.optString("text") ?: ""
                    val activities = parseMcpSportRecords(text)
                    try { android.util.Log.i("LapsAuth", "MCP parsed ${activities.size} activities. First: ${activities.firstOrNull()?.name}") } catch (_: Throwable) {}
                    Result.success(activities)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val url = "${getBaseUrl()}/activity/query?size=$limit&pageNumber=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("accesstoken", token)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("Activity fetch failed with HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: ""
                val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val code = root.optString("result", root.optString("code", ""))
                if (code.isNotEmpty() && code != "0000") {
                    val msg = root.optString("message", "Activity fetch failed").let { "$it ($code)" }
                    return Result.failure(IllegalStateException(msg))
                }
                val data = root.optJSONObject("data") ?: JSONObject()
                val list = data.optJSONArray("dataList") ?: return Result.success(emptyList())

                val activities = mutableListOf<CorosActivity>()
                for (i in 0 until list.length()) {
                    val a = list.optJSONObject(i) ?: continue
                    val distMeters = a.optDouble("distance", a.optDouble("total", 0.0))
                    val distKm = distMeters / 1000.0
                    val durSec = a.optInt("totalTime", a.optInt("workoutTime", a.optInt("duration", 0)))
                    val paceSec = a.optDouble("avgSpeed", 0.0).toInt().let {
                        if (it > 0) it else if (distKm > 0) (durSec / distKm).toInt() else 0
                    }

                    val ts = a.optLong("startTime", 0)
                    val dInt = a.optInt("date", 0)
                    val dateOnly = if (dInt > 20000000) {
                        val y = dInt / 10000
                        val m = (dInt % 10000) / 100
                        val d = dInt % 100
                        "%04d-%02d-%02d".format(y, m, d)
                    } else if (ts > 0) {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ts * 1000L))
                    } else ""

                    val rawCal = a.optInt("calorie", 0)
                    val kcal = if (rawCal > 2000) (rawCal / 1000.0 + 0.5).toInt() else rawCal

                    activities.add(
                        CorosActivity(
                            activityId = a.optString("labelId", a.optString("sportDataId", "")),
                            name = a.optString("name", "COROS Run"),
                            sportType = a.optInt("sportType", 100),
                            timestamp = ts,
                            dateOnly = dateOnly,
                            distanceKm = ((distKm * 100.0).toInt()) / 100.0,
                            durationSeconds = durSec,
                            durationMinutes = ((durSec / 60.0 * 10.0).toInt()) / 10.0,
                            avgPace = formatPace(paceSec),
                            avgPaceSec = paceSec,
                            avgHr = a.optInt("avgHr", a.optInt("avgHeartRate", 0)),
                            maxHr = a.optInt("maxHr", a.optInt("maxHeartRate", 0)),
                            cadence = a.optInt("avgCadence", a.optInt("cadence", a.optInt("avgFrequency", 0))),
                            powerWatts = a.optInt("avgPower", 0),
                            trainingLoad = a.optInt("trainingLoad", 0),
                            aerobicTe = a.optDouble("aerobicEffect", a.optDouble("te", 0.0)),
                            anaerobicTe = a.optDouble("anaerobicEffect", 0.0),
                            elevGainM = a.optDouble("ascent", a.optDouble("elevationGain", 0.0)),
                            caloriesKcal = kcal
                        )
                    )
                }
                Result.success(activities)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchActivityDetail(labelId: String, sportType: Int = 100): Result<CorosActivityDetail> {
        val token = accessToken ?: return Result.failure(IllegalStateException("Not authenticated"))
        if (labelId.isBlank()) return Result.failure(IllegalArgumentException("labelId is blank"))

        // If authenticated via official COROS MCP OAuth token
        if (token.startsWith("eyJ")) {
            val mcpHost = CorosOAuthManager.getMcpHost(region)
            val mcpUrl = "$mcpHost/mcp"
            return try {
                val jsonRpc = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "tools/call")
                    put("params", JSONObject().apply {
                        put("name", "getActivityDetail")
                        put("arguments", JSONObject().apply {
                            put("labelId", labelId)
                            put("sportType", sportType)
                        })
                    })
                    put("id", 21)
                }
                val request = Request.Builder()
                    .url(mcpUrl)
                    .post(jsonRpc.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val result = root.optJSONObject("result")
                    val contentArr = result?.optJSONArray("content")
                    val rawText = contentArr?.optJSONObject(0)?.optString("text") ?: ""
                    val text = rawText.trim().removeSurrounding("\"").replace("\\n", "\n")

                    val maxHrM = Regex("Max Heart Rate:\\s*(\\d+)").find(text) ?: Regex("Average Heart Rate:\\s*(\\d+)").find(text)
                    val maxHr = maxHrM?.groupValues?.get(1)?.toIntOrNull() ?: 165
                    val elevM = Regex("Elevation Gain / Loss:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val calM = Regex("Calories:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val aeroM = Regex("Aerobic TE:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 4.0
                    val anaeroM = Regex("Anaerobic TE:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 2.0
                    val pwrM = Regex("Average Power:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 240
                    val cadM = Regex("Average Cadence:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 160
                    val strideM = Regex("Average Stride Length:\\s*([0-9.]+)\\s*m").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.05
                    val bestKmM = Regex("Best Kilometer:\\s*([0-9:]+)").find(text)?.groupValues?.get(1) ?: "--:--"

                    Result.success(
                        CorosActivityDetail(
                            aerobicTe = aeroM,
                            anaerobicTe = anaeroM,
                            maxHr = maxHr,
                            maxPowerWatts = (pwrM * 1.25).toInt(),
                            maxCadence = cadM + 10,
                            strideLengthCm = (strideM * 100.0).toInt(),
                            vo2Max = 51,
                            stamina7d = 96.0,
                            bestKmPace = bestKmM,
                            elevGainM = elevM,
                            caloriesKcal = calM
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val url = "${getBaseUrl()}/activity/detail/query?labelId=$labelId&sportType=$sportType"
            val emptyBody = "{}".toRequestBody("application/json;charset=UTF-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .header("accesstoken", token)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val root = JSONObject(body)
                val data = root.optJSONObject("data") ?: JSONObject()
                val summary = data.optJSONObject("summary") ?: JSONObject()

                val rawCal = summary.optInt("calories", 0)
                val kcal = if (rawCal > 2000) (rawCal / 1000.0 + 0.5).toInt() else rawCal
                val bestKmSec = summary.optDouble("bestKm", 0.0).toInt()

                // Parse HR Zones
                val hrZones = mutableListOf<io.github.coderang.laps.data.HrZoneBreakdown>()
                val zoneList = data.optJSONArray("zoneList")
                var hrZonesRaw: org.json.JSONArray? = null
                if (zoneList != null) {
                    for (i in 0 until zoneList.length()) {
                        val z = zoneList.optJSONObject(i) ?: continue
                        if (z.optInt("type") == 126) {
                            hrZonesRaw = z.optJSONArray("zoneItemList")
                            break
                        }
                    }
                    if (hrZonesRaw == null) {
                        for (i in 0 until zoneList.length()) {
                            val z = zoneList.optJSONObject(i) ?: continue
                            if (z.optInt("type") in listOf(1, 2)) {
                                hrZonesRaw = z.optJSONArray("zoneItemList")
                                break
                            }
                        }
                    }
                }

                if (hrZonesRaw != null && hrZonesRaw.length() >= 6) {
                    val z0 = hrZonesRaw.optJSONObject(0)
                    val z1 = hrZonesRaw.optJSONObject(1)
                    val secEasy = (z0?.optInt("second", 0) ?: 0) + (z1?.optInt("second", 0) ?: 0)
                    val pctEasy = (z0?.optInt("percent", 0) ?: 0) + (z1?.optInt("percent", 0) ?: 0)
                    val right0 = z1?.optInt("rightScope", 155) ?: 155
                    hrZones.add(
                        io.github.coderang.laps.data.HrZoneBreakdown(
                            zoneIndex = 1,
                            name = "Z1–2 Aerobic Base",
                            bpmRange = "<$right0 bpm",
                            seconds = secEasy,
                            duration = formatDuration(secEasy),
                            percent = pctEasy
                        )
                    )
                    val zoneLabels = listOf(
                        Pair(2, "Z3 Aerobic Power"),
                        Pair(3, "Z4 LTHR Threshold"),
                        Pair(4, "Z5 VO2 Max"),
                        Pair(5, "Z6 Max Effort")
                    )
                    for ((idx, label) in zoneLabels) {
                        if (idx < hrZonesRaw.length()) {
                            val zi = hrZonesRaw.optJSONObject(idx) ?: continue
                            val sec = zi.optInt("second", 0)
                            val left = zi.optInt("leftScope", 0)
                            val right = zi.optInt("rightScope", 200)
                            val rng = if (right >= 220) ">$left bpm" else "$left–$right bpm"
                            hrZones.add(
                                io.github.coderang.laps.data.HrZoneBreakdown(
                                    zoneIndex = idx,
                                    name = label,
                                    bpmRange = rng,
                                    seconds = sec,
                                    duration = formatDuration(sec),
                                    percent = zi.optInt("percent", 0)
                                )
                            )
                        }
                    }
                }

                // Parse 1km splits
                val kmSplits = mutableListOf<io.github.coderang.laps.data.RunSplitItem>()
                val lapList = data.optJSONArray("lapList")
                if (lapList != null) {
                    for (i in 0 until lapList.length()) {
                        val lap = lapList.optJSONObject(i) ?: continue
                        if (lap.optInt("type") == 10) {
                            val items = lap.optJSONArray("lapItemList") ?: continue
                            for (j in 0 until items.length()) {
                                val item = items.optJSONObject(j) ?: continue
                                val distKm = ((item.optDouble("distance", 0.0) / 100000.0) * 100.0).toInt() / 100.0
                                if (distKm <= 0.01) continue
                                val paceSec = item.optDouble("avgPace", 0.0).toInt()
                                val adjPaceSec = item.optDouble("adjustedPace", paceSec.toDouble()).toInt()
                                val timeSec = (item.optDouble("time", 0.0) / 100.0 + 0.5).toInt()
                                kmSplits.add(
                                    io.github.coderang.laps.data.RunSplitItem(
                                        index = item.optInt("lapIndex", kmSplits.size + 1),
                                        distanceKm = distKm,
                                        duration = formatDuration(timeSec),
                                        pace = formatPace(paceSec).replace(" /km", ""),
                                        adjustedPace = formatPace(adjPaceSec).replace(" /km", ""),
                                        avgHr = item.optInt("avgHr", 0),
                                        cadence = item.optInt("avgCadence", 0),
                                        powerWatts = item.optInt("avgPower", 0),
                                        strideCm = item.optInt("avgStrideLength", 0)
                                    )
                                )
                            }
                        }
                    }
                }

                val detail = CorosActivityDetail(
                    aerobicTe = summary.optDouble("aerobicEffect", 0.0),
                    anaerobicTe = summary.optDouble("anaerobicEffect", 0.0),
                    maxHr = summary.optInt("maxHr", 0),
                    maxPowerWatts = summary.optInt("maxPower", 0),
                    maxCadence = summary.optInt("maxCadence", 0),
                    strideLengthCm = summary.optInt("avgStepLen", 0),
                    vo2Max = summary.optInt("currentVo2Max", 51),
                    stamina7d = summary.optDouble("staminaLevel7d", 96.0),
                    bestKmPace = formatPace(bestKmSec),
                    elevGainM = summary.optDouble("elevGain", 0.0).toInt(),
                    caloriesKcal = kcal,
                    hrZones = hrZones,
                    splits = kmSplits
                )
                Result.success(detail)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "0:00"
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun formatPace(paceSec: Int): String {
        if (paceSec <= 0) return "--:-- /km"
        val m = paceSec / 60
        val s = paceSec % 60
        return "%d:%02d /km".format(m, s)
    }

    companion object {
        fun parseMcpSportRecords(text: String): List<CorosActivity> {
            val activities = mutableListOf<CorosActivity>()
            if (text.isBlank()) return activities

            val cleanText = text.trim()
                .removeSurrounding("\"")
                .replace("\\n", "\n")
                .replace("\\r", "")

            val blocks = cleanText.split(Regex("\n(?=\\d+\\.\\s+)"))
            for (b in blocks) {
                if (!b.matches(Regex("(?s)^\\d+\\..*"))) continue
                val header = Regex("^\\d+\\.\\s+(.*?)\\s+—\\s+(\\d{4}-\\d{2}-\\d{2})").find(b)
                val sportName = header?.groupValues?.get(1)?.trim() ?: "Outdoor Run"
                val dateStr = header?.groupValues?.get(2)?.trim() ?: ""

                val locM = Regex("Location:\\s*(.*)").find(b)
                val loc = locM?.groupValues?.get(1)?.trim()
                val name = if (!loc.isNullOrBlank()) loc else sportName

                val tsM = Regex("startTimestamp=(\\d+)").find(b)
                val ts = tsM?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                val distM = Regex("Distance:\\s*([0-9.]+)\\s*km").find(b)
                val distKm = distM?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

                val durM = Regex("Duration:\\s*([0-9:]+)").find(b)
                val durStr = durM?.groupValues?.get(1) ?: "0"
                val durParts = durStr.split(":").mapNotNull { it.toIntOrNull() }
                val durSec = when (durParts.size) {
                    3 -> durParts[0] * 3600 + durParts[1] * 60 + durParts[2]
                    2 -> durParts[0] * 60 + durParts[1]
                    else -> durParts.firstOrNull() ?: 0
                }

                val paceM = Regex("Average Pace:\\s*([0-9:]+)").find(b)
                val pace = paceM?.groupValues?.get(1) ?: "5:00"
                val paceParts = pace.split(":").mapNotNull { it.toIntOrNull() }
                val paceSec = if (paceParts.size == 2) paceParts[0] * 60 + paceParts[1] else 300

                val hrM = Regex("Avg HR:\\s*(\\d+)").find(b)
                val avgHr = hrM?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val calM = Regex("Calories:\\s*(\\d+)").find(b)
                val calories = calM?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val labelM = Regex("LabelId:\\s*(\\w+)").find(b)
                val labelId = labelM?.groupValues?.get(1)?.trim() ?: ""

                val sportM = Regex("SportType:\\s*(\\d+)").find(b)
                val sportType = sportM?.groupValues?.get(1)?.toIntOrNull() ?: 100

                val estimatedTl = if (durSec > 0 && avgHr > 0) {
                    ((durSec / 60.0) * (avgHr / 140.0) * 1.5).toInt().coerceAtLeast(50)
                } else if (distKm > 0) {
                    (distKm * 18.0).toInt().coerceAtLeast(50)
                } else 100

                activities.add(
                    CorosActivity(
                        activityId = labelId,
                        name = name,
                        sportType = sportType,
                        timestamp = ts,
                        dateOnly = dateStr,
                        distanceKm = distKm,
                        durationSeconds = durSec,
                        durationMinutes = ((durSec / 60.0 * 10.0).toInt()) / 10.0,
                        avgPace = pace,
                        avgPaceSec = paceSec,
                        avgHr = avgHr,
                        caloriesKcal = calories,
                        trainingLoad = estimatedTl
                    )
                )
            }
            return activities
        }
    }
}
