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
        backgroundColor = Color(0xFF000000)
    ),
    PIXEL_MINT(
        displayName = "Pixel Mint",
        primaryColor = Color(0xFFA8E6CF),
        secondaryColor = Color(0xFF56C596),
        accentColor = Color(0xFF20503B),
        backgroundColor = Color(0xFF000000)
    ),
    PIXEL_CORAL(
        displayName = "Pixel Coral",
        primaryColor = Color(0xFFFF8A80),
        secondaryColor = Color(0xFFFF5252),
        accentColor = Color(0xFFFFD180),
        backgroundColor = Color(0xFF000000)
    ),
    PIXEL_LAVENDER(
        displayName = "Pixel Lavender",
        primaryColor = Color(0xFFD6C7FF),
        secondaryColor = Color(0xFF9E86FF),
        accentColor = Color(0xFFEADBDB),
        backgroundColor = Color(0xFF000000)
    ),
    PIXEL_HONEY(
        displayName = "Pixel Honey",
        primaryColor = Color(0xFFFFD166),
        secondaryColor = Color(0xFFF4A261),
        accentColor = Color(0xFFE76F51),
        backgroundColor = Color(0xFF000000)
    ),
    OBSIDIAN(
        displayName = "Obsidian Mono",
        primaryColor = Color(0xFFFFFFFF),
        secondaryColor = Color(0xFFB0B0B0),
        accentColor = Color(0xFF808080),
        backgroundColor = Color(0xFF000000)
    )
}

/**
 * Typography font styles inspired by Android 14 lockscreen clocks.
 */
enum class WatchFaceFont(
    val title: String,
    val subtitle: String,
    val fontRes: Int
) {
    PIXEL_BUBBLY("Bubbly", "Chunky Interlocking", io.github.coderang.laps.R.font.google_sans_flex_bubbly),
    TALL_SERIF("Tall Serif", "Condensed Serif", io.github.coderang.laps.R.font.pixel_tall_serif),
    CLEAN_SANS("Clean Sans", "Minimalist Clock", io.github.coderang.laps.R.font.google_sans_clock_600),
    STACKED_BOLD("Stacked Bold", "Signature Pixel Bold", io.github.coderang.laps.R.font.google_sans_flex_stacked),
    THIN_ROUNDED("Thin Rounded", "Modern Curves", io.github.coderang.laps.R.font.pixel_rounded_thin),
    BLOCK_CUTOUT("Block Cutout", "Industrial Geometric", io.github.coderang.laps.R.font.pixel_block_cutout)
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

/**
 * Safe, hardware-vetted complication presets for COROS PACE Pro.
 * Only layouts guaranteed to render cleanly without overlapping or graphical clipping.
 */
enum class ComplicationLayout(
    val title: String,
    val subtitle: String,
    val description: String,
    val isInteractiveCycle: Boolean = false
) {
    BACK_BUTTON_CYCLE(
        title = "Back Button Cycle",
        subtitle = "Interactive pod • BACK/LAP button cycles",
        description = "Single centered pod that natively cycles on physical BACK button press: Steps ➔ Calories ➔ Heart Rate ➔ Battery ➔ Elevation.",
        isInteractiveCycle = true
    ),
    STEPS_AND_BATTERY(
        title = "Steps & Battery",
        subtitle = "Steps (Left) • Battery % (Right)",
        description = "Left pod shows daily steps (e.g. 1,087). Right pod shows watch battery percentage (e.g. 94%)."
    ),
    STEPS_AND_CALORIES(
        title = "Steps & Calories",
        subtitle = "Steps (Left) • Active Kcal (Right)",
        description = "Left pod shows daily steps. Right pod shows active calories burned today."
    ),
    HR_AND_BATTERY(
        title = "Heart Rate & Battery",
        subtitle = "Live HR (Left) • Battery % (Right)",
        description = "Left pod shows real-time heart rate in BPM. Right pod shows watch battery percentage."
    ),
    HR_AND_CALORIES(
        title = "Heart Rate & Calories",
        subtitle = "Live HR (Left) • Active Kcal (Right)",
        description = "Left pod shows real-time heart rate in BPM. Right pod shows active calories burned."
    ),
    MINIMALIST(
        title = "Minimalist",
        subtitle = "Date & Clock only",
        description = "Pure, uncluttered dial featuring only the interlocking clock numerals and top date cluster."
    )
}

data class WatchFaceConfig(
    val style: WatchFaceStyle = WatchFaceStyle.PIXEL_BUBBLY,
    val model: WatchModelPreset = WatchModelPreset.PACE_PRO,
    val palette: WatchFacePalette = WatchFacePalette.DYNAMIC_M3,
    val font: WatchFaceFont = WatchFaceFont.PIXEL_BUBBLY,
    val complicationLayout: ComplicationLayout = ComplicationLayout.BACK_BUTTON_CYCLE,
    val topComplication: ComplicationType = ComplicationType.ACUTE_LOAD,
    val bottomComplication: ComplicationType = ComplicationType.WEEKLY_MILEAGE,
    val is24HourFormat: Boolean = false,
    val showSecondsRing: Boolean = true,
    val isAodMode: Boolean = false
)
