package io.github.coderang.laps.watchface

import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import io.github.coderang.laps.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photorealistic Circular Watch Canvas rendering COROS display resolutions with
 * authentic Pixel Watch typography and Material 3 Expressive dials.
 */
@Composable
fun WatchFacePreviewCanvas(
    config: WatchFaceConfig,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 280.dp,
    previewDate: Date = remember { Date() },
    acuteLoad: Int = 311,
    thresholdPace: String = "4:56",
    weeklyMileage: Float = 24.5f,
    weeklyTarget: Float = 45.0f,
    daysToRace: Int = 47
) {
    val context = LocalContext.current
    val stackedFont = remember {
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex_stacked)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    val clockFont = remember {
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_clock_600)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    val bubblyFont = remember {
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex_bubbly)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
    val monoFont = remember {
        try {
            ResourcesCompat.getFont(context, R.font.google_sans_mono)
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "secondHand")
    val animatedSecondAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondAngle"
    )

    // Formatted time strings
    val hourFormat = if (config.is24HourFormat) "HH" else "hh"
    val hoursStr = SimpleDateFormat(hourFormat, Locale.getDefault()).format(previewDate)
    val minutesStr = SimpleDateFormat("mm", Locale.getDefault()).format(previewDate)
    val secondsVal = SimpleDateFormat("ss", Locale.getDefault()).format(previewDate).toIntOrNull() ?: 30
    val dateStr = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(previewDate).uppercase()

    // Outer physical watch case & bezel
    Box(
        modifier = modifier
            .size(sizeDp)
            .shadow(16.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.6f))
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2C2C30),
                        Color(0xFF1E1E22),
                        Color(0xFF121214)
                    )
                )
            )
            .border(
                width = 3.dp,
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFF484850),
                        Color(0xFF202024),
                        Color(0xFF5A5A64),
                        Color(0xFF202024),
                        Color(0xFF484850)
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner Display Bezel & Active Pixel Watch Canvas
        Box(
            modifier = Modifier
                .fillMaxSize(config.model.bezelRatio)
                .clip(CircleShape)
                .background(config.palette.backgroundColor)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f

                when (config.style) {
                    WatchFaceStyle.PIXEL_BUBBLY -> {
                        drawPixelBubblyWatchFace(
                            center = center,
                            radius = radius,
                            config = config,
                            hoursStr = hoursStr,
                            minutesStr = minutesStr,
                            dateStr = dateStr,
                            acuteLoad = acuteLoad,
                            thresholdPace = thresholdPace,
                            daysToRace = daysToRace,
                            bubblyFont = bubblyFont,
                            monoFont = monoFont
                        )
                    }

                    WatchFaceStyle.CONCENTRIC -> {
                        drawConcentricWatchFace(
                            center = center,
                            radius = radius,
                            config = config,
                            hoursStr = hoursStr,
                            minutesStr = minutesStr,
                            secondsVal = secondsVal,
                            dateStr = dateStr,
                            acuteLoad = acuteLoad,
                            thresholdPace = thresholdPace,
                            weeklyMileage = weeklyMileage,
                            weeklyTarget = weeklyTarget,
                            clockFont = clockFont,
                            monoFont = monoFont
                        )
                    }

                    WatchFaceStyle.STACKED_BOLD -> {
                        drawStackedBoldWatchFace(
                            center = center,
                            radius = radius,
                            config = config,
                            hoursStr = hoursStr,
                            minutesStr = minutesStr,
                            dateStr = dateStr,
                            acuteLoad = acuteLoad,
                            thresholdPace = thresholdPace,
                            daysToRace = daysToRace,
                            stackedFont = stackedFont,
                            monoFont = monoFont
                        )
                    }

                    WatchFaceStyle.LAPS_TRACK -> {
                        drawLapsTrackWatchFace(
                            center = center,
                            radius = radius,
                            config = config,
                            hoursStr = hoursStr,
                            minutesStr = minutesStr,
                            dateStr = dateStr,
                            acuteLoad = acuteLoad,
                            thresholdPace = thresholdPace,
                            weeklyMileage = weeklyMileage,
                            weeklyTarget = weeklyTarget,
                            clockFont = clockFont,
                            monoFont = monoFont
                        )
                    }

                    WatchFaceStyle.EXPEDITION -> {
                        drawExpeditionWatchFace(
                            center = center,
                            radius = radius,
                            config = config,
                            previewDate = previewDate,
                            secondAngle = animatedSecondAngle,
                            acuteLoad = acuteLoad,
                            thresholdPace = thresholdPace,
                            daysToRace = daysToRace,
                            clockFont = clockFont,
                            monoFont = monoFont
                        )
                    }
                }
            }
        }
    }
}

/**
 * 0. Pixel Bold Bubbly Dial (Interlocking 4-digit pillow typography)
 */
private fun DrawScope.drawPixelBubblyWatchFace(
    center: Offset,
    radius: Float,
    config: WatchFaceConfig,
    hoursStr: String,
    minutesStr: String,
    dateStr: String,
    acuteLoad: Int,
    thresholdPace: String,
    daysToRace: Int,
    bubblyFont: Typeface?,
    monoFont: Typeface?
) {
    val c1 = if (config.palette == WatchFacePalette.OBSIDIAN) config.palette.primaryColor else Color(0xFFFF9BB2) // Soft Pink
    val c2 = if (config.palette == WatchFacePalette.OBSIDIAN) config.palette.secondaryColor else Color(0xFF6BB5F9) // Sky Blue
    val c3 = if (config.palette == WatchFacePalette.OBSIDIAN) config.palette.accentColor else Color(0xFFF7F7F7) // Off-white
    val c4 = if (config.palette == WatchFacePalette.OBSIDIAN) config.palette.primaryColor else Color(0xFFCE88D8) // Lavender Purple

    // Outer Edge Complications (Pushed to perimeter like Pixel Watch Radial)
    val compPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = config.palette.secondaryColor.toArgb()
        textSize = radius * 0.088f
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Top: Date (e.g. "MAR 27 • FRI")
    drawContext.canvas.nativeCanvas.drawText(
        dateStr,
        center.x,
        center.y - (radius * 0.82f),
        compPaint
    )

    // Left: Battery / Acute Load
    drawContext.canvas.nativeCanvas.drawText(
        "$acuteLoad AC",
        center.x - (radius * 0.78f),
        center.y + (radius * 0.02f),
        compPaint
    )

    // Right: Threshold Pace
    drawContext.canvas.nativeCanvas.drawText(
        thresholdPace,
        center.x + (radius * 0.78f),
        center.y + (radius * 0.02f),
        compPaint
    )

    // Bottom: Race Countdown
    drawContext.canvas.nativeCanvas.drawText(
        "${daysToRace}d RACE",
        center.x,
        center.y + (radius * 0.84f),
        compPaint
    )

    // Center 4 Interlocking Tilted Digits
    val d0 = if (hoursStr.length >= 2) hoursStr.substring(0, 1) else "0"
    val d1 = if (hoursStr.length >= 2) hoursStr.substring(1, 2) else hoursStr
    val d2 = if (minutesStr.length >= 2) minutesStr.substring(0, 1) else "0"
    val d3 = if (minutesStr.length >= 2) minutesStr.substring(1, 2) else minutesStr

    fun drawInterlockingDigit(
        char: String,
        fillColor: Color,
        xOff: Float,
        yOff: Float,
        angleDeg: Float
    ) {
        val textSizePx = radius * 0.74f
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(center.x + xOff, center.y + yOff)
        canvas.rotate(angleDeg)

        // 1. Dark outline cut-out (creates the overlapping jigsaw separation)
        val strokePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = textSizePx
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = radius * 0.034f
            this.strokeJoin = android.graphics.Paint.Join.ROUND
            this.color = config.palette.backgroundColor.toArgb()
        }

        // 2. Solid color fill
        val fillPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = textSizePx
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.FILL
            this.color = fillColor.toArgb()
        }

        val yBaseline = -((fillPaint.descent() + fillPaint.ascent()) / 2f)

        // Draw outline cutout first, then vibrant fill
        canvas.drawText(char, 0f, yBaseline, strokePaint)
        canvas.drawText(char, 0f, yBaseline, fillPaint)

        canvas.restore()
    }

    // Balanced interlocking offset spacing with comfortable breathing room
    val xSpread = radius * 0.235f
    val ySpread = radius * 0.235f

    // Layer 1: Top-Left (d0, Pink) tilted -6°
    drawInterlockingDigit(d0, c1, -xSpread, -ySpread, -6f)

    // Layer 2: Top-Right (d1, Blue) tilted +8°
    drawInterlockingDigit(d1, c2, xSpread, -ySpread, 8f)

    // Layer 3: Bottom-Left (d2, White) tilted +5°
    drawInterlockingDigit(d2, c3, -xSpread, ySpread, 5f)

    // Layer 4: Bottom-Right (d3, Purple) tilted -6°
    drawInterlockingDigit(d3, c4, xSpread, ySpread, -6f)
}

/**
 * 1. Pixel Concentric Dial
 */
private fun DrawScope.drawConcentricWatchFace(
    center: Offset,
    radius: Float,
    config: WatchFaceConfig,
    hoursStr: String,
    minutesStr: String,
    secondsVal: Int,
    dateStr: String,
    acuteLoad: Int,
    thresholdPace: String,
    weeklyMileage: Float,
    weeklyTarget: Float,
    clockFont: Typeface?,
    monoFont: Typeface?
) {
    val primaryArgb = config.palette.primaryColor.toArgb()
    val secondaryArgb = config.palette.secondaryColor.toArgb()
    val accentArgb = config.palette.accentColor.toArgb()

    // Outer Ring: 60 Minute Radial Ticks
    val outerRadius = radius * 0.94f
    for (i in 0 until 60) {
        val angleRad = (i * 6f - 90f) * (PI / 180f).toFloat()
        val isMajor = i % 5 == 0
        val tickLength = if (isMajor) radius * 0.08f else radius * 0.04f
        val tickColor = if (isMajor) config.palette.secondaryColor else config.palette.secondaryColor.copy(alpha = 0.35f)
        val strokeWidth = if (isMajor) 2.5f else 1.2f

        val start = Offset(
            center.x + (outerRadius - tickLength) * cos(angleRad),
            center.y + (outerRadius - tickLength) * sin(angleRad)
        )
        val end = Offset(
            center.x + outerRadius * cos(angleRad),
            center.y + outerRadius * sin(angleRad)
        )
        drawLine(
            color = tickColor,
            start = start,
            end = end,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }

    // Middle Ring: Concentric Second Indicator Arc
    if (config.showSecondsRing) {
        val secondArcRadius = radius * 0.78f
        val sweep = (secondsVal / 60f) * 360f
        drawCircle(
            color = config.palette.primaryColor.copy(alpha = 0.12f),
            radius = secondArcRadius,
            center = center,
            style = Stroke(width = 3.5f)
        )
        drawArc(
            color = config.palette.primaryColor,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - secondArcRadius, center.y - secondArcRadius),
            size = Size(secondArcRadius * 2, secondArcRadius * 2),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round)
        )
    }

    // Center Digital Time using Google Sans Clock
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = clockFont ?: Typeface.DEFAULT_BOLD
        color = primaryArgb
        textSize = radius * 0.46f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val timeText = "$hoursStr:$minutesStr"
    val textYOffset = (textPaint.descent() + textPaint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        center.x,
        center.y - textYOffset - (radius * 0.04f),
        textPaint
    )

    // Top Complication: Date or Acute Load Pill
    val podPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = secondaryArgb
        textSize = radius * 0.11f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        dateStr,
        center.x,
        center.y - (radius * 0.38f),
        podPaint
    )

    // Bottom Complication Pod: EvoLab Acute Load & Threshold
    val bottomText = "$acuteLoad ACUTE • $thresholdPace/KM"
    val bottomPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = accentArgb
        textSize = radius * 0.11f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        bottomText,
        center.x,
        center.y + (radius * 0.44f),
        bottomPaint
    )
}

/**
 * 2. Pixel Stacked Bold Dial (Two-line hours over minutes)
 */
private fun DrawScope.drawStackedBoldWatchFace(
    center: Offset,
    radius: Float,
    config: WatchFaceConfig,
    hoursStr: String,
    minutesStr: String,
    dateStr: String,
    acuteLoad: Int,
    thresholdPace: String,
    daysToRace: Int,
    stackedFont: Typeface?,
    monoFont: Typeface?
) {
    val primaryArgb = config.palette.primaryColor.toArgb()
    val secondaryArgb = config.palette.secondaryColor.toArgb()
    val accentArgb = config.palette.accentColor.toArgb()

    // Line 1: Hours (Big bold stacked numeral)
    val hourPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = stackedFont ?: Typeface.DEFAULT_BOLD
        color = primaryArgb
        textSize = radius * 0.68f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        hoursStr,
        center.x,
        center.y - (radius * 0.05f),
        hourPaint
    )

    // Line 2: Minutes
    val minutePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = stackedFont ?: Typeface.DEFAULT_BOLD
        color = secondaryArgb
        textSize = radius * 0.68f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        minutesStr,
        center.x,
        center.y + (radius * 0.52f),
        minutePaint
    )

    // Top Right Pill: Date & Race countdown
    val topSubText = "$dateStr • ${daysToRace}D"
    val topPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = accentArgb
        textSize = radius * 0.10f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        topSubText,
        center.x,
        center.y - (radius * 0.66f),
        topPaint
    )

    // Subtle outer bezel guide dots
    for (i in 0 until 12) {
        val angleRad = (i * 30f - 90f) * (PI / 180f).toFloat()
        val dotRadius = radius * 0.90f
        drawCircle(
            color = config.palette.primaryColor.copy(alpha = 0.25f),
            radius = 2.5f,
            center = Offset(center.x + dotRadius * cos(angleRad), center.y + dotRadius * sin(angleRad))
        )
    }
}

/**
 * 3. Laps Radial Running Track Dial
 */
private fun DrawScope.drawLapsTrackWatchFace(
    center: Offset,
    radius: Float,
    config: WatchFaceConfig,
    hoursStr: String,
    minutesStr: String,
    dateStr: String,
    acuteLoad: Int,
    thresholdPace: String,
    weeklyMileage: Float,
    weeklyTarget: Float,
    clockFont: Typeface?,
    monoFont: Typeface?
) {
    val primaryArgb = config.palette.primaryColor.toArgb()
    val secondaryArgb = config.palette.secondaryColor.toArgb()

    // Outer Stadium Running Track Oval Arc
    val trackWidth = radius * 0.12f
    val trackRadius = radius * 0.86f
    val trackRect = Size(trackRadius * 2, trackRadius * 2)
    val trackOffset = Offset(center.x - trackRadius, center.y - trackRadius)

    // Background Track Groove
    drawArc(
        color = config.palette.primaryColor.copy(alpha = 0.12f),
        startAngle = 135f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = trackOffset,
        size = trackRect,
        style = Stroke(width = trackWidth, cap = StrokeCap.Round)
    )

    // Active Mileage Progress
    val progress = (weeklyMileage / weeklyTarget).coerceIn(0f, 1f)
    drawArc(
        brush = Brush.sweepGradient(
            listOf(
                config.palette.accentColor,
                config.palette.primaryColor,
                config.palette.secondaryColor
            )
        ),
        startAngle = 135f,
        sweepAngle = 270f * progress,
        useCenter = false,
        topLeft = trackOffset,
        size = trackRect,
        style = Stroke(width = trackWidth, cap = StrokeCap.Round)
    )

    // Pacer Runner Dot on Track Head
    val pacerAngleRad = (135f + (270f * progress)) * (PI / 180f).toFloat()
    val pacerOffset = Offset(
        center.x + trackRadius * cos(pacerAngleRad),
        center.y + trackRadius * sin(pacerAngleRad)
    )
    drawCircle(
        color = Color.White,
        radius = trackWidth * 0.65f,
        center = pacerOffset
    )
    drawCircle(
        color = config.palette.primaryColor,
        radius = trackWidth * 0.40f,
        center = pacerOffset
    )

    // Center Time Display
    val timePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = clockFont ?: Typeface.DEFAULT_BOLD
        color = primaryArgb
        textSize = radius * 0.42f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val timeText = "$hoursStr:$minutesStr"
    drawContext.canvas.nativeCanvas.drawText(
        timeText,
        center.x,
        center.y + (radius * 0.04f),
        timePaint
    )

    // EvoLab Pod in Stadium Center
    val mileageText = "%.1f / %.0f KM".format(weeklyMileage, weeklyTarget)
    val podPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = secondaryArgb
        textSize = radius * 0.11f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        mileageText,
        center.x,
        center.y + (radius * 0.36f),
        podPaint
    )
}

/**
 * 4. Expedition Sports Analog Dial
 */
private fun DrawScope.drawExpeditionWatchFace(
    center: Offset,
    radius: Float,
    config: WatchFaceConfig,
    previewDate: Date,
    secondAngle: Float,
    acuteLoad: Int,
    thresholdPace: String,
    daysToRace: Int,
    clockFont: Typeface?,
    monoFont: Typeface?
) {
    val primaryColor = config.palette.primaryColor
    val secondaryColor = config.palette.secondaryColor
    val accentColor = config.palette.accentColor

    // Outer Compass / Cardinal Ticks
    for (i in 0 until 12) {
        val angleRad = (i * 30f - 90f) * (PI / 180f).toFloat()
        val isCardinal = i % 3 == 0
        val tickLength = if (isCardinal) radius * 0.12f else radius * 0.06f
        val strokeWidth = if (isCardinal) 4f else 2f
        val color = if (isCardinal) primaryColor else secondaryColor.copy(alpha = 0.5f)

        val start = Offset(
            center.x + (radius * 0.92f - tickLength) * cos(angleRad),
            center.y + (radius * 0.92f - tickLength) * sin(angleRad)
        )
        val end = Offset(
            center.x + radius * 0.92f * cos(angleRad),
            center.y + radius * 0.92f * sin(angleRad)
        )
        drawLine(color, start, end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }

    // Cardinal Markers (12, 3, 6, 9)
    val cardPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = clockFont ?: Typeface.DEFAULT_BOLD
        color = primaryColor.toArgb()
        textSize = radius * 0.18f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText("12", center.x, center.y - radius * 0.62f, cardPaint)
    drawContext.canvas.nativeCanvas.drawText("6", center.x, center.y + radius * 0.74f, cardPaint)
    drawContext.canvas.nativeCanvas.drawText("3", center.x + radius * 0.70f, center.y + radius * 0.06f, cardPaint)
    drawContext.canvas.nativeCanvas.drawText("9", center.x - radius * 0.70f, center.y + radius * 0.06f, cardPaint)

    // Center Pod: Acute & Race Days
    val podText = "$acuteLoad ACUTE • ${daysToRace}D"
    val podPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = monoFont ?: Typeface.MONOSPACE
        color = accentColor.toArgb()
        textSize = radius * 0.10f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(podText, center.x, center.y + radius * 0.32f, podPaint)

    // Hands calculation
    val hours = previewDate.hours % 12
    val minutes = previewDate.minutes
    val seconds = previewDate.seconds

    val hourAngleRad = ((hours + minutes / 60f) * 30f - 90f) * (PI / 180f).toFloat()
    val minAngleRad = ((minutes + seconds / 60f) * 6f - 90f) * (PI / 180f).toFloat()
    val secAngleRad = (secondAngle - 90f) * (PI / 180f).toFloat()

    // Hour Hand
    drawLine(
        color = primaryColor,
        start = center,
        end = Offset(center.x + radius * 0.52f * cos(hourAngleRad), center.y + radius * 0.52f * sin(hourAngleRad)),
        strokeWidth = 6.5f,
        cap = StrokeCap.Round
    )

    // Minute Hand
    drawLine(
        color = secondaryColor,
        start = center,
        end = Offset(center.x + radius * 0.74f * cos(minAngleRad), center.y + radius * 0.74f * sin(minAngleRad)),
        strokeWidth = 4.5f,
        cap = StrokeCap.Round
    )

    // Second Hand (Precision Sports Orange/Accent)
    drawLine(
        color = accentColor,
        start = Offset(center.x - radius * 0.18f * cos(secAngleRad), center.y - radius * 0.18f * sin(secAngleRad)),
        end = Offset(center.x + radius * 0.84f * cos(secAngleRad), center.y + radius * 0.84f * sin(secAngleRad)),
        strokeWidth = 2f,
        cap = StrokeCap.Round
    )
    drawCircle(color = accentColor, radius = 5f, center = center)
    drawCircle(color = Color.Black, radius = 2.5f, center = center)
}
