package io.github.coderang.laps.coach

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional BYO Gemini API Caller (Free Tier from Google AI Studio).
 * Calls `gemini-2.5-flash` directly on-device with zero server or backend intermediary.
 */
object GeminiCoachClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun reasonMissedWorkout(
        apiKey: String,
        missedTitle: String,
        missedDistKm: Double,
        nextTitle: String,
        nextDistKm: Double,
        todayDateStr: String,
        currentTl: Int,
        lthr: Int
    ): EvoLabAnalyzer.MissedWorkoutResolution? {
        if (apiKey.isBlank()) return null

        val prompt = """
            You are an elite exercise physiologist and running coach. An athlete missed a scheduled workout:
            - Missed: '$missedTitle' ($missedDistKm km)
            - Next Scheduled: '$nextTitle' ($nextDistKm km)
            - Current Load: $currentTl TL, LTHR: $lthr bpm
            - Current Date: $todayDateStr

            Evaluate whether the athlete should drop this workout, swap it, or shift it without causing injury or compromising the upcoming key session.
            Output ONLY valid JSON matching this schema:
            {
              "coach_rationale": "2-sentence physiological explanation of trade-offs",
              "options": [
                {
                  "label": "Short Action Title (max 26 chars)",
                  "sublabel": "Brief reason (max 45 chars)",
                  "action": "DROP" or "SHIFT",
                  "target_date": "$todayDateStr" or "",
                  "is_recommended": true or false
                }
              ]
            }
        """.trimIndent()

        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val bodyObj = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.3)
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val respStr = resp.body?.string() ?: ""
                val root = JSONObject(respStr)
                val candidates = root.optJSONArray("candidates") ?: return null
                val text = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val parsed = JSONObject(text)

                val rationale = parsed.optString("coach_rationale", "")
                val optArr = parsed.optJSONArray("options") ?: return null
                val opts = mutableListOf<EvoLabAnalyzer.MissedOption>()
                for (i in 0 until optArr.length()) {
                    val o = optArr.getJSONObject(i)
                    opts.add(
                        EvoLabAnalyzer.MissedOption(
                            label = o.optString("label", "Drop & Move On"),
                            sublabel = o.optString("sublabel", ""),
                            action = o.optString("action", "DROP"),
                            targetDate = o.optString("target_date", ""),
                            isRecommended = o.optBoolean("is_recommended", false)
                        )
                    )
                }
                EvoLabAnalyzer.MissedWorkoutResolution(rationale, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
}
