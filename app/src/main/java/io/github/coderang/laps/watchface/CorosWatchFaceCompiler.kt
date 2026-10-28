package io.github.coderang.laps.watchface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import io.github.coderang.laps.R
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-device compilation engine converting Pixel Watch styles & Material 3 palettes
 * into standard COROS watchface packages (config.txt, info.json, PNG sprites, and .zip).
 */
object CorosWatchFaceCompiler {

    data class CompilationResult(
        val zipFile: File,
        val totalAssets: Int,
        val resolution: Int,
        val styleName: String
    )

    /**
     * Authoritative 416x416 coordinate & sprite specification for each WatchFaceStyle.
     * Shared between compileToBin(), compile(), and WatchFacePreviewCanvas so the preview
     * is guaranteed to match the physical COROS watch pixel-for-pixel.
     */
    data class StyleLayoutSpec(
        val digitW: Int,
        val digitH: Int,
        val digitFontSize: Float,
        val strokeWidthPx: Float,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val x3: Int,
        val y3: Int,
        val shiftDigitOne: Boolean
    )

    fun getStyleLayoutSpec(style: WatchFaceStyle): StyleLayoutSpec = when (style) {
        WatchFaceStyle.PIXEL_BUBBLY -> StyleLayoutSpec(
            digitW = 188, digitH = 272, digitFontSize = 172.06f, strokeWidthPx = 16.03f,
            x0 = 68, y0 = 10, x1 = 160, y1 = 10,
            x2 = 68, y2 = 126, x3 = 160, y3 = 126,
            shiftDigitOne = true
        )
        WatchFaceStyle.STACKED_BOLD -> StyleLayoutSpec(
            digitW = 136, digitH = 152, digitFontSize = 132f, strokeWidthPx = 11f,
            x0 = 72, y0 = 56, x1 = 208, y1 = 56,
            x2 = 72, y2 = 198, x3 = 208, y3 = 198,
            shiftDigitOne = false
        )
        WatchFaceStyle.CONCENTRIC -> StyleLayoutSpec(
            digitW = 76, digitH = 140, digitFontSize = 98f, strokeWidthPx = 8f,
            x0 = 46, y0 = 138, x1 = 120, y1 = 138,
            x2 = 220, y2 = 138, x3 = 294, y3 = 138,
            shiftDigitOne = false
        )
        WatchFaceStyle.LAPS_TRACK -> StyleLayoutSpec(
            digitW = 156, digitH = 216, digitFontSize = 142f, strokeWidthPx = 13f,
            x0 = 80, y0 = 46, x1 = 180, y1 = 46,
            x2 = 80, y2 = 150, x3 = 180, y3 = 150,
            shiftDigitOne = true
        )
        WatchFaceStyle.EXPEDITION -> StyleLayoutSpec(
            digitW = 152, digitH = 210, digitFontSize = 136f, strokeWidthPx = 12f,
            x0 = 82, y0 = 50, x1 = 182, y1 = 50,
            x2 = 82, y2 = 152, x3 = 182, y3 = 152,
            shiftDigitOne = false
        )
    }

    /**
     * Interactive cycle items for ComplicationLayout.BACK_BUTTON_CYCLE.
     */
    data class CycleMetricSample(
        val iconKind: String,
        val label: String,
        val digits: String,
        val showPercent: Boolean = false
    )

    val BACK_BUTTON_CYCLE_SAMPLES = listOf(
        CycleMetricSample("step", "Steps", "1087"),
        CycleMetricSample("kcal", "Calories", "450"),
        CycleMetricSample("hr", "Heart Rate", "72"),
        CycleMetricSample("battery", "Battery", "94", showPercent = true),
        CycleMetricSample("elevation", "Elevation", "240")
    )

    fun resolvePaletteColors(config: WatchFaceConfig): List<Int> = when (config.palette) {
        WatchFacePalette.OBSIDIAN -> listOf(
            config.palette.primaryColor.toArgb(),
            config.palette.secondaryColor.toArgb(),
            config.palette.secondaryColor.toArgb(),
            config.palette.primaryColor.toArgb()
        )
        WatchFacePalette.PIXEL_MINT -> listOf(
            android.graphics.Color.rgb(0x8D, 0xE7, 0xD6),
            android.graphics.Color.rgb(0xA8, 0xB4, 0xFD),
            android.graphics.Color.rgb(0xA8, 0xB4, 0xFD),
            android.graphics.Color.rgb(0x8D, 0xE7, 0xD6)
        )
        WatchFacePalette.DYNAMIC_M3 -> listOf(
            android.graphics.Color.rgb(0x8D, 0xE7, 0xD6),
            android.graphics.Color.rgb(0xA8, 0xB4, 0xFD),
            android.graphics.Color.rgb(0xA8, 0xB4, 0xFD),
            android.graphics.Color.rgb(0x8D, 0xE7, 0xD6)
        )
        else -> listOf(
            config.palette.primaryColor.toArgb(),
            config.palette.secondaryColor.toArgb(),
            config.palette.secondaryColor.toArgb(),
            config.palette.primaryColor.toArgb()
        )
    }

    /**
     * Renders the exact 416x416 hardware watch face frame using the identical sprite bitmaps,
     * bounding boxes, and alignment math compiled into the COROS .bin binary.
     */
    fun renderHardwarePreviewBitmap(
        context: Context,
        config: WatchFaceConfig,
        previewDate: java.util.Date = java.util.Date(),
        cycleIndex: Int = 0
    ): Bitmap {
        val res = 416
        val isAod = config.isAodMode
        val baseFont = resolveTypeface(context, config) ?: Typeface.DEFAULT_BOLD
        val regularFont = Typeface.create("sans-serif", Typeface.BOLD)
        val (c1, c2, c3, c4) = resolvePaletteColors(config)
        val spec = getStyleLayoutSpec(config.style)

        // 1. Background dial (416x416)
        val bitmap = renderDialBackground(config, res, c1, c2, isAod = isAod)
        val canvas = Canvas(bitmap)

        // 2. Top Date Cluster (Week: {126,26,186,52,right}, Month: {192,26,192,26}, Day: {252,26,292,52,left})
        val cal = java.util.Calendar.getInstance().apply { time = previewDate }
        val weekdays = arrayOf("TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON")
        val months = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        val dowIndex = ((cal.get(java.util.Calendar.DAY_OF_WEEK) + 4) % 7).coerceIn(0, 6)
        val monthIndex = cal.get(java.util.Calendar.MONTH).coerceIn(0, 11)
        val dayStr = "%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH))

        val dateColor = if (isAod) android.graphics.Color.LTGRAY else c2
        val dayColor = if (isAod) android.graphics.Color.WHITE else c1

        val weekBmp = renderTextBadge(weekdays[dowIndex], regularFont, dateColor, 54, 26)
        val monthBmp = renderTextBadge(months[monthIndex], regularFont, dateColor, 54, 26)
        // Week rect {126, 26, 186, 52, align = 20 (right|vcenter)} -> x = 186 - 54 = 132, y = 26
        canvas.drawBitmap(weekBmp, 132f, 26f, null)
        // Month point rect {192, 26, 192, 26} -> x = 192, y = 26
        canvas.drawBitmap(monthBmp, 192f, 26f, null)
        // Day rect {252, 26, 292, 52, align = 17 (left|vcenter)} -> x = 252, y = 26
        dayStr.forEachIndexed { idx, ch ->
            val dBmp = renderTextBadge(ch.toString(), regularFont, dayColor, 16, 26)
            canvas.drawBitmap(dBmp, 252f + idx * 16f, 26f, null)
        }

        // 3. 4 Clock Digit Sprites at exact header coordinates (x0,y0 .. x3,y3)
        val hourPattern = if (config.is24HourFormat) "HH" else "hh"
        val hoursStr = java.text.SimpleDateFormat(hourPattern, java.util.Locale.US).format(previewDate)
        val minutesStr = java.text.SimpleDateFormat("mm", java.util.Locale.US).format(previewDate)
        val d0 = hoursStr.getOrElse(0) { '0' }.toString()
        val d1 = hoursStr.getOrElse(1) { '3' }.toString()
        val d2 = minutesStr.getOrElse(0) { '0' }.toString()
        val d3 = minutesStr.getOrElse(1) { '0' }.toString()

        val bmp0 = renderDigitBitmap(baseFont, d0, c1, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = isAod)
        val bmp1 = renderDigitBitmap(baseFont, d1, c2, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = isAod)
        val bmp2 = renderDigitBitmap(baseFont, d2, c3, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = isAod)
        val bmp3 = renderDigitBitmap(baseFont, d3, c4, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = isAod)

        canvas.drawBitmap(bmp0, spec.x0.toFloat(), spec.y0.toFloat(), null)
        canvas.drawBitmap(bmp1, spec.x1.toFloat(), spec.y1.toFloat(), null)
        canvas.drawBitmap(bmp2, spec.x2.toFloat(), spec.y2.toFloat(), null)
        canvas.drawBitmap(bmp3, spec.x3.toFloat(), spec.y3.toFloat(), null)

        // 4. Bottom Complication Sprites (Active Mode only, matching COROS firmware behavior)
        if (!isAod) {
            fun drawDigitsInRect(digits: String, showPercent: Boolean, x0: Int, y0: Int, x1: Int, y1: Int, align: Int) {
                val glyphW = 16
                val glyphH = 26
                val totalChars = digits.length + (if (showPercent) 1 else 0)
                val totalW = totalChars * glyphW
                val startX = when {
                    (align and 2) != 0 -> x0 + (x1 - x0 - totalW) / 2f // hcenter
                    (align and 4) != 0 -> (x1 - totalW).toFloat()      // right
                    else -> x0.toFloat()                               // left
                }
                val startY = if ((align and 16) != 0) y0 + (y1 - y0 - glyphH) / 2f else y0.toFloat()
                digits.forEachIndexed { i, ch ->
                    val glyph = renderTextBadge(ch.toString(), regularFont, c1, glyphW, glyphH)
                    canvas.drawBitmap(glyph, startX + i * glyphW, startY, null)
                }
                if (showPercent) {
                    val pctGlyph = renderTextBadge("%", regularFont, c1, glyphW, glyphH)
                    canvas.drawBitmap(pctGlyph, startX + digits.length * glyphW, startY, null)
                }
            }

            when (config.complicationLayout) {
                ComplicationLayout.BACK_BUTTON_CYCLE -> {
                    val sample = BACK_BUTTON_CYCLE_SAMPLES[cycleIndex.mod(BACK_BUTTON_CYCLE_SAMPLES.size)]
                    val iconBmp = if (sample.iconKind == "battery") {
                        renderBatteryStateIcon(10, c1, 22)
                    } else {
                        renderControlIcon(sample.iconKind, c1, 22)
                    }
                    val numDigits = sample.digits.length + (if (sample.showPercent) 1 else 0)
                    val glyphW = 16
                    val iconW = 22
                    val gap = 6
                    val textW = numDigits * glyphW
                    val totalW = iconW + gap + textW
                    val startX = Math.round((416f - totalW) / 2f)
                    val iconX = startX.toFloat()
                    val textX0 = startX + iconW + gap
                    canvas.drawBitmap(iconBmp, iconX, 366f, null)
                    drawDigitsInRect(sample.digits, sample.showPercent, textX0, 364, textX0 + textW, 390, align = 17)
                }
                ComplicationLayout.STEPS_AND_BATTERY -> {
                    drawDigitsInRect("1087", false, 126, 364, 206, 390, align = 17)
                    drawDigitsInRect("94", true, 238, 364, 318, 390, align = 17)
                }
                ComplicationLayout.STEPS_AND_CALORIES -> {
                    drawDigitsInRect("1087", false, 126, 364, 206, 390, align = 17)
                    drawDigitsInRect("450", false, 238, 364, 318, 390, align = 17)
                }
                ComplicationLayout.HR_AND_BATTERY -> {
                    drawDigitsInRect("72", false, 126, 364, 206, 390, align = 17)
                    drawDigitsInRect("94", true, 238, 364, 318, 390, align = 17)
                }
                ComplicationLayout.HR_AND_CALORIES -> {
                    drawDigitsInRect("72", false, 126, 364, 206, 390, align = 17)
                    drawDigitsInRect("450", false, 238, 364, 318, 390, align = 17)
                }
                ComplicationLayout.MINIMALIST -> Unit
            }
        }

        return bitmap
    }

    fun renderFullPreviewBitmap(context: Context, config: WatchFaceConfig, resolution: Int = 800): Bitmap {
        val hwBitmap = renderHardwarePreviewBitmap(context, config)
        return if (resolution == 416) hwBitmap else Bitmap.createScaledBitmap(hwBitmap, resolution, resolution, true)
    }

    fun compile(
        context: Context,
        config: WatchFaceConfig,
        outputDir: File = context.cacheDir
    ): CompilationResult {
        val resolution = config.model.resolutionPx
        val folderName = "watchface_${resolution}x${resolution}"
        val stagingDir = File(outputDir, "staging_${System.currentTimeMillis()}").apply { mkdirs() }
        val targetDir = File(stagingDir, folderName).apply { mkdirs() }

        // 1. Generate info.json conforming to official COROS & CorosLink DIY template contracts
        val templateId = when (resolution) {
            416 -> 416901
            260 -> 260902
            240 -> 240901
            else -> 250601
        }
        val infoJson = JSONObject().apply {
            put("m_name", config.style.title)
            put("m_app", folderName)
            put("m_preview", "watchface_customize.png")
            put("o_template_id", templateId)
            put("o_diy_version", 1)
            put("o_wf_ver", 0)
            put("name", config.style.title)
            put("model", config.model.modelName)
            put("resolution", "${resolution}x${resolution}")
            put("version", "1.3.0")
            put("author", "Laps M3 Expressive Studio")
            put("palette", config.palette.displayName)
            put("generator", "Laps CorosWatchFaceCompiler")
        }
        File(stagingDir, "info.json").writeText(infoJson.toString(2))

        // 1.1 Render watchface_customize.png (required by CorosLink Studio import)
        val previewBitmap = renderFullPreviewBitmap(context, config, resolution = 800)
        savePng(previewBitmap, File(stagingDir, "watchface_customize.png"))
        savePng(previewBitmap, File(targetDir, "watchface_customize.png"))

        // 2. Compute authentic Pixel Watch Bold Digital geometric dimensions & positions
        val center = resolution / 2f
        val radius = center * 0.94f
        val textSizePx = radius * 0.88f
        val xSpread = radius * 0.235f
        val ySpread = radius * 0.275f
        val strokeWidthPx = radius * 0.082f

        val (c1, c2, c3, c4) = when (config.palette) {
            WatchFacePalette.OBSIDIAN -> listOf(config.palette.primaryColor, config.palette.secondaryColor, config.palette.secondaryColor, config.palette.primaryColor)
            WatchFacePalette.PIXEL_MINT -> listOf(androidx.compose.ui.graphics.Color(0xFF8DE7D6), androidx.compose.ui.graphics.Color(0xFFA8B4FD), androidx.compose.ui.graphics.Color(0xFFA8B4FD), androidx.compose.ui.graphics.Color(0xFF8DE7D6))
            WatchFacePalette.DYNAMIC_M3 -> listOf(androidx.compose.ui.graphics.Color(0xFF8DE7D6), androidx.compose.ui.graphics.Color(0xFFA8B4FD), androidx.compose.ui.graphics.Color(0xFFA8B4FD), androidx.compose.ui.graphics.Color(0xFF8DE7D6))
            else -> listOf(config.palette.primaryColor, config.palette.secondaryColor, config.palette.secondaryColor, config.palette.primaryColor)
        }

        val font = resolveTypeface(context, config)
        val aodFont = try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex_aod)
        } catch (_: Exception) {
            font
        }

        // Measure maximum digit bounds for uniform frame sizing
        val testPaint = Paint().apply {
            isAntiAlias = true
            this.textSize = textSizePx
            typeface = font ?: Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        var maxW = 0
        var maxH = 0
        for (d in 0..9) {
            testPaint.getTextBounds(d.toString(), 0, 1, bounds)
            if (bounds.width() > maxW) maxW = bounds.width()
            if (bounds.height() > maxH) maxH = bounds.height()
        }
        val padding = (strokeWidthPx * 2.5f).toInt() + 16
        var digitW = maxW + padding
        var digitH = maxH + padding
        if (digitW % 2 != 0) digitW++
        if (digitH % 2 != 0) digitH++

        // Authentic Pixel Watch Bold Digital center offsets
        val cx0 = center - xSpread
        val cy0 = center - ySpread
        val cx1 = center + xSpread
        val cy1 = center - ySpread
        val cx2 = center - xSpread
        val cy2 = center + ySpread
        val cx3 = center + xSpread
        val cy3 = center + ySpread

        val x0 = Math.round(cx0 - digitW / 2f)
        val y0 = Math.round(cy0 - digitH / 2f)
        val x1 = Math.round(cx1 - digitW / 2f)
        val y1 = Math.round(cy1 - digitH / 2f)
        val x2 = Math.round(cx2 - digitW / 2f)
        val y2 = Math.round(cy2 - digitH / 2f)
        val x3 = Math.round(cx3 - digitW / 2f)
        val y3 = Math.round(cy3 - digitH / 2f)

        // 2.1 Generate config.txt using COROS 4-digit layout keys
        val configText = buildString {
            appendLine("// Laps COROS Watch Face Configuration")
            appendLine("[watchface_name] = ${config.style.title}")
            appendLine("[target_model] = ${config.model.modelName}")
            appendLine("[bg_color] = 0x%06X".format(config.palette.backgroundColor.toArgb() and 0xFFFFFF))
            appendLine("[background_icon] = background.png")
            appendLine("[background_icon_pos] = {0,0}")
            appendLine("[time_hour_high_pos] = {$x0,$y0}")
            appendLine("[time_hour_high_font] = time_num_primary")
            appendLine("[time_hour_low_pos] = {$x1,$y1}")
            appendLine("[time_hour_low_font] = time_num_secondary")
            appendLine("[time_minute_high_pos] = {$x2,$y2}")
            appendLine("[time_minute_high_font] = time_num_secondary")
            appendLine("[time_minute_low_pos] = {$x3,$y3}")
            appendLine("[time_minute_low_font] = time_num_primary")
            appendLine("[english_date_week_rect] = {108,54,168,80,right|vcenter}")
            appendLine("[english_date_week_font] = date_week")
            appendLine("[english_date_month_rect] = {176,54,176,54}")
            appendLine("[english_date_month_font] = date_month")
            appendLine("[english_date_day_rect] = {236,54,276,80,left|vcenter}")
            appendLine("[english_date_day_font] = date_day")
            when (config.complicationLayout) {
                ComplicationLayout.STEPS_AND_BATTERY -> {
                    appendLine("[step_rect] = {100,372,200,400,hcenter|vcenter}")
                    appendLine("[step_font] = date_day")
                    appendLine("[battery_level_rect] = {216,372,316,400,hcenter|vcenter}")
                    appendLine("[battery_level_font] = date_day")
                }
                ComplicationLayout.STEPS_AND_CALORIES -> {
                    appendLine("[step_rect] = {100,372,200,400,hcenter|vcenter}")
                    appendLine("[step_font] = date_day")
                    appendLine("[kcal_rect] = {216,372,316,400,hcenter|vcenter}")
                    appendLine("[kcal_font] = date_day")
                }
                ComplicationLayout.HR_AND_BATTERY -> {
                    appendLine("[heartreate_level_rect] = {100,372,200,400,hcenter|vcenter}")
                    appendLine("[heartreate_level_font] = date_day")
                    appendLine("[battery_level_rect] = {216,372,316,400,hcenter|vcenter}")
                    appendLine("[battery_level_font] = date_day")
                }
                ComplicationLayout.HR_AND_CALORIES -> {
                    appendLine("[heartreate_level_rect] = {100,372,200,400,hcenter|vcenter}")
                    appendLine("[heartreate_level_font] = date_day")
                    appendLine("[kcal_rect] = {216,372,316,400,hcenter|vcenter}")
                    appendLine("[kcal_font] = date_day")
                }
                ComplicationLayout.BACK_BUTTON_CYCLE -> {
                    appendLine("[rect_control1_pos] = {0,0}")
                    appendLine("[control_step_icon_pos] = {162,366}")
                    appendLine("[control_step_icon] = icon\\step.png")
                    appendLine("[control_step_rect] = {190,364,290,390,left|vcenter}")
                    appendLine("[control_step_font] = date_day")

                    appendLine("[control_kcal_icon_pos] = {170,366}")
                    appendLine("[control_kcal_icon] = icon\\kcal.png")
                    appendLine("[control_kcal_rect] = {198,364,278,390,left|vcenter}")
                    appendLine("[control_kcal_font] = date_day")

                    appendLine("[control_hr_icon_pos] = {178,366}")
                    appendLine("[control_hr_icon] = icon\\hr.png")
                    appendLine("[control_hr_rect] = {206,364,286,390,left|vcenter}")
                    appendLine("[control_hr_font] = date_day")

                    appendLine("[control_battery_level_icon_pos] = {170,366}")
                    appendLine("[control_battery_level_icon] = icon\\battery.png")
                    appendLine("[control_battery_level_rect] = {198,364,278,390,left|vcenter}")
                    appendLine("[control_battery_level_font] = date_day")

                    appendLine("[control_elevation_icon_pos] = {170,366}")
                    appendLine("[control_elevation_icon] = icon\\elevation.png")
                    appendLine("[control_elevation_rect] = {198,364,278,390,left|vcenter}")
                    appendLine("[control_elevation_font] = date_day")
                }
                ComplicationLayout.MINIMALIST -> {
                    // No bottom complications
                }
            }
        }
        File(targetDir, "config.txt").writeText(configText)

        // 2.2 Generate AODconfig.txt (for Always-On Display / Dim Standby)
        val aodConfigText = buildString {
            appendLine("// Laps COROS Always-On Display Standby Configuration")
            appendLine("[watchface_name] = ${config.style.title} AOD")
            appendLine("[target_model] = ${config.model.modelName}")
            appendLine("[bg_color] = 0x000000")
            appendLine("[background_icon] = aod_background.png")
            appendLine("[background_icon_pos] = {0,0}")
            appendLine("[time_hour_high_pos] = {$x0,$y0}")
            appendLine("[time_hour_high_font] = aod_time_num_primary")
            appendLine("[time_hour_low_pos] = {$x1,$y1}")
            appendLine("[time_hour_low_font] = aod_time_num_secondary")
            appendLine("[time_minute_high_pos] = {$x2,$y2}")
            appendLine("[time_minute_high_font] = aod_time_num_secondary")
            appendLine("[time_minute_low_pos] = {$x3,$y3}")
            appendLine("[time_minute_low_font] = aod_time_num_primary")
            appendLine("[english_date_week_rect] = {108,54,168,80,right|vcenter}")
            appendLine("[english_date_week_font] = aod_date_week")
            appendLine("[english_date_month_rect] = {176,54,176,54}")
            appendLine("[english_date_month_font] = aod_date_month")
            appendLine("[english_date_day_rect] = {236,54,276,80,left|vcenter}")
            appendLine("[english_date_day_font] = aod_date_day")
        }
        File(targetDir, "AODconfig.txt").writeText(aodConfigText)

        // 3. Render background.png
        val bgBitmap = renderBackground(config, resolution)
        savePng(bgBitmap, File(targetDir, "background.png"))

        // 4. Render thmb.png (80x80 thumbnail)
        val thmbBitmap = Bitmap.createScaledBitmap(bgBitmap, 80, 80, true)
        savePng(thmbBitmap, File(targetDir, "thmb.png"))

        // 5. Render dual-tone time_num sprite sets (0.png - 9.png)
        val timeNumPrimaryDir = File(targetDir, "time_num_primary").apply { mkdirs() }
        val timeNumSecondaryDir = File(targetDir, "time_num_secondary").apply { mkdirs() }
        val aodTimeNumPrimaryDir = File(targetDir, "aod_time_num_primary").apply { mkdirs() }
        val aodTimeNumSecondaryDir = File(targetDir, "aod_time_num_secondary").apply { mkdirs() }

        val dateMonthDir = File(targetDir, "date_month").apply { mkdirs() }
        val dateWeekDir = File(targetDir, "date_week").apply { mkdirs() }
        val dateDayDir = File(targetDir, "date_day").apply { mkdirs() }
        val aodDateMonthDir = File(targetDir, "aod_date_month").apply { mkdirs() }
        val aodDateWeekDir = File(targetDir, "aod_date_week").apply { mkdirs() }
        val aodDateDayDir = File(targetDir, "aod_date_day").apply { mkdirs() }

        var assetCount = 5 // info.json, config.txt, AODconfig.txt, background.png, thmb.png

        for (digit in 0..9) {
            val dStr = digit.toString()
            savePng(renderUniformDigit(dStr, font, null, c1.toArgb(), textSizePx, strokeWidthPx, digitW, digitH, isAod = false), File(timeNumPrimaryDir, "$dStr.png"))
            savePng(renderUniformDigit(dStr, font, null, c2.toArgb(), textSizePx, strokeWidthPx, digitW, digitH, isAod = false), File(timeNumSecondaryDir, "$dStr.png"))
            savePng(renderUniformDigit(dStr, font, aodFont, c1.toArgb(), textSizePx, strokeWidthPx, digitW, digitH, isAod = true), File(aodTimeNumPrimaryDir, "$dStr.png"))
            savePng(renderUniformDigit(dStr, font, aodFont, c2.toArgb(), textSizePx, strokeWidthPx, digitW, digitH, isAod = true), File(aodTimeNumSecondaryDir, "$dStr.png"))
            assetCount += 4
        }

        // 5.1 Render Date & Complication Badges
        val regularFont = Typeface.create("sans-serif", Typeface.BOLD)
        val months = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        val weekdays = arrayOf("TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON")

        for (i in months.indices) {
            savePng(renderTextBadge(months[i], regularFont, c2.toArgb(), 54, 26), File(dateMonthDir, "$i.png"))
            savePng(renderTextBadge(months[i], regularFont, android.graphics.Color.LTGRAY, 54, 26), File(aodDateMonthDir, "$i.png"))
            assetCount += 2
        }

        for (i in weekdays.indices) {
            savePng(renderTextBadge(weekdays[i], regularFont, c2.toArgb(), 60, 26), File(dateWeekDir, "$i.png"))
            savePng(renderTextBadge(weekdays[i], regularFont, android.graphics.Color.LTGRAY, 60, 26), File(aodDateWeekDir, "$i.png"))
            assetCount += 2
        }

        for (i in 0..9) {
            savePng(renderTextBadge(i.toString(), regularFont, c1.toArgb(), 16, 26), File(dateDayDir, "$i.png"))
            savePng(renderTextBadge(i.toString(), regularFont, android.graphics.Color.WHITE, 16, 26), File(aodDateDayDir, "$i.png"))
            assetCount += 2
        }
        val aodBgBitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        val aodCanvas = Canvas(aodBgBitmap)
        aodCanvas.drawColor(android.graphics.Color.BLACK)
        savePng(aodBgBitmap, File(targetDir, "aod_background.png"))
        assetCount++

        // 6. Zip everything into clean COROS package
        val zipFileName = "Laps_${config.style.name}_${config.model.name}_${resolution}x${resolution}.zip"
        val zipFile = File(outputDir, zipFileName)
        zipDirectory(stagingDir, zipFile)

        // Cleanup staging
        stagingDir.deleteRecursively()

        return CompilationResult(
            zipFile = zipFile,
            totalAssets = assetCount,
            resolution = resolution,
            styleName = config.style.title
        )
    }

    private fun renderBackground(config: WatchFaceConfig, resolution: Int): Bitmap {
        val (c1, c2, _, _) = resolvePaletteColors(config)
        return renderDialBackground(config, resolution, c1, c2, isAod = false)
    }

    /**
     * Renders the 416x416 background dial for the selected WatchFaceStyle and ComplicationLayout.
     * For fixed 2-pod complication layouts, embeds the crisp 18x18 companion icons on the background
     * immediately to the left of each dynamic value rect so the physical watch displays both icon + number.
     */
    private fun renderDialBackground(
        config: WatchFaceConfig,
        resolution: Int,
        c1: Int,
        c2: Int,
        isAod: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = resolution / 2f
        val radius = center * 0.96f
        val scale = resolution / 416f

        // Fill pure AMOLED black (or palette background in active mode)
        canvas.drawColor(if (isAod) android.graphics.Color.BLACK else config.palette.backgroundColor.toArgb())

        val alphaScale = if (isAod) 0.4f else 1.0f

        when (config.style) {
            WatchFaceStyle.PIXEL_BUBBLY -> {
                // Pure uncluttered AMOLED canvas for interlocking numerals
            }

            WatchFaceStyle.STACKED_BOLD -> {
                // Subtle 12-hour perimeter index dots outside top/bottom complication zones
                val dotPaint = Paint().apply {
                    color = c2
                    alpha = (110 * alphaScale).toInt()
                    isAntiAlias = true
                }
                for (i in 0 until 12) {
                    if (i == 0 || i == 6) continue // Keep 12 o'clock date and 6 o'clock complication clear
                    val angle = (i * 30f - 90f) * (PI / 180f).toFloat()
                    canvas.drawCircle(
                        center + radius * 0.93f * cos(angle),
                        center + radius * 0.93f * sin(angle),
                        3.5f * scale,
                        dotPaint
                    )
                }
            }

            WatchFaceStyle.CONCENTRIC -> {
                // 60 radial minute ticks + concentric inner ring + center digital colon
                val tickPaint = Paint().apply {
                    color = c2
                    strokeWidth = (2.2f * scale).coerceAtLeast(1f)
                    isAntiAlias = true
                    strokeCap = Paint.Cap.ROUND
                }
                for (i in 0 until 60) {
                    // Leave clean arc openings at 12 o'clock (date) and 6 o'clock (complications)
                    if (i in 56..59 || i in 0..4 || i in 26..34) continue
                    val angle = (i * 6f - 90f) * (PI / 180f).toFloat()
                    val isMajor = i % 5 == 0
                    tickPaint.alpha = ((if (isMajor) 200 else 95) * alphaScale).toInt()
                    val len = (if (isMajor) 14f else 7f) * scale
                    val r1 = radius - len
                    val r2 = radius
                    canvas.drawLine(
                        center + r1 * cos(angle),
                        center + r1 * sin(angle),
                        center + r2 * cos(angle),
                        center + r2 * sin(angle),
                        tickPaint
                    )
                }

                val ringPaint = Paint().apply {
                    color = c1
                    alpha = (55 * alphaScale).toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * scale
                    isAntiAlias = true
                }
                canvas.drawCircle(center, center, 134f * scale, ringPaint)

                // Center digital colon between hours (x=46..196) and minutes (x=220..370)
                val colonPaint = Paint().apply {
                    color = c2
                    alpha = (220 * alphaScale).toInt()
                    isAntiAlias = true
                }
                canvas.drawCircle(208f * scale, 192f * scale, 5.5f * scale, colonPaint)
                canvas.drawCircle(208f * scale, 224f * scale, 5.5f * scale, colonPaint)
            }

            WatchFaceStyle.LAPS_TRACK -> {
                // Signature Laps running track stadium arcs on left and right flanks
                val trackRect = android.graphics.RectF(
                    center - 190f * scale,
                    center - 190f * scale,
                    center + 190f * scale,
                    center + 190f * scale
                )
                val groovePaint = Paint().apply {
                    color = c1
                    alpha = (40 * alphaScale).toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 10f * scale
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }
                val activePaint = Paint().apply {
                    color = c2
                    alpha = (165 * alphaScale).toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 10f * scale
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }
                // Left track arc (125 deg to 235 deg) & Right track arc (-55 deg to 55 deg)
                canvas.drawArc(trackRect, 125f, 110f, false, groovePaint)
                canvas.drawArc(trackRect, -55f, 110f, false, groovePaint)
                canvas.drawArc(trackRect, 125f, 82f, false, activePaint)
                canvas.drawArc(trackRect, -55f, 82f, false, activePaint)
            }

            WatchFaceStyle.EXPEDITION -> {
                // Tactical athletic bezel marks on left/right flanks
                val expPaint = Paint().apply {
                    color = c1
                    alpha = (150 * alphaScale).toInt()
                    strokeWidth = 3.2f * scale
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }
                for (i in 0 until 12) {
                    if (i == 0 || i == 6) continue
                    val angle = (i * 30f - 90f) * (PI / 180f).toFloat()
                    val len = (if (i % 3 == 0) 18f else 11f) * scale
                    canvas.drawLine(
                        center + (radius - len) * cos(angle),
                        center + (radius - len) * sin(angle),
                        center + radius * cos(angle),
                        center + radius * sin(angle),
                        expPaint
                    )
                }
            }
        }

        // Fixed 2-pod complication companion icons drawn on Active background
        if (!isAod) {
            val leftRightIcons: Pair<String, String>? = when (config.complicationLayout) {
                ComplicationLayout.STEPS_AND_BATTERY -> "step" to "battery"
                ComplicationLayout.STEPS_AND_CALORIES -> "step" to "kcal"
                ComplicationLayout.HR_AND_BATTERY -> "hr" to "battery"
                ComplicationLayout.HR_AND_CALORIES -> "hr" to "kcal"
                else -> null
            }
            if (leftRightIcons != null) {
                val iconSize = (18 * scale).toInt().coerceAtLeast(12)
                val leftBmp = renderControlIcon(leftRightIcons.first, c2, iconSize)
                val rightBmp = renderControlIcon(leftRightIcons.second, c2, iconSize)
                canvas.drawBitmap(leftBmp, 104f * scale, 368f * scale, null)
                canvas.drawBitmap(rightBmp, 216f * scale, 368f * scale, null)
            }
        }

        return bitmap
    }

    /**
     * Direct in-memory compilation of PACE Pro AMOLED watch face binary (.bin).
     * Compiles customized numerals, outline Always-On Display, and date text directly on device.
     */
    fun compileToBin(context: Context, config: WatchFaceConfig): ByteArray {
        val resolution = config.model.resolutionPx
        val scale = resolution / 416f
        fun sc(v: Int): Int = Math.round(v * scale)
        fun scEven(v: Int): Int {
            val r = Math.round(v * scale).coerceAtLeast(2)
            return if (r % 2 != 0) r + 1 else r
        }

        val baseFont = resolveTypeface(context, config) ?: Typeface.DEFAULT_BOLD
        val regularFont = Typeface.create("sans-serif", Typeface.BOLD)
        val (c1, c2, c3, c4) = resolvePaletteColors(config)
        val rawSpec = getStyleLayoutSpec(config.style)
        val spec = StyleLayoutSpec(
            digitW = scEven(rawSpec.digitW),
            digitH = scEven(rawSpec.digitH),
            digitFontSize = rawSpec.digitFontSize * scale,
            strokeWidthPx = (rawSpec.strokeWidthPx * scale).coerceAtLeast(2f),
            x0 = sc(rawSpec.x0), y0 = sc(rawSpec.y0),
            x1 = sc(rawSpec.x1), y1 = sc(rawSpec.y1),
            x2 = sc(rawSpec.x2), y2 = sc(rawSpec.y2),
            x3 = sc(rawSpec.x3), y3 = sc(rawSpec.y3),
            shiftDigitOne = rawSpec.shiftDigitOne
        )

        val templateHexId = when (resolution) {
            416 -> 0x416901
            260 -> 0x260902
            240 -> 0x240901
            else -> 0x416901
        }

        // 1. Bitmaps (Active & AOD backgrounds, plus composed 80x80 picker thumbnail)
        val bgBitmap = renderDialBackground(config, resolution, c1, c2, isAod = false)
        val aodBgBitmap = renderDialBackground(config, resolution, c1, c2, isAod = true)
        val previewForThumb = renderHardwarePreviewBitmap(context, config.copy(isAodMode = false))
        val thmbBitmap = Bitmap.createScaledBitmap(previewForThumb, 80, 80, true)

        // Digits (with style-specific optical shift for digit 1 when interlocking)
        val timeHourHigh = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c1, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = false) }
        val timeHourLow = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c2, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = false) }
        val timeMinuteHigh = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c3, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = false) }
        val timeMinuteLow = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c4, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = false) }

        // AOD Digits (using EXACT SAME FONT, opaque black interior + stroke outline)
        val aodTimeHourHigh = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c1, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = true) }
        val aodTimeHourLow = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c2, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = true) }
        val aodTimeMinuteHigh = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c3, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = spec.shiftDigitOne, isRightColumn = false, isAod = true) }
        val aodTimeMinuteLow = (0..9).map { renderDigitBitmap(baseFont, it.toString(), c4, spec.digitW, spec.digitH, spec.digitFontSize, spec.strokeWidthPx, isLeftColumn = false, isRightColumn = spec.shiftDigitOne, isAod = true) }

        // Date text badges
        val weekdays = arrayOf("TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON")
        val months = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

        val badgeW = scEven(54)
        val badgeH = scEven(26)
        val dayW = scEven(16)
        val iconSz = scEven(22)

        val dateWeek = weekdays.map { renderTextBadge(it, regularFont, c2, badgeW, badgeH) }
        val aodDateWeek = weekdays.map { renderTextBadge(it, regularFont, android.graphics.Color.LTGRAY, badgeW, badgeH) }

        val dateMonth = months.map { renderTextBadge(it, regularFont, c2, badgeW, badgeH) }
        val aodDateMonth = months.map { renderTextBadge(it, regularFont, android.graphics.Color.LTGRAY, badgeW, badgeH) }

        val dateDay = (0..9).map { renderTextBadge(it.toString(), regularFont, c1, dayW, badgeH) }
        val aodDateDay = (0..9).map { renderTextBadge(it.toString(), regularFont, android.graphics.Color.WHITE, dayW, badgeH) }

        // Companion unit symbols (% for battery, - for negative elevation/temp)
        val percentBadge = renderTextBadge("%", regularFont, c1, dayW, badgeH)
        val minusBadge = renderTextBadge("-", regularFont, c1, dayW, badgeH)

        // 2. Stream & blocks
        val headerSize = 0x1202
        var currentOffset = headerSize * 2

        val blockStream = ByteArrayOutputStream()
        fun writeBlock(blockBytes: ByteArray): Int {
            val offset = currentOffset
            blockStream.write(blockBytes)
            currentOffset += blockBytes.size
            return offset
        }

        val bgPtr = writeBlock(encodeImageBlock(bgBitmap))
        val thmbPtr = writeBlock(encodeImageBlock(thmbBitmap))
        val aodBgPtr = writeBlock(encodeImageBlock(aodBgBitmap))

        // Normal layout blocks
        val dateMonthPtr = writeBlock(encodeFramesBlock(dateMonth))
        val dateDayPtr = writeBlock(encodeFramesBlock(dateDay))
        // COROS firmware expects 20 frames (0..9 repeated twice) for battery_level_font (0x74)
        val batteryFontPtr = writeBlock(encodeFramesBlock(dateDay + dateDay))
        val dateWeekPtr = writeBlock(encodeFramesBlock(dateWeek))
        val percentIconPtr = writeBlock(encodeImageBlock(percentBadge))
        val minusIconPtr = writeBlock(encodeImageBlock(minusBadge))

        val hourHighPtr = writeBlock(encodeFramesBlock(timeHourHigh))
        val hourLowPtr = writeBlock(encodeFramesBlock(timeHourLow))
        val minHighPtr = writeBlock(encodeFramesBlock(timeMinuteHigh))
        val minLowPtr = writeBlock(encodeFramesBlock(timeMinuteLow))

        // AOD layout blocks
        val aodDateMonthPtr = writeBlock(encodeFramesBlock(aodDateMonth))
        val aodDateDayPtr = writeBlock(encodeFramesBlock(aodDateDay))
        val aodDateWeekPtr = writeBlock(encodeFramesBlock(aodDateWeek))

        val aodHourHighPtr = writeBlock(encodeFramesBlock(aodTimeHourHigh))
        val aodHourLowPtr = writeBlock(encodeFramesBlock(aodTimeHourLow))
        val aodMinHighPtr = writeBlock(encodeFramesBlock(aodTimeMinuteHigh))
        val aodMinLowPtr = writeBlock(encodeFramesBlock(aodTimeMinuteLow))

        // Control cycle icons for physical BACK button cycling
        val stepIcon = renderControlIcon("step", c1, iconSz)
        val kcalIcon = renderControlIcon("kcal", c1, iconSz)
        val hrIcon = renderControlIcon("hr", c1, iconSz)
        val batteryFrames = (0..11).map { renderBatteryStateIcon(it, c1, iconSz) }
        val elevIcon = renderControlIcon("elevation", c1, iconSz)

        val stepIconPtr = writeBlock(encodeImageBlock(stepIcon))
        val kcalIconPtr = writeBlock(encodeImageBlock(kcalIcon))
        val hrIconPtr = writeBlock(encodeImageBlock(hrIcon))
        val batteryIconDirPtr = writeBlock(encodeFramesBlock(batteryFrames))
        val elevIconPtr = writeBlock(encodeImageBlock(elevIcon))

        // 3. Normal Header (0x1202 bytes)
        val normalHeader = ByteArray(headerSize)
        val nb = ByteBuffer.wrap(normalHeader).order(ByteOrder.LITTLE_ENDIAN)

        nb.put(0, '6'.code.toByte()); nb.put(1, '1'.code.toByte()); nb.put(2, '4'.code.toByte()); nb.put(3, 'A'.code.toByte())
        nb.putInt(4, templateHexId)
        nb.putInt(0x12, 0); nb.putInt(0x16, 0)
        nb.putInt(0x1a, bgPtr)
        nb.putInt(0x1e, thmbPtr)
        nb.put(0x22, 0)
        nb.putShort(0x138, headerSize.toShort())
        nb.put(0x13a, 3) // WF_VERSION_3 (enables battery_level_percent_icon & control_percent_icon)
        nb.put(0x13b, 1)

        // Centered Top Date Cluster (span x = 132..284, horizontal center = 208.0, y = 26..52):
        // Month: point rect {192, 26, 192, 26} triggers COROS firmware single-label 12-month string mode
        writeRect(normalHeader, 0x13c, sc(192), sc(26), sc(192), sc(26))
        nb.putInt(0x146, dateMonthPtr)

        // Day: rect {252, 26, 292, 52, left|vcenter}
        writeRect(normalHeader, 0x15c, sc(252), sc(26), sc(292), sc(52), align = 17)
        nb.putInt(0x166, dateDayPtr)

        // Week: rect {126, 26, 186, 52, right|vcenter}
        writeRect(normalHeader, 0x17c, sc(126), sc(26), sc(186), sc(52), align = 20)
        nb.putInt(0x186, dateWeekPtr)

        nb.putInt(0x1b0, spec.x0); nb.putInt(0x1b4, spec.y0)
        nb.putInt(0x1b8, hourHighPtr)

        nb.putInt(0x1be, spec.x1); nb.putInt(0x1c2, spec.y1)
        nb.putInt(0x1c6, hourLowPtr)

        nb.putInt(0x1cc, spec.x2); nb.putInt(0x1d0, spec.y2)
        nb.putInt(0x1d4, minHighPtr)

        nb.putInt(0x1da, spec.x3); nb.putInt(0x1de, spec.y3)
        nb.putInt(0x1e2, minLowPtr)

        when (config.complicationLayout) {
            ComplicationLayout.STEPS_AND_BATTERY -> {
                writeRect(normalHeader, 0x92, sc(126), sc(364), sc(206), sc(390), align = 17) // step_rect (left|vcenter)
                nb.putInt(0x9c, dateDayPtr)

                writeRect(normalHeader, 0x6a, sc(238), sc(364), sc(318), sc(390), align = 17) // battery_level_rect (left|vcenter)
                nb.putInt(0x74, batteryFontPtr)
                nb.putInt(0x7a, percentIconPtr) // battery_level_percent_icon
            }
            ComplicationLayout.STEPS_AND_CALORIES -> {
                writeRect(normalHeader, 0x92, sc(126), sc(364), sc(206), sc(390), align = 17) // step_rect
                nb.putInt(0x9c, dateDayPtr)

                writeRect(normalHeader, 0xde, sc(238), sc(364), sc(318), sc(390), align = 17) // kcal_rect
                nb.putInt(0xe8, dateDayPtr)
            }
            ComplicationLayout.HR_AND_BATTERY -> {
                // Heart rate at 0x316: 34-byte record
                val hrRecord = ByteArray(34)
                writeRect(hrRecord, 0, sc(126), sc(364), sc(206), sc(390), align = 17)
                val hrRecordBb = ByteBuffer.wrap(hrRecord).order(ByteOrder.LITTLE_ENDIAN)
                hrRecordBb.putInt(10, dateDayPtr)
                val hrPtr = writeBlock(hrRecord)
                nb.putInt(0x316, hrPtr)

                writeRect(normalHeader, 0x6a, sc(238), sc(364), sc(318), sc(390), align = 17) // battery_level_rect
                nb.putInt(0x74, batteryFontPtr)
                nb.putInt(0x7a, percentIconPtr)
            }
            ComplicationLayout.HR_AND_CALORIES -> {
                val hrRecord = ByteArray(34)
                writeRect(hrRecord, 0, sc(126), sc(364), sc(206), sc(390), align = 17)
                val hrRecordBb = ByteBuffer.wrap(hrRecord).order(ByteOrder.LITTLE_ENDIAN)
                hrRecordBb.putInt(10, dateDayPtr)
                val hrPtr = writeBlock(hrRecord)
                nb.putInt(0x316, hrPtr)

                writeRect(normalHeader, 0xde, sc(238), sc(364), sc(318), sc(390), align = 17) // kcal_rect
                nb.putInt(0xe8, dateDayPtr)
            }
            ComplicationLayout.BACK_BUTTON_CYCLE -> {
                // Interactive control panel cycling on physical BACK/LAP button
                // rect_control1_pos at 0x33a (two 32-bit ints: x, y) must be {0, 0}
                nb.putInt(0x33a, 0)
                nb.putInt(0x33e, 0)

                // 1. Control Battery Level (28-byte record at 0x35a)
                nb.putInt(0x35a, sc(170)); nb.putInt(0x35e, sc(366)) // position
                nb.putInt(0x362, batteryIconDirPtr)          // 12-frame battery state icon dir
                writeRect(normalHeader, 0x366, sc(198), sc(364), sc(278), sc(390), align = 17) // rect (left|vcenter)
                nb.putInt(0x370, dateDayPtr)                 // font
                nb.putInt(0x4ca, percentIconPtr)             // control_percent_icon

                // 2. Control HR (28-byte record at 0x3c4)
                nb.putInt(0x3c4, sc(178)); nb.putInt(0x3c8, sc(366))
                nb.putInt(0x3cc, hrIconPtr)
                writeRect(normalHeader, 0x3d0, sc(206), sc(364), sc(286), sc(390), align = 17)
                nb.putInt(0x3da, dateDayPtr)

                // 3. Control Elevation (28-byte record at 0x3fc)
                nb.putInt(0x3fc, sc(170)); nb.putInt(0x400, sc(366))
                nb.putInt(0x404, elevIconPtr)
                writeRect(normalHeader, 0x408, sc(198), sc(364), sc(278), sc(390), align = 17)
                nb.putInt(0x412, dateDayPtr)
                nb.putInt(0x392, minusIconPtr)               // control_negative_sign_icon

                // 4. Control Step (28-byte record at 0x464)
                nb.putInt(0x464, sc(162)); nb.putInt(0x468, sc(366))
                nb.putInt(0x46c, stepIconPtr)
                writeRect(normalHeader, 0x470, sc(190), sc(364), sc(290), sc(390), align = 17)
                nb.putInt(0x47a, dateDayPtr)

                // 5. Control Kcal (28-byte record at 0x480)
                nb.putInt(0x480, sc(170)); nb.putInt(0x484, sc(366))
                nb.putInt(0x488, kcalIconPtr)
                writeRect(normalHeader, 0x48c, sc(198), sc(364), sc(278), sc(390), align = 17)
                nb.putInt(0x496, dateDayPtr)
            }
            ComplicationLayout.MINIMALIST -> {
                // Pure dial, no bottom complications
            }
        }

        nb.putInt(0x322, headerSize)

        // 4. AOD Header (0x1202 bytes)
        val aodHeader = ByteArray(headerSize)
        val ab = ByteBuffer.wrap(aodHeader).order(ByteOrder.LITTLE_ENDIAN)

        ab.put(0, '6'.code.toByte()); ab.put(1, '1'.code.toByte()); ab.put(2, '4'.code.toByte()); ab.put(3, 'A'.code.toByte())
        ab.putInt(4, -1)
        ab.putInt(0x12, 0); ab.putInt(0x16, 0)
        ab.putInt(0x1a, aodBgPtr)
        ab.put(0x22, 0)
        ab.putShort(0x138, headerSize.toShort())
        ab.put(0x13b, 1)

        writeRect(aodHeader, 0x13c, sc(192), sc(26), sc(192), sc(26))
        ab.putInt(0x146, aodDateMonthPtr)

        writeRect(aodHeader, 0x15c, sc(252), sc(26), sc(292), sc(52), align = 17)
        ab.putInt(0x166, aodDateDayPtr)

        writeRect(aodHeader, 0x17c, sc(126), sc(26), sc(186), sc(52), align = 20)
        ab.putInt(0x186, aodDateWeekPtr)

        ab.putInt(0x1b0, spec.x0); ab.putInt(0x1b4, spec.y0)
        ab.putInt(0x1b8, aodHourHighPtr)

        ab.putInt(0x1be, spec.x1); ab.putInt(0x1c2, spec.y1)
        ab.putInt(0x1c6, aodHourLowPtr)

        ab.putInt(0x1cc, spec.x2); ab.putInt(0x1d0, spec.y2)
        ab.putInt(0x1d4, aodMinHighPtr)

        ab.putInt(0x1da, spec.x3); ab.putInt(0x1de, spec.y3)
        ab.putInt(0x1e2, aodMinLowPtr)

        // 5. Assemble final byte array
        val blocks = blockStream.toByteArray()
        val totalLen = normalHeader.size + aodHeader.size + blocks.size
        val bin = ByteArray(totalLen)
        System.arraycopy(normalHeader, 0, bin, 0, normalHeader.size)
        System.arraycopy(aodHeader, 0, bin, normalHeader.size, aodHeader.size)
        System.arraycopy(blocks, 0, bin, normalHeader.size + aodHeader.size, blocks.size)

        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(8, totalLen - 18)
        val crc = crc16(bin, 18, totalLen - 18)
        bb.putShort(12, crc.toShort())

        return bin
    }

    private fun renderDigitBitmap(
        font: Typeface?,
        digit: String,
        fillColor: Int,
        width: Int,
        height: Int,
        fontSizePx: Float,
        strokeWidthPx: Float,
        isLeftColumn: Boolean = false,
        isRightColumn: Boolean = false,
        isAod: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val xShift = when {
            digit == "1" && isLeftColumn -> 12f
            digit == "1" && isRightColumn -> -12f
            else -> 0f
        }
        val x = (width / 2f) + xShift

        val strokePaint = Paint().apply {
            isAntiAlias = true
            this.typeface = font ?: Typeface.DEFAULT_BOLD
            this.textSize = fontSizePx
            this.textAlign = Paint.Align.CENTER
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokeWidthPx
            this.strokeJoin = Paint.Join.ROUND
            this.strokeCap = Paint.Cap.ROUND
            this.color = android.graphics.Color.BLACK
        }
        val y = (height / 2f) - ((strokePaint.descent() + strokePaint.ascent()) / 2f)

        val glyphPath = android.graphics.Path()
        val textMeasurePaint = Paint().apply {
            isAntiAlias = true
            this.typeface = font ?: Typeface.DEFAULT_BOLD
            this.textSize = fontSizePx
            this.textAlign = Paint.Align.CENTER
        }
        textMeasurePaint.getTextPath(digit, 0, digit.length, x, y, glyphPath)

        val cleanPath = android.graphics.Path()
        val opSuccess = try {
            cleanPath.op(glyphPath, glyphPath, android.graphics.Path.Op.UNION)
        } catch (_: Exception) {
            false
        }
        val targetPath = if (opSuccess && !cleanPath.isEmpty) cleanPath else glyphPath

        if (isAod) {
            // 1. Black contour separator to cleanly occlude underlying digits in interlocking layouts
            canvas.drawPath(targetPath, strokePaint)

            // 2. Solid opaque black fill inside the digit so bottom/right digits cleanly mask upper/left digits
            val solidBlackPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.FILL
                this.color = android.graphics.Color.BLACK
            }
            canvas.drawPath(targetPath, solidBlackPaint)

            // 3. Crisp wireframe stroke outline drawn in the selected accent color.
            // Using the unified targetPath removes internal self-overlapping lines (such as
            // crossbars in '4' or loops in '9'), rendering genuinely hollow wireframe numerals
            // with no self-overlapping strokes crossing each other.
            val aodPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.STROKE
                this.strokeWidth = 3.5f
                this.strokeJoin = Paint.Join.ROUND
                this.strokeCap = Paint.Cap.ROUND
                this.color = fillColor
            }
            canvas.drawPath(targetPath, aodPaint)
        } else {
            // Draw prominent black cutout contour separator first, matching authentic interlocking sticker effect
            canvas.drawPath(targetPath, strokePaint)

            val fillPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.FILL
                this.color = fillColor
            }
            canvas.drawPath(targetPath, fillPaint)
        }
        return bitmap
    }

    private fun toLut256(bitmap: Bitmap): Pair<ByteArray, ByteArray> {
        val w = bitmap.width
        val h = bitmap.height
        val totalPixels = w * h
        val indices = ByteArray(totalPixels)
        val palette = ByteArray(1024)

        val colorMap = mutableMapOf<Int, Int>()
        var colorCount = 0

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in 0 until totalPixels) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val normalized = if (a == 0) 0 else argb
            var idx = colorMap[normalized]
            if (idx == null) {
                if (colorCount < 256) {
                    idx = colorCount++
                    colorMap[normalized] = idx
                    val slot = ((idx and 15) shl 4) or (idx shr 4)
                    val pOffset = slot * 4
                    palette[pOffset] = r.toByte()
                    palette[pOffset + 1] = g.toByte()
                    palette[pOffset + 2] = b.toByte()
                    palette[pOffset + 3] = a.toByte()
                } else {
                    idx = 0
                }
            }
            indices[i] = idx.toByte()
        }

        val frameData = ByteArray(totalPixels + 1024)
        System.arraycopy(indices, 0, frameData, 0, totalPixels)
        System.arraycopy(palette, 0, frameData, totalPixels, 1024)
        return Pair(indices, frameData)
    }

    private fun cbzCompress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        var i = 0
        while (i < input.size) {
            val v = input[i].toInt() and 0xFF
            var run = 1
            while (run < 63 && i + run < input.size && (input[i + run].toInt() and 0xFF) == v) {
                run++
            }
            if (run > 1 || v >= 0xC0) {
                out.write(0xC0 or run)
                out.write(v)
            } else {
                out.write(v)
            }
            i += run
        }
        return out.toByteArray()
    }

    private fun encodeImageBlock(bitmap: Bitmap): ByteArray {
        val (_, frameData) = toLut256(bitmap)
        val compressed = cbzCompress(frameData)
        val useRle = compressed.size < frameData.size
        val data = if (useRle) compressed else frameData

        val block = ByteArray(10 + 8 + data.size)
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, bitmap.width.toShort())
        bb.putShort(2, bitmap.height.toShort())
        bb.put(4, if (useRle) 2.toByte() else 0.toByte())
        bb.put(5, 0x20.toByte())
        bb.put(6, 1.toByte())
        bb.put(7, 1.toByte())
        bb.putShort(8, 0.toShort())
        bb.putInt(10, 0)
        bb.putInt(14, data.size)
        System.arraycopy(data, 0, block, 18, data.size)
        return block
    }

    private fun encodeFramesBlock(bitmaps: List<Bitmap>): ByteArray {
        require(bitmaps.isNotEmpty()) { "Frames list cannot be empty" }
        val framesData = bitmaps.map { toLut256(it).second }
        val firstCompressed = cbzCompress(framesData[0])
        val useRle = firstCompressed.size < framesData[0].size
        val processedParts = framesData.map { if (useRle) cbzCompress(it) else it }

        val tableSize = (bitmaps.size + 1) * 4
        var dataSize = 0
        for (part in processedParts) dataSize += part.size

        val block = ByteArray(10 + tableSize + dataSize)
        val bb = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, bitmaps[0].width.toShort())
        bb.putShort(2, bitmaps[0].height.toShort())
        bb.put(4, if (useRle) 2.toByte() else 0.toByte())
        bb.put(5, 0x20.toByte())
        bb.put(6, bitmaps.size.toByte())
        bb.put(7, 1.toByte())
        bb.putShort(8, 0.toShort())

        var end = 0
        bb.putInt(10, 0)
        for (i in processedParts.indices) {
            end += processedParts[i].size
            bb.putInt(10 + (i + 1) * 4, end)
        }

        var writePos = 10 + tableSize
        for (part in processedParts) {
            System.arraycopy(part, 0, block, writePos, part.size)
            writePos += part.size
        }
        return block
    }

    private fun writeRect(header: ByteArray, offset: Int, x0: Int, y0: Int, x1: Int, y1: Int, align: Byte = 0) {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(offset, x0.toShort())
        bb.putShort(offset + 2, y0.toShort())
        bb.putShort(offset + 4, x1.toShort())
        bb.putShort(offset + 6, y1.toShort())
        header[offset + 8] = align
    }

    private val crcTable = IntArray(256) { n ->
        var c = n shl 8
        for (bit in 0 until 8) {
            c = if ((c and 0x8000) != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF
        }
        c
    }

    private fun crc16(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val byte = bytes[i].toInt() and 0xFF
            crc = ((crc shl 8) xor crcTable[((crc ushr 8) xor byte) and 0xFF]) and 0xFFFF
        }
        return crc
    }

    private fun renderUniformDigit(
        digit: String,
        bubblyFont: Typeface?,
        aodFont: Typeface?,
        fillColor: Int,
        fontSizePx: Float,
        strokeWidthPx: Float,
        width: Int,
        height: Int,
        isAod: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val x = width / 2f

        val strokePaint = Paint().apply {
            isAntiAlias = true
            typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = fontSizePx
            this.textAlign = Paint.Align.CENTER
            this.style = Paint.Style.STROKE
            this.strokeWidth = strokeWidthPx
            this.strokeJoin = Paint.Join.ROUND
            this.strokeCap = Paint.Cap.ROUND
            this.color = android.graphics.Color.BLACK
        }
        val y = (height / 2f) - ((strokePaint.descent() + strokePaint.ascent()) / 2f)

        val glyphPath = android.graphics.Path()
        val textMeasurePaint = Paint().apply {
            isAntiAlias = true
            this.typeface = bubblyFont ?: Typeface.DEFAULT_BOLD
            this.textSize = fontSizePx
            this.textAlign = Paint.Align.CENTER
        }
        textMeasurePaint.getTextPath(digit, 0, digit.length, x, y, glyphPath)

        val cleanPath = android.graphics.Path()
        val opSuccess = try {
            cleanPath.op(glyphPath, glyphPath, android.graphics.Path.Op.UNION)
        } catch (_: Exception) {
            false
        }
        val targetPath = if (opSuccess && !cleanPath.isEmpty) cleanPath else glyphPath

        if (isAod) {
            canvas.drawPath(targetPath, strokePaint)

            val solidBlackPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.FILL
                this.color = android.graphics.Color.BLACK
            }
            canvas.drawPath(targetPath, solidBlackPaint)

            val aodPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.STROKE
                this.strokeWidth = 3.5f
                this.strokeJoin = Paint.Join.ROUND
                this.strokeCap = Paint.Cap.ROUND
                this.color = fillColor
            }
            canvas.drawPath(targetPath, aodPaint)
        } else {
            canvas.drawPath(targetPath, strokePaint)

            val fillPaint = Paint().apply {
                isAntiAlias = true
                this.style = Paint.Style.FILL
                this.color = fillColor
            }
            canvas.drawPath(targetPath, fillPaint)
        }

        return bitmap
    }

    private fun renderTextBadge(
        text: String,
        font: Typeface?,
        color: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            this.color = color
            this.textSize = 22f * (height / 26f)
            this.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            this.textAlign = Paint.Align.CENTER
        }
        val y = (height / 2f) - ((paint.descent() + paint.ascent()) / 2f) - (2f * (height / 26f))
        canvas.drawText(text, width / 2f, y, paint)
        return bitmap
    }

    private fun renderBatteryStateIcon(stateIndex: Int, color: Int, size: Int = 22): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size / 22f
        canvas.scale(s, s)
        val strokePaint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        // Battery cell body and positive terminal
        canvas.drawRoundRect(android.graphics.RectF(2f, 5f, 17f, 17f), 3f, 3f, strokePaint)
        canvas.drawRect(android.graphics.RectF(17f, 8f, 19.5f, 14f), fillPaint)

        // COROS 12-frame convention: 0 = charging, 1 = 0%, 2..11 = 10%..100%
        val fraction = when {
            stateIndex <= 0 -> 0.85f
            stateIndex == 1 -> 0.12f
            else -> ((stateIndex - 1) / 10f).coerceIn(0.15f, 1.0f)
        }
        val fillRight = 4.5f + (10.0f * fraction)
        canvas.drawRoundRect(android.graphics.RectF(4.5f, 7.5f, fillRight, 14.5f), 1.5f, 1.5f, fillPaint)
        return bitmap
    }

    private fun renderControlIcon(kind: String, color: Int, size: Int = 22): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size / 22f
        canvas.scale(s, s)
        val strokePaint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint().apply {
            this.color = color
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        when (kind) {
            "step" -> {
                // Two athletic footprint marks
                canvas.drawOval(android.graphics.RectF(3f, 8f, 9f, 18f), fillPaint)
                canvas.drawCircle(6f, 5f, 2.2f, fillPaint)
                canvas.drawOval(android.graphics.RectF(11f, 4f, 17f, 14f), fillPaint)
                canvas.drawCircle(14f, 17f, 2.2f, fillPaint)
            }
            "kcal" -> {
                // Energetic calorie flame
                val path = android.graphics.Path().apply {
                    moveTo(11f, 2f)
                    cubicTo(11f, 2f, 6f, 7f, 6f, 12f)
                    cubicTo(6f, 16f, 8f, 20f, 11f, 20f)
                    cubicTo(14f, 20f, 16f, 16f, 16f, 12f)
                    cubicTo(16f, 7f, 11f, 2f, 11f, 2f)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
            "hr" -> {
                // Clean heart icon
                val path = android.graphics.Path().apply {
                    moveTo(11f, 7f)
                    cubicTo(11f, 4f, 8f, 3f, 6f, 5f)
                    cubicTo(3f, 8f, 5f, 12f, 11f, 19f)
                    cubicTo(17f, 12f, 19f, 8f, 16f, 5f)
                    cubicTo(14f, 3f, 11f, 4f, 11f, 7f)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
            "battery" -> {
                // Battery cell with terminal and charge bar
                canvas.drawRoundRect(android.graphics.RectF(2f, 5f, 17f, 17f), 3f, 3f, strokePaint)
                canvas.drawRect(android.graphics.RectF(17f, 8f, 19.5f, 14f), fillPaint)
                canvas.drawRoundRect(android.graphics.RectF(4.5f, 7.5f, 12.5f, 14.5f), 1.5f, 1.5f, fillPaint)
            }
            "elevation" -> {
                // Dual mountain peaks
                val path = android.graphics.Path().apply {
                    moveTo(2f, 18f)
                    lineTo(9f, 6f)
                    lineTo(13f, 12f)
                    lineTo(16f, 8f)
                    lineTo(20f, 18f)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
        }
        return bitmap
    }

    private fun resolveTypeface(context: Context, config: WatchFaceConfig): Typeface? {
        return try {
            ResourcesCompat.getFont(context, config.font.fontRes)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path
                    val entry = ZipEntry(relativePath)
                    zos.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}
