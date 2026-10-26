package io.github.coderang.laps.watchface

import androidx.compose.ui.graphics.Color

/**
 * Visual watch face styles inspired by Google Pixel Watch & Laps Material 3 Expressive design.
 */
enum class WatchFaceStyle(
    val title: String,
    val subtitle: String,
    val description: String
) {
    PIXEL_BUBBLY(
        title = "Pixel Bold Bubbly",
        subtitle = "Interlocking Pillow Numerals",
        description = "Chunky, colorful interlocking digits inspired by the Pixel Watch Bold Digital & Android 14 lock screen."
    ),
    CONCENTRIC(
        title = "Pixel Concentric",
        subtitle = "Radial Dial & Google Sans Digital",
        description = "Concentric rotating minute and second index rings surrounding bold digital numerals."
    ),
    STACKED_BOLD(
        title = "Pixel Stacked Bold",
        subtitle = "Two-Tier Hours & Minutes",
        description = "Signature Pixel Watch two-line stacked numeral typography with dual complication pods."
    ),
    LAPS_TRACK(
        title = "Laps Radial Track",
        subtitle = "Running Track & EvoLab Ring",
        description = "Signature Laps oval track layout with live weekly mileage progress and acute load indicator."
    ),
    EXPEDITION(
        title = "Expedition Sports",
        subtitle = "Tachymeter Bezel & Hands",
        description = "High-contrast athletic analog dial with cardinal compass accents and step/pace sub-dials."
    )
}

/**
 * Supported COROS watch hardware display targets.
 */
enum class WatchModelPreset(
    val modelName: String,
    val resolutionPx: Int,
    val screenType: String,
    val bezelRatio: Float
) {
    PACE_3("COROS PACE 3", 260, "Memory LCD", 0.88f),
    PACE_PRO("COROS PACE Pro", 416, "AMOLED", 0.92f),
    PACE_2("COROS PACE 2 / APEX 42", 240, "Memory LCD", 0.86f),
    APEX_2_PRO("COROS APEX 2 Pro / VERTIX 2", 260, "Memory LCD", 0.90f)
}

/**
 * Material 3 Expressive & Pixel Watch inspired color palettes.
 */
enum class WatchFacePalette(
    val displayName: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val accentColor: Color,
    val backgroundColor: Color
) {
    DYNAMIC_M3(
        displayName = "Dynamic Material",
        primaryColor = Color(0xFFD0BCFF),
        secondaryColor = Color(0xFFCCC2DC),
        accentColor = Color(0xFFEFB8C8),
        backgroundColor = Color(0xFF141218)
    ),
    PIXEL_MINT(
        displayName = "Pixel Mint",
        primaryColor = Color(0xFFA8E6CF),
        secondaryColor = Color(0xFF56C596),
        accentColor = Color(0xFF20503B),
        backgroundColor = Color(0xFF0F1A15)
    ),
    PIXEL_CORAL(
        displayName = "Pixel Coral",
        primaryColor = Color(0xFFFF8A80),
        secondaryColor = Color(0xFFFF5252),
        accentColor = Color(0xFFFFD180),
        backgroundColor = Color(0xFF1C1010)
    ),
    PIXEL_LAVENDER(
        displayName = "Pixel Lavender",
        primaryColor = Color(0xFFD6C7FF),
        secondaryColor = Color(0xFF9E86FF),
        accentColor = Color(0xFFEADBDB),
        backgroundColor = Color(0xFF161224)
    ),
    PIXEL_HONEY(
        displayName = "Pixel Honey",
        primaryColor = Color(0xFFFFD166),
        secondaryColor = Color(0xFFF4A261),
        accentColor = Color(0xFFE76F51),
        backgroundColor = Color(0xFF1C170E)
    ),
    OBSIDIAN(
        displayName = "Obsidian Mono",
        primaryColor = Color(0xFFFFFFFF),
        secondaryColor = Color(0xFFB0B0B0),
        accentColor = Color(0xFF808080),
        backgroundColor = Color(0xFF0A0A0A)
    )
}

/**
 * Watch face complication configuration slots.
 */
enum class ComplicationType(val label: String, val iconDesc: String) {
    ACUTE_LOAD("Acute Load", "EvoLab Load"),
    THRESHOLD_PACE("Threshold", "Lactate Threshold"),
    WEEKLY_MILEAGE("Weekly Dist", "Mileage Progress"),
    RACE_COUNTDOWN("Race Day", "Days Remaining"),
    BATTERY("Battery", "Watch Battery %"),
    STEPS("Steps", "Daily Step Count"),
    HEART_RATE("Heart Rate", "Current HR")
}

data class WatchFaceConfig(
    val style: WatchFaceStyle = WatchFaceStyle.PIXEL_BUBBLY,
    val model: WatchModelPreset = WatchModelPreset.PACE_PRO,
    val palette: WatchFacePalette = WatchFacePalette.PIXEL_MINT,
    val topComplication: ComplicationType = ComplicationType.ACUTE_LOAD,
    val bottomComplication: ComplicationType = ComplicationType.WEEKLY_MILEAGE,
    val is24HourFormat: Boolean = false,
    val showSecondsRing: Boolean = true,
    val isAodMode: Boolean = false
)
