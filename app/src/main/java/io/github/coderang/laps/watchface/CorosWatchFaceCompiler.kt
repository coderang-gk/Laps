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
import java.io.File
import java.io.FileOutputStream
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

    fun compile(
        context: Context,
        config: WatchFaceConfig,
        outputDir: File = context.cacheDir
    ): CompilationResult {
        val resolution = config.model.resolutionPx
        val folderName = "watchface_${resolution}x${resolution}"
        val stagingDir = File(outputDir, "staging_${System.currentTimeMillis()}").apply { mkdirs() }
        val targetDir = File(stagingDir, folderName).apply { mkdirs() }

        // 1. Generate info.json
        val infoJson = JSONObject().apply {
            put("name", config.style.title)
            put("model", config.model.modelName)
            put("resolution", "${resolution}x${resolution}")
            put("version", "1.3.0")
            put("author", "Laps M3 Expressive Studio")
            put("palette", config.palette.displayName)
            put("generator", "Laps CorosWatchFaceCompiler")
        }
        File(stagingDir, "info.json").writeText(infoJson.toString(2))

        // 2. Generate config.txt
        val configText = buildString {
            appendLine("// Laps COROS Watch Face Configuration")
            appendLine("[watchface_name] = ${config.style.title}")
            appendLine("[target_model] = ${config.model.modelName}")
            appendLine("[bg_color] = 0x%06X".format(config.palette.backgroundColor.toArgb() and 0xFFFFFF))
            appendLine("[background_icon] = background.png")
            appendLine("[background_icon_pos] = {0,0}")
            appendLine("[time_num_dir] = time_num")
            val yOffset = (resolution * 0.38f).toInt()
            val xOffset = (resolution * 0.16f).toInt()
            appendLine("[time_num_pos] = {$xOffset,$yOffset}")
            appendLine("[time_colon_icon] = colon.png")
            appendLine("[date_format] = EEE_MMM_D")
        }
        File(targetDir, "config.txt").writeText(configText)

        // 3. Render background.png
        val bgBitmap = renderBackground(config, resolution)
        savePng(bgBitmap, File(targetDir, "background.png"))

        // 4. Render thmb.png (80x80 thumbnail)
        val thmbBitmap = Bitmap.createScaledBitmap(bgBitmap, 80, 80, true)
        savePng(thmbBitmap, File(targetDir, "thmb.png"))

        // 5. Render time_num sprite set (0.png - 9.png)
        val timeNumDir = File(targetDir, "time_num").apply { mkdirs() }
        val font = resolveTypeface(context, config)
        var assetCount = 3 // info.json, config.txt, background.png, thmb.png

        for (digit in 0..9) {
            val digitBitmap = renderDigit(
                digit = digit.toString(),
                font = font,
                textColor = config.palette.primaryColor.toArgb(),
                fontSizePx = (resolution * 0.38f),
                hasOutline = (config.style == WatchFaceStyle.PIXEL_BUBBLY)
            )
            savePng(digitBitmap, File(timeNumDir, "$digit.png"))
            assetCount++
        }

        // Render colon.png
        val colonBitmap = renderDigit(
            digit = ":",
            font = font,
            textColor = config.palette.primaryColor.toArgb(),
            fontSizePx = (resolution * 0.34f)
        )
        savePng(colonBitmap, File(targetDir, "colon.png"))
        assetCount++

        // 6. Generate Always-On Display (AOD / Dim Standby) assets for AMOLED (e.g. PACE Pro)
        val aodFont = try {
            ResourcesCompat.getFont(context, R.font.google_sans_flex_aod)
        } catch (_: Exception) {
            font
        }
        val aodTimeNumDir = File(targetDir, "aod_time_num").apply { mkdirs() }
        for (digit in 0..9) {
            val aodDigitBitmap = renderDigit(
                digit = digit.toString(),
                font = aodFont ?: font,
                textColor = config.palette.primaryColor.toArgb(),
                fontSizePx = (resolution * 0.38f),
                hasOutline = (config.style == WatchFaceStyle.PIXEL_BUBBLY),
                isAod = true
            )
            savePng(aodDigitBitmap, File(aodTimeNumDir, "$digit.png"))
            assetCount++
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
        val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = resolution / 2f
        val radius = center * 0.96f

        // Fill background
        val bgPaint = Paint().apply {
            color = config.palette.backgroundColor.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center, bgPaint)

        // Render Dial based on Style
        when (config.style) {
            WatchFaceStyle.CONCENTRIC -> {
                // Minute ticks
                val tickPaint = Paint().apply {
                    color = config.palette.secondaryColor.toArgb()
                    strokeWidth = (resolution * 0.008f).coerceAtLeast(1f)
                    isAntiAlias = true
                    strokeCap = Paint.Cap.ROUND
                }
                for (i in 0 until 60) {
                    val angle = (i * 6f - 90f) * (PI / 180f).toFloat()
                    val isMajor = i % 5 == 0
                    val len = if (isMajor) resolution * 0.06f else resolution * 0.03f
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

                // Inner Concentric Track
                val ringPaint = Paint().apply {
                    color = config.palette.primaryColor.toArgb()
                    alpha = 40
                    style = Paint.Style.STROKE
                    strokeWidth = resolution * 0.012f
                    isAntiAlias = true
                }
                canvas.drawCircle(center, center, radius * 0.76f, ringPaint)
            }

            WatchFaceStyle.LAPS_TRACK -> {
                val trackPaint = Paint().apply {
                    color = config.palette.primaryColor.toArgb()
                    alpha = 45
                    style = Paint.Style.STROKE
                    strokeWidth = resolution * 0.10f
                    strokeCap = Paint.Cap.ROUND
                    isAntiAlias = true
                }
                canvas.drawCircle(center, center, radius * 0.82f, trackPaint)
            }

            WatchFaceStyle.EXPEDITION -> {
                val expPaint = Paint().apply {
                    color = config.palette.primaryColor.toArgb()
                    strokeWidth = resolution * 0.015f
                    isAntiAlias = true
                }
                for (i in 0 until 12) {
                    val angle = (i * 30f - 90f) * (PI / 180f).toFloat()
                    val len = resolution * 0.08f
                    canvas.drawLine(
                        center + (radius - len) * cos(angle),
                        center + (radius - len) * sin(angle),
                        center + radius * cos(angle),
                        center + radius * sin(angle),
                        expPaint
                    )
                }
            }

            WatchFaceStyle.PIXEL_BUBBLY -> {
                // Clean canvas with subtle peripheral indicator dots
                val dotPaint = Paint().apply {
                    color = config.palette.secondaryColor.toArgb()
                    alpha = 45
                    isAntiAlias = true
                }
                for (i in 0 until 12) {
                    val angle = (i * 30f - 90f) * (PI / 180f).toFloat()
                    canvas.drawCircle(
                        center + radius * 0.92f * cos(angle),
                        center + radius * 0.92f * sin(angle),
                        resolution * 0.008f,
                        dotPaint
                    )
                }
            }

            WatchFaceStyle.STACKED_BOLD -> {
                val dotPaint = Paint().apply {
                    color = config.palette.primaryColor.toArgb()
                    alpha = 60
                    isAntiAlias = true
                }
                for (i in 0 until 12) {
                    val angle = (i * 30f - 90f) * (PI / 180f).toFloat()
                    canvas.drawCircle(
                        center + radius * 0.90f * cos(angle),
                        center + radius * 0.90f * sin(angle),
                        resolution * 0.010f,
                        dotPaint
                    )
                }
            }
        }

        return bitmap
    }

    private fun renderDigit(
        digit: String,
        font: Typeface?,
        textColor: Int,
        fontSizePx: Float,
        hasOutline: Boolean = false,
        isAod: Boolean = false
    ): Bitmap {
        val paint = Paint().apply {
            isAntiAlias = true
            color = textColor
            textSize = fontSizePx
            typeface = font ?: Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            if (isAod && font != null) {
                style = Paint.Style.FILL
            } else if (isAod) {
                style = Paint.Style.STROKE
                strokeWidth = fontSizePx * 0.08f
            }
        }
        val bounds = Rect()
        paint.getTextBounds(digit, 0, digit.length, bounds)

        val padding = if (hasOutline) (fontSizePx * 0.16f).toInt() else 16
        val width = (bounds.width() + padding).coerceAtLeast(24)
        val height = (bounds.height() + padding).coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val x = width / 2f
        val y = (height / 2f) - ((paint.descent() + paint.ascent()) / 2f)

        if (hasOutline) {
            val strokePaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.BLACK
                textSize = fontSizePx
                typeface = font ?: Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE
                strokeWidth = fontSizePx * 0.10f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawText(digit, x, y, strokePaint)
        }

        canvas.drawText(digit, x, y, paint)
        return bitmap
    }

    private fun resolveTypeface(context: Context, config: WatchFaceConfig): Typeface? {
        return try {
            when (config.style) {
                WatchFaceStyle.PIXEL_BUBBLY ->
                    ResourcesCompat.getFont(context, R.font.google_sans_flex_bubbly)
                WatchFaceStyle.STACKED_BOLD ->
                    ResourcesCompat.getFont(context, R.font.google_sans_flex_stacked)
                WatchFaceStyle.CONCENTRIC ->
                    ResourcesCompat.getFont(context, R.font.google_sans_clock_600)
                else ->
                    ResourcesCompat.getFont(context, R.font.google_sans_flex_horizontal)
            }
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
