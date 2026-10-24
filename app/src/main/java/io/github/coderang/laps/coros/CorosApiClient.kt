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
    private val region: String = "teamapi.coros.com"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var userId: String? = null

    private fun getBaseUrl(): String {
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
     * Authenticates using an official COROS OAuth 2.0 / MCP Bearer token.
     * Keeps user password 100% off-device.
     */
    fun authenticateWithToken(token: String): Result<CorosProfile> {
        this.accessToken = token.trim()
        if (token.isBlank()) return Result.failure(IllegalArgumentException("Token is empty"))
        return try {
            val url = "${getBaseUrl()}/activity/query?size=1&pageNumber=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("accesstoken", token.trim())
                .addHeader("accessToken", token.trim())
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .addHeader("User-Agent", "Laps-Android/1.0")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("Authentication failed with HTTP ${resp.code}"))
                }
                val body = resp.body?.string() ?: ""
                val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val code = root.optString("result", root.optString("code", ""))
                if (code.isNotEmpty() && code != "0000") {
                    val msg = root.optString("message", "Invalid or expired token ($code)")
                    return Result.failure(IllegalStateException(msg))
                }
                Result.success(CorosProfile(nickname = "COROS Athlete"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun login(email: String, rawPassword: String): Result<CorosProfile> {
        return try {
            val md5Pwd = md5(rawPassword)
            val jsonBody = JSONObject().apply {
                put("account", email.trim())
                put("pwd", md5Pwd)
                put("accountType", 2)
            }

            val request = Request.Builder()
                .url("${getBaseUrl()}/account/login")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("User-Agent", "Laps-Android/1.0")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val root = JSONObject(body)
                val code = root.optString("result", "")
                if (code != "0000") {
                    val msg = root.optString("message", "Authentication failed ($code)")
                    return Result.failure(IllegalStateException(msg))
                }

                val data = root.optJSONObject("data") ?: JSONObject()
                accessToken = data.optString("accessToken")
                userId = data.optString("userId")

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
                    userId = userId ?: "",
                    nickname = data.optString("nickname", "Runner"),
                    rhr = rhr,
                    maxHr = maxHr,
                    lthr = lthr,
                    ltspSec = ltspSec,
                    ltspPace = formatPace(ltspSec),
                    weightKg = weight
                )
                Result.success(profile)
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
        return try {
            val url = "${getBaseUrl()}/activity/query?size=$limit&pageNumber=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("accesstoken", token)
                .addHeader("accessToken", token)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Laps-Android/1.0")
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
        return try {
            val url = "${getBaseUrl()}/activity/detail/query?labelId=$labelId&sportType=$sportType"
            val emptyBody = "{}".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .addHeader("accesstoken", token)
                .addHeader("accessToken", token)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "Laps-Android/1.0")
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
}
