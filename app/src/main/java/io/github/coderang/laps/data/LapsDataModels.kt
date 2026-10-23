package io.github.coderang.laps.data

import io.github.coderang.laps.coach.EvoLabAnalyzer

data class AthleteProfile(
    val nickname: String = "Runner",
    val rhr: Int = 44,
    val maxHr: Int = 195,
    val lthr: Int = 172,
    val ltspPace: String = "4:56 /km",
    val weightKg: Double = 75.0
)

data class DayBarData(
    val index: Int,
    val dayLetter: String,
    val dayName: String,
    val date: String,
    val isToday: Boolean,
    val actualKm: Double,
    val plannedKm: Double,
    val status: String,
    val workoutType: String,
    val completionPct: Int = if (plannedKm > 0) ((actualKm / plannedKm) * 100.0 + 0.5).toInt() else 0
)

data class WeeklyMileageData(
    val actualKm: Double = 22.35,
    val plannedKm: Double = 44.0,
    val expectedKmSoFar: Double = 24.0,
    val expectedCompletionPct: Int = 93,
    val remainingKm: Double = 21.6,
    val completionPct: Int = 51,
    val runsCompleted: Int = 3,
    val runsPlanned: Int = 4,
    val avgPace: String = "6:36 /km",
    val totalElevationM: Int = 16,
    val days: List<DayBarData> = defaultDays()
) {
    companion object {
        fun defaultDays(): List<DayBarData> = listOf(
            DayBarData(0, "M", "Mon", "2026-10-19", false, 0.0, 0.0, "REST", "Rest", 0),
            DayBarData(1, "T", "Tue", "2026-10-20", false, 8.53, 10.0, "COMPLETED", "2x4K Threshold", 85),
            DayBarData(2, "W", "Wed", "2026-10-21", false, 7.01, 7.0, "COMPLETED", "Easy 7K", 100),
            DayBarData(3, "T", "Thu", "2026-10-22", false, 0.0, 0.0, "REST", "Rest", 0),
            DayBarData(4, "F", "Fri", "2026-10-23", true, 6.81, 7.0, "COMPLETED", "Easy 7K + Strides", 97),
            DayBarData(5, "S", "Sat", "2026-10-24", false, 0.0, 0.0, "REST", "Rest", 0),
            DayBarData(6, "S", "Sun", "2026-10-25", false, 0.0, 20.0, "PLANNED", "Long Run 20K", 0)
        )
    }
}

data class TrainingLoadData(
    val acuteTl: Int = 401,
    val weeklyTl: Int = 401,
    val targetTlMin: Int = 380,
    val targetTlMax: Int = 580,
    val loadRatioPct: Int = 69,
    val statusBadge: String = "PRODUCTIVE",
    val rhr: Int = 44,
    val lthr: Int = 172,
    val recoveryHours: Int = 17,
    val readinessScore: Int = 84,
    val lastAerobicTe: Double = 2.8,
    val lastAnaerobicTe: Double = 1.6
)

data class NextWorkoutData(
    val title: String = "Long Run 20K",
    val workoutType: String = "Long Run",
    val date: String = "2026-10-25",
    val dayBadge: String = "SUN · 6:00 AM",
    val countdown: String = "Tomorrow",
    val targetDistanceKm: Double = 20.0,
    val targetPace: String = "5:45–6:15 /km",
    val targetHrZone: String = "Zone 2–3 (145–160 bpm)",
    val watchSynced: Boolean = true
)

data class ZoneSpectrumData(
    val aerobicPct: Int = 78,
    val thresholdPct: Int = 22,
    val targetRatio: String = "80 / 20",
    val easyAvgHr: Int = 149,
    val qualityAvgHr: Int = 174,
    val z2CeilingHr: Int = 155,
    val zoneCreepStatus: String = "NO ZONE CREEP"
)

data class RaceCountdownData(
    val raceName: String = "HALF MARATHON",
    val raceDate: String = "2026-12-13",
    val daysLeft: Int = 51,
    val currentWeek: Int = 3,
    val totalWeeks: Int = 10,
    val phase: String = "AEROBIC + THRESHOLD BUILD",
    val goalTime: String = "1:45:00",
    val goalPace: String = "4:58 /km",
    val currentLtsp: String = "4:56 /km",
    val currentLthr: Int = 172,
    val paceDeltaSec: Int = -2,
    val readinessTag: String = "ON TRACK"
)

data class HrZoneBreakdown(
    val zoneIndex: Int,
    val name: String,
    val bpmRange: String,
    val seconds: Int,
    val duration: String,
    val percent: Int
)

data class RunSplitItem(
    val index: Int,
    val distanceKm: Double,
    val duration: String,
    val pace: String,
    val adjustedPace: String,
    val avgHr: Int,
    val cadence: Int,
    val powerWatts: Int,
    val strideCm: Int
)

data class MissedWorkoutOption(
    val label: String = "Drop & Move On",
    val sublabel: String = "",
    val action: String = "DROP",
    val targetDate: String = "",
    val isRecommended: Boolean = false
)

data class MissedWorkoutData(
    val eventId: Long = 0L,
    val title: String = "",
    val date: String = "",
    val targetDistanceKm: Double = 0.0,
    val workoutType: String = "Run",
    val coachAdvice: String = "",
    val options: List<MissedWorkoutOption> = emptyList()
) {
    companion object {
        fun defaultSample(): MissedWorkoutData = MissedWorkoutData(
            eventId = 9991L,
            title = "W3 Thu · 2x4K Threshold",
            date = "2026-10-22",
            targetDistanceKm = 10.0,
            workoutType = "Threshold",
            coachAdvice = "You missed Thursday's 2x4K Threshold session. With Sunday's 20K Long Run in 48 hours, shifting 8K of lactate threshold reps into Saturday risks high neuromuscular fatigue right before your key endurance run.",
            options = listOf(
                MissedWorkoutOption(
                    label = "Drop & Protect Long Run",
                    sublabel = "Absorb into weekly baseline · Keeps Sunday fresh",
                    action = "DROP",
                    targetDate = "",
                    isRecommended = true
                ),
                MissedWorkoutOption(
                    label = "Shift to Light Shakeout (4K)",
                    sublabel = "Easy aerobic shakeout today · Avoids heavy lactate loading",
                    action = "SHIFT",
                    targetDate = "2026-10-24",
                    isRecommended = false
                )
            )
        )
    }
}

data class LastRunData(
    val name: String = "Easy Run + Strides",
    val date: String = "2026-10-23",
    val dayLabel: String = "Fri, 23 Oct",
    val distanceKm: Double = 6.81,
    val plannedKm: Double = 7.0,
    val adherencePct: Int = 97,
    val verdictBadge: String = "100% On Target (7K)",
    val durationMinutes: Double = 49.6,
    val avgPace: String = "7:14 /km",
    val avgHr: Int = 149,
    val maxHr: Int = 173,
    val cadence: Int = 152,
    val maxCadence: Int = 180,
    val powerWatts: Int = 201,
    val maxPowerWatts: Int = 413,
    val strideLengthCm: Int = 91,
    val trainingLoad: Int = 110,
    val aerobicTe: Double = 2.8,
    val anaerobicTe: Double = 1.6,
    val vo2Max: Int = 51,
    val stamina7d: Double = 96.0,
    val bestKmPace: String = "6:42 /km",
    val elevGainM: Int = 6,
    val caloriesKcal: Int = 703,
    val physiologyNote: String = "Solid Aerobic Control: Avg HR 149 bpm stayed comfortably inside Zone 2 (<155 bpm).",
    val coachGuidance: String = "Up Next: Long Run 20K at 5:45–6:15 /km in Zone 2–3 (145–160 bpm).",
    val aiVerdictHeadline: String = "A+ Shakeout & Neuromuscular Pop (97% Volume · 94% Zone 2)",
    val aiExecutiveSummary: String = "You executed the dual purpose of this workout with textbook discipline: keeping heart rate comfortably inside Zone 2 while firing up fast-twitch recruitment without accumulating fatigue.",
    val aiInsights: List<String> = defaultInsights(),
    val aiTips: List<String> = defaultTips(),
    val hrZones: List<HrZoneBreakdown> = defaultZones(),
    val splits: List<RunSplitItem> = defaultSplits(),
    val stages: List<RunSplitItem> = emptyList()
) {
    companion object {
        fun empty(): LastRunData = LastRunData(
            name = "No Runs Synced",
            date = "--",
            dayLabel = "--",
            distanceKm = 0.0,
            plannedKm = 0.0,
            adherencePct = 0,
            verdictBadge = "No Data",
            durationMinutes = 0.0,
            avgPace = "--:--",
            avgHr = 0,
            maxHr = 0,
            cadence = 0,
            maxCadence = 0,
            powerWatts = 0,
            maxPowerWatts = 0,
            strideLengthCm = 0,
            trainingLoad = 0,
            aerobicTe = 0.0,
            anaerobicTe = 0.0,
            vo2Max = 0,
            stamina7d = 0.0,
            bestKmPace = "--:--",
            elevGainM = 0,
            caloriesKcal = 0,
            physiologyNote = "No workout recorded yet.",
            coachGuidance = "Log a workout with your COROS watch to view live physiological analysis.",
            aiVerdictHeadline = "No Recent Activities",
            aiExecutiveSummary = "Connect and record a workout with your COROS watch to view live on-device EvoLab physiological analysis and pacing metrics.",
            aiInsights = emptyList(),
            aiTips = emptyList(),
            hrZones = emptyList(),
            splits = emptyList(),
            stages = emptyList()
        )

        fun defaultInsights(): List<String> = listOf(
            "Aerobic Discipline: Base block stayed comfortably below your threshold, protecting heart rate instead of forcing pace.",
            "Neuromuscular Strides: Strides recruited fast-twitch fibers smoothly without building systemic fatigue.",
            "Form Watchpoint: Maintain light, quick foot turnover (158–162 spm) to minimize ground contact time."
        )

        fun defaultTips(): List<String> = listOf(
            "Upcoming Long Run: Lock early kilometers strictly in Zone 2. Do not bank time early.",
            "Hydration & Electrolytes: Pre-hydrate 30 minutes before running to keep cardiac drift minimal.",
            "Pacing Strategy: Hold a steady aerobic pace before progressing in the final 5K if heart rate allows."
        )

        fun defaultZones(): List<HrZoneBreakdown> = listOf(
            HrZoneBreakdown(1, "Z1–2 Aerobic Base", "<155 bpm", 2763, "46:03", 94),
            HrZoneBreakdown(2, "Z3 Aerobic Power", "156–163 bpm", 125, "2:05", 4),
            HrZoneBreakdown(3, "Z4 LTHR Threshold", "164–175 bpm", 70, "1:10", 2),
            HrZoneBreakdown(4, "Z5 VO2 Max", "176–182 bpm", 0, "0:00", 0),
            HrZoneBreakdown(5, "Z6 Max Effort", ">182 bpm", 0, "0:00", 0)
        )

        fun defaultSplits(): List<RunSplitItem> = listOf(
            RunSplitItem(1, 1.0, "7:08", "7:08", "7:08", 144, 151, 207, 94),
            RunSplitItem(2, 1.0, "7:01", "7:01", "7:01", 147, 154, 206, 91),
            RunSplitItem(3, 1.0, "7:11", "7:11", "7:11", 150, 154, 201, 90)
        )
    }
}

data class LapsPayload(
    val updatedLabel: String = "Just now",
    val athlete: AthleteProfile = AthleteProfile(),
    val weeklyMileage: WeeklyMileageData = WeeklyMileageData(),
    val trainingLoad: TrainingLoadData = TrainingLoadData(),
    val nextWorkout: NextWorkoutData = NextWorkoutData(),
    val zoneSpectrum: ZoneSpectrumData = ZoneSpectrumData(),
    val raceCountdown: RaceCountdownData = RaceCountdownData(),
    val lastRun: LastRunData = LastRunData(),
    val missedWorkout: MissedWorkoutData? = MissedWorkoutData.defaultSample(),
    val recentRuns: List<LastRunData> = listOf(LastRunData())
)

enum class ExpressiveTileType(
    val title: String,
    val subtitle: String,
    val gridSpan: String,
    val aspectRatio: Float,
    val isSquare: Boolean
) {
    WEEKLY_MILEAGE("Mileage Bars", "4 stacked capsule bars", "2x2", 1.0f, true),
    TRAINING_LOAD("EvoLab Ring", "Two-tone donut gauge", "2x2", 1.0f, true),
    NEXT_WORKOUT("Next Run Pods", "Circle + squircle pods", "2x2", 1.0f, true),
    ZONE_SPECTRUM("80/20 Split Shapes", "Top pill bar + metrics", "2x2", 1.0f, true),
    RACE_COUNTDOWN("Race Countdown", "12-lobe flower cookie", "2x2", 1.0f, true),
    COMMAND_CENTER("Liquid Progress", "Liquid progress card", "2x2", 1.0f, false)
}
