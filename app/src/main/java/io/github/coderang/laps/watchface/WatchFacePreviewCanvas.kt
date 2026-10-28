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
import androidx.compose.foundation.clickable
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
    cycleIndex: Int = 0,
    onCycleComplication: (() -> Unit)? = null,
    acuteLoad: Int = 311,
    thresholdPace: String = "4:56",
    weeklyMileage: Float = 24.5f,
    weeklyTarget: Float = 45.0f,
    daysToRace: Int = 47
) {
    val context = LocalContext.current

    // Render the exact 416x416 hardware frame using the identical sprite bitmaps and
    // header rects that CorosWatchFaceCompiler.compileToBin() packs into the .bin binary.
    val hardwareBitmap = remember(
        config.style,
        config.palette,
        config.font,
        config.complicationLayout,
        config.isAodMode,
        config.is24HourFormat,
        previewDate.time / 60000L,
        cycleIndex
    ) {
        CorosWatchFaceCompiler.renderHardwarePreviewBitmap(
            context = context,
            config = config,
            previewDate = previewDate,
            cycleIndex = cycleIndex
        )
    }

    // Outer physical watch case & bezel
    Box(
        modifier = modifier
            .size(sizeDp)
            .shadow(16.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.6f))
            .clip(CircleShape)
            .then(
                if (onCycleComplication != null && config.complicationLayout == ComplicationLayout.BACK_BUTTON_CYCLE && !config.isAodMode) {
                    Modifier.clickable { onCycleComplication() }
                } else {
                    Modifier
                }
            )
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
        // Inner Display Bezel & Active 416x416 Hardware-Accurate Canvas
        Box(
            modifier = Modifier
                .fillMaxSize(config.model.bezelRatio)
                .clip(CircleShape)
                .background(if (config.isAodMode) Color.Black else config.palette.backgroundColor)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dstRect = android.graphics.RectF(0f, 0f, size.width, size.height)
                val bitmapPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                drawContext.canvas.nativeCanvas.drawBitmap(
                    hardwareBitmap,
                    null,
                    dstRect,
                    bitmapPaint
                )
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
    val isAod = config.isAodMode

    // Material You dual-tone diagonal pairing matching Google Pixel Watch Bold Digital
    val (c1, c2, c3, c4) = when (config.palette) {
        WatchFacePalette.OBSIDIAN -> listOf(
            config.palette.primaryColor,
            config.palette.secondaryColor,
            config.palette.secondaryColor,
            config.palette.primaryColor
        )
        WatchFacePalette.PIXEL_MINT -> listOf(
            Color(0xFF8DE7D6), // Mint (Top-Left)
            Color(0xFFA8B4FD), // Lavender (Top-Right)
            Color(0xFFA8B4FD), // Lavender (Bottom-Left)
            Color(0xFF8DE7D6)  // Mint (Bottom-Right)
        )
        WatchFacePalette.DYNAMIC_M3 -> listOf(
            Color(0xFF8DE7D6), // Mint
            Color(0xFFA8B4FD), // Lavender
            Color(0xFFA8B4FD), // Lavender
            Color(0xFF8DE7D6)  // Mint
        )
        else -> listOf(
            config.palette.primaryColor,
            config.palette.secondaryColor,
            config.palette.secondaryColor,
            config.palette.primaryColor
        )
    }

    // Outer Edge Complications
    val compPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = if (isAod) {
            Color.White.copy(alpha = 0.45f).toArgb()
        } else {
            c1.toArgb()
        }
        textSize = radius * 0.082f
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Top: Date (e.g. "TUE OCT 27") - clearly visible above the clock numbers, 1:1 with COROS watch display
    val cleanDateStr = dateStr.replace(",", "")
    val datePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = if (isAod) {
            Color.LightGray.copy(alpha = 0.85f).toArgb()
        } else {
            c2.toArgb()
        }
        textSize = radius * 0.086f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    drawContext.canvas.nativeCanvas.drawText(
        cleanDateStr,
        center.x,
        center.y - (radius * 0.70f),
        datePaint
    )

    // Bottom complications matching COROS PACE Pro binary layout 1:1
    if (!isAod) {
        val compY = center.y + (radius * 0.856f)
        val leftX = center.x - (radius * 0.28f)
        val rightX = center.x + (radius * 0.28f)
        val centerX = center.x

        when (config.complicationLayout) {
            ComplicationLayout.STEPS_AND_BATTERY -> {
                drawContext.canvas.nativeCanvas.drawText("1,087", leftX, compY, compPaint)
                drawContext.canvas.nativeCanvas.drawText("94%", rightX, compY, compPaint)
            }
            ComplicationLayout.STEPS_AND_CALORIES -> {
                drawContext.canvas.nativeCanvas.drawText("1,087", leftX, compY, compPaint)
                drawContext.canvas.nativeCanvas.drawText("450 kcal", rightX, compY, compPaint)
            }
            ComplicationLayout.HR_AND_BATTERY -> {
                drawContext.canvas.nativeCanvas.drawText("72 bpm", leftX, compY, compPaint)
                drawContext.canvas.nativeCanvas.drawText("94%", rightX, compY, compPaint)
            }
            ComplicationLayout.HR_AND_CALORIES -> {
                drawContext.canvas.nativeCanvas.drawText("72 bpm", leftX, compY, compPaint)
                drawContext.canvas.nativeCanvas.drawText("450 kcal", rightX, compY, compPaint)
            }
            ComplicationLayout.BACK_BUTTON_CYCLE -> {
                drawContext.canvas.nativeCanvas.drawText("⟳ 1,087 STEPS", centerX, compY, compPaint)
            }
            ComplicationLayout.MINIMALIST -> {
                // Pure dial, no bottom complications
            }
        }
    }

    // Center 4 Interlocking Digits (Upright 90°, prominent black cutout border)
    val d0 = if (hoursStr.length >= 2) hoursStr.substring(0, 1) else "0"
    val d1 = if (hoursStr.length >= 2) hoursStr.substring(1, 2) else hoursStr
    val d2 = if (minutesStr.length >= 2) minutesStr.substring(0, 1) else "0"
    val d3 = if (minutesStr.length >= 2) minutesStr.substring(1, 2) else minutesStr

    // Exact horizontal and vertical spread calibrated to Pixel Watch Bold Digital
    val clusterOffsetY = radius * 0.045f
    val xSpread = radius * 0.235f
    val ySpread = radius * 0.265f

    fun drawInterlockingDigit(
        char: String,
        fillColor: Color,
        xOff: Float,
        yOff: Float
    ) {
        val textSizePx = radius * 0.85f
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(center.x + xOff, center.y + clusterOffsetY + yOff)

        // 1. Black cutout border and backing to cleanly separate and mask underlying digits
        val strokePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = textSizePx
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = radius * 0.082f
            this.strokeJoin = android.graphics.Paint.Join.ROUND
            this.strokeCap = android.graphics.Paint.Cap.ROUND
            this.color = android.graphics.Color.BLACK
        }

        val solidBlackPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = textSizePx
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.FILL
            this.color = android.graphics.Color.BLACK
        }

        val yBaseline = -((strokePaint.descent() + strokePaint.ascent()) / 2f)

        val glyphPath = android.graphics.Path()
        val textMeasurePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = textSizePx
            this.textAlign = android.graphics.Paint.Align.CENTER
        }
        textMeasurePaint.getTextPath(char, 0, char.length, 0f, yBaseline, glyphPath)

        val cleanPath = android.graphics.Path()
        val opSuccess = try {
            cleanPath.op(glyphPath, glyphPath, android.graphics.Path.Op.UNION)
        } catch (_: Exception) {
            false
        }
        val targetPath = if (opSuccess && !cleanPath.isEmpty) cleanPath else glyphPath

        // Always draw the black contour separator first
        canvas.drawPath(targetPath, strokePaint)

        if (isAod) {
            // In AOD / Dim mode: Mask interior with black, then draw wireframe outline using the EXACT SAME FONT
            canvas.drawPath(targetPath, solidBlackPaint)

            val aodOutlinePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = radius * 0.038f
                this.strokeJoin = android.graphics.Paint.Join.ROUND
                this.strokeCap = android.graphics.Paint.Cap.ROUND
                this.color = fillColor.toArgb()
            }
            canvas.drawPath(targetPath, aodOutlinePaint)
        } else {
            // In Active mode: Full solid vibrant fill
            val fillPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.style = android.graphics.Paint.Style.FILL
                this.color = fillColor.toArgb()
            }
            canvas.drawPath(targetPath, fillPaint)
        }

        canvas.restore()
    }

    // Layer 1: Top-Left (d0) - base layer
    drawInterlockingDigit(d0, c1, -xSpread, -ySpread)

    // Layer 2: Top-Right (d1) - right digit overlaps on the left
    drawInterlockingDigit(d1, c2, xSpread, -ySpread)

    // Layer 3: Bottom-Left (d2) - bottom digit overlaps the top
    drawInterlockingDigit(d2, c3, -xSpread, ySpread)

    // Layer 4: Bottom-Right (d3) - bottom & right overlaps top and left
    drawInterlockingDigit(d3, c4, xSpread, ySpread)
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
