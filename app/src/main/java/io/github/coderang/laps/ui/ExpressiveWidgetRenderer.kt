package io.github.coderang.laps.ui

import android.content.Context
import android.graphics.*
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import io.github.coderang.laps.data.LapsPayload
import io.github.coderang.laps.data.ExpressiveTileType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object ExpressiveWidgetRenderer {

    fun renderTileBitmap(
        context: Context,
        tileType: ExpressiveTileType,
        payload: LapsPayload,
        widthPx: Int,
        heightPx: Int,
        styleIndexOverride: Int? = null
    ): Bitmap {
        val size = min(widthPx.coerceAtLeast(360), heightPx.coerceAtLeast(360)).coerceAtMost(900)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = ExpressiveTheme.getPalette(context)

        val styleIdx = styleIndexOverride ?: tileType.ordinal
        val s = size.toFloat()

        when (styleIdx % 12) {
            0 -> drawStackedCapsuleBars(canvas, s, payload, p)
            1 -> drawConcentricDonutRing(canvas, s, payload, p)
            2 -> drawDualContrastPods(canvas, s, payload, p)
            3 -> drawPolygonShapeMatrix(canvas, s, payload, p)
            4 -> drawCookie12ScallopSeal(canvas, s, payload, p)
            5 -> drawLiquidHorizonCookie9(canvas, s, payload, p)
            6 -> drawAsymmetricWorkoutStack(canvas, s, payload, p)
            7 -> drawSlantedPillWithSatellites(canvas, s, payload, p)
            8 -> drawConcentricTonalEllipses(canvas, s, payload, p)
            9 -> drawCookie12OuterFlower(canvas, s, payload, p)
            10 -> drawExpressiveSplitBlocks(canvas, s, payload, p)
            11 -> drawEvoLabSplitsClover4(canvas, s, payload, p)
        }
        return bmp
    }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = Paint.Style.FILL
        }

    private fun textPaint(
        color: Int,
        sizePx: Float,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        condensed: Boolean = false
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = sizePx
        this.textAlign = align
        val family = if (condensed) "sans-serif-condensed" else "sans-serif"
        this.typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * Official M3 RoundedPolygon Cookie / Clover / Starburst builder (androidx.graphics.shapes).
     * Uses lobe-adaptive CornerRounding so waves are never flattened and normalizes the Path
     * to match [outerRadius] edge-to-edge.
     */
    private fun createM3CookiePath(
        cx: Float,
        cy: Float,
        outerRadius: Float,
        lobes: Int,
        innerRatio: Float = 0.82f,
        outerRoundFraction: Float = 0.18f,
        smoothing: Float = 0.6f
    ): Path {
        val adaptiveRound = outerRadius * (0.65f / lobes.coerceAtLeast(4))
        val polygon = RoundedPolygon.star(
            numVerticesPerRadius = lobes,
            radius = outerRadius,
            innerRadius = outerRadius * innerRatio,
            rounding = CornerRounding(adaptiveRound, smoothing = smoothing),
            innerRounding = CornerRounding(adaptiveRound * 0.85f, smoothing = smoothing),
            centerX = cx,
            centerY = cy
        )
        val rawPath = polygon.toPath()
        val bounds = RectF()
        rawPath.computeBounds(bounds, true)
        val targetRect = RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        val matrix = Matrix().apply {
            setRectToRect(bounds, targetRect, Matrix.ScaleToFit.FILL)
        }
        rawPath.transform(matrix)
        return rawPath
    }

    /**
     * Official M3 RoundedPolygon regular N-gon (PuffyDiamond, Pentagon, Hexagon),
     * normalized to exact [radius] bounds.
     */
    private fun createM3PolygonPath(
        cx: Float,
        cy: Float,
        radius: Float,
        vertices: Int,
        roundFraction: Float = 0.28f
    ): Path {
        val polygon = RoundedPolygon(
            numVertices = vertices,
            radius = radius,
            centerX = cx,
            centerY = cy,
            rounding = CornerRounding(radius * roundFraction, smoothing = 1.0f)
        )
        val rawPath = polygon.toPath()
        val bounds = RectF()
        rawPath.computeBounds(bounds, true)
        val targetRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val matrix = Matrix().apply {
            setRectToRect(bounds, targetRect, Matrix.ScaleToFit.CENTER)
        }
        rawPath.transform(matrix)
        return rawPath
    }

    private fun drawAsymmetricRoundRect(
        canvas: Canvas,
        rect: RectF,
        tl: Float,
        tr: Float,
        br: Float,
        bl: Float,
        paint: Paint
    ) {
        val radii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        val path = Path().apply { addRoundRect(rect, radii, Path.Direction.CW) }
        canvas.drawPath(path, paint)
    }

    private fun drawLightningBolt(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx + 2f * scale, cy - 12f * scale)
            lineTo(cx - 8f * scale, cy + 1f * scale)
            lineTo(cx - 1f * scale, cy + 1f * scale)
            lineTo(cx - 3f * scale, cy + 12f * scale)
            lineTo(cx + 8f * scale, cy - 1f * scale)
            lineTo(cx + 1f * scale, cy - 1f * scale)
            close()
        }
        canvas.drawPath(path, fillPaint(color))
    }

    // =========================================================================
    // 0: STACKED CAPSULE BARS (M3 Capsule Progress Group)
    // =========================================================================
    private fun drawStackedCapsuleBars(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f
        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerHigh))

        val wm = data.weeklyMileage
        val tl = data.trainingLoad
        val zs = data.zoneSpectrum
        val rc = data.raceCountdown

        data class BarSpec(val label: String, val valueText: String, val ratio: Float, val fillCol: Int, val textCol: Int)

        val rows = listOf(
            BarSpec(
                "Mileage",
                String.format(Locale.US, "%.1fK", wm.actualKm),
                (wm.completionPct / 100f).coerceIn(0.32f, 0.92f),
                p.primary,
                p.onPrimary
            ),
            BarSpec(
                "Load TL",
                "${tl.acuteTl}",
                (tl.loadRatioPct / 100f).coerceIn(0.32f, 0.90f),
                p.tertiaryContainer,
                p.onTertiaryContainer
            ),
            BarSpec(
                "Z2 Aero",
                "${zs.aerobicPct}%",
                (zs.aerobicPct / 100f).coerceIn(0.35f, 0.92f),
                p.primary,
                p.onPrimary
            ),
            BarSpec(
                "Pune HM",
                "${rc.daysLeft}d",
                ((100 - rc.daysLeft).coerceIn(30, 88)) / 100f,
                p.tertiaryContainer,
                p.onTertiaryContainer
            )
        )

        val innerPad = s * 0.085f
        val gap = s * 0.032f
        val availH = s - innerPad * 2f - gap * 3f
        val barH = availH / 4f
        val barLeft = innerPad
        val barRight = s - innerPad
        val totalW = barRight - barLeft

        rows.forEachIndexed { idx, row ->
            val top = innerPad + idx * (barH + gap)
            val trackRect = RectF(barLeft, top, barRight, top + barH)
            canvas.drawRoundRect(trackRect, barH / 2f, barH / 2f, fillPaint(p.surfaceContainerLow))

            val save = canvas.save()
            val clipPath = Path().apply { addRoundRect(trackRect, barH / 2f, barH / 2f, Path.Direction.CW) }
            canvas.clipPath(clipPath)
            val fillW = totalW * row.ratio
            val fillRect = RectF(barLeft, top, barLeft + fillW, top + barH)
            canvas.drawRoundRect(fillRect, barH / 2f, barH / 2f, fillPaint(row.fillCol))
            canvas.restoreToCount(save)

            val labelPaint = textPaint(row.textCol, s * 0.048f, bold = true)
            val fm = labelPaint.fontMetrics
            val textY = trackRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(row.label, barLeft + s * 0.046f, textY, labelPaint)

            val valPaint = textPaint(p.onSurface, s * 0.046f, bold = true, align = Paint.Align.RIGHT)
            canvas.drawText(row.valueText, barRight - s * 0.042f, textY, valPaint)
        }
    }

    // =========================================================================
    // 1: CONCENTRIC DONUT RING (M3 Full Circular Track + Rounded Arc)
    // =========================================================================
    private fun drawConcentricDonutRing(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val outerR = s * 0.496f
        val ringThickness = s * 0.165f
        val innerDiscR = outerR - ringThickness * 0.5f

        canvas.drawCircle(cx, cy, outerR, fillPaint(p.surfaceContainerHigh))

        val ringRadius = outerR - ringThickness / 2f
        val arcRect = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        val baseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.surfaceContainerLow
            style = Paint.Style.STROKE
            strokeWidth = ringThickness
        }
        canvas.drawArc(arcRect, 0f, 360f, false, baseRingPaint)

        val tl = data.trainingLoad
        val sweep = (tl.loadRatioPct / 100f).coerceIn(0.18f, 0.92f) * 360f
        val progRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.primary
            style = Paint.Style.STROKE
            strokeWidth = ringThickness
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, -90f, sweep, false, progRingPaint)

        drawLightningBolt(canvas, cx, cy - innerDiscR * 0.36f, s * 0.0025f, p.tertiary)

        val mainPaint = textPaint(p.primary, s * 0.11f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText("${tl.acuteTl}", cx, cy + s * 0.04f, mainPaint)

        val subPaint = textPaint(p.onSurface, s * 0.045f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText("TL · ${tl.readinessScore}%", cx, cy + s * 0.115f, subPaint)
    }

    // =========================================================================
    // 2: DUAL CONTRAST PODS (M3 Circle + Squircle Tension inside Disc)
    // =========================================================================
    private fun drawDualContrastPods(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.496f
        canvas.drawCircle(cx, cy, r, fillPaint(p.surfaceContainerHigh))

        val nw = data.nextWorkout
        val wm = data.weeklyMileage

        val topPaint = textPaint(p.onSurfaceVariant, s * 0.048f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText("Week ${data.raceCountdown.currentWeek} : ${wm.completionPct}%", cx, cy - r * 0.62f, topPaint)
        drawLightningBolt(canvas, cx, cy - r * 0.45f, s * 0.0020f, p.primary)

        // Left Pod: Full Circle (tertiaryContainer)
        val podSize = s * 0.37f
        val gap = s * 0.032f
        val leftPodCx = cx - gap / 2f - podSize / 2f
        val podCy = cy + s * 0.01f
        canvas.drawCircle(leftPodCx, podCy, podSize / 2f, fillPaint(p.tertiaryContainer))

        val leftValPaint = textPaint(p.onTertiaryContainer, s * 0.14f, bold = true, align = Paint.Align.CENTER)
        val fm = leftValPaint.fontMetrics
        val numY = podCy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(String.format(Locale.US, "%.0f", wm.actualKm), leftPodCx, numY, leftValPaint)

        // Right Pod: Expressive Squircle (primaryContainer)
        val rightPodLeft = cx + gap / 2f
        val rightRect = RectF(rightPodLeft, podCy - podSize / 2f, rightPodLeft + podSize, podCy + podSize / 2f)
        canvas.drawRoundRect(rightRect, s * 0.095f, s * 0.095f, fillPaint(p.primaryContainer))
        val rightValPaint = textPaint(p.onPrimaryContainer, s * 0.13f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText(
            String.format(Locale.US, "%.0fK", wm.plannedKm),
            rightRect.centerX(),
            numY,
            rightValPaint
        )

        // Bottom Stadium Pill ("Sun 20K")
        val pillW = s * 0.33f
        val pillH = s * 0.10f
        val pillTop = cy + r * 0.50f
        val pillRect = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, fillPaint(p.surfaceContainerHighest))

        val pillTextPaint = textPaint(p.onSurface, s * 0.045f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText(
            "Sun ${nw.targetDistanceKm.toInt()}K",
            cx,
            pillRect.centerY() + s * 0.016f,
            pillTextPaint
        )
    }

    // =========================================================================
    // 3: 80/20 AEROBIC SPLIT BAR ('Battery Bar' Style Two-Tone Segmented Pill)
    // =========================================================================
    private fun drawPolygonShapeMatrix(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f
        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerHigh))

        val zs = data.zoneSpectrum

        // Top Two-Tone Horizontal Segmented Bar ('Battery Bar' layout)
        val barLeft = pad + s * 0.09f
        val barRight = s - pad - s * 0.09f
        val barTop = pad + s * 0.11f
        val barH = s * 0.24f
        val barW = barRight - barLeft
        val barRect = RectF(barLeft, barTop, barRight, barTop + barH)
        val barR = s * 0.075f

        // Background track (Threshold / Speed 20% portion)
        canvas.drawRoundRect(barRect, barR, barR, fillPaint(p.tertiaryContainer))

        // Foreground fill (Aerobic Z2 80% portion)
        val aeroFrac = (zs.aerobicPct / 100f).coerceIn(0.25f, 0.90f)
        val fillRect = RectF(barLeft, barTop, barLeft + barW * aeroFrac, barTop + barH)
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(barRect, barR, barR, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawRoundRect(fillRect, barR, barR, fillPaint(p.primary))
        canvas.restoreToCount(save)

        // Labels inside the bar
        canvas.drawText(
            "Z2 ${zs.aerobicPct}%",
            barLeft + s * 0.045f,
            barRect.centerY() + s * 0.018f,
            textPaint(p.onPrimary, s * 0.048f, bold = true)
        )
        canvas.drawText(
            "${zs.thresholdPct}%",
            barRight - s * 0.035f,
            barRect.centerY() + s * 0.018f,
            textPaint(p.onTertiaryContainer, s * 0.044f, bold = true, align = Paint.Align.RIGHT)
        )

        // Bottom-left typography ('75% / 25%' + '80/20 AEROBIC SPLIT' + HR summary)
        canvas.drawText(
            "${zs.aerobicPct}%",
            barLeft,
            s - pad - s * 0.25f,
            textPaint(p.primary, s * 0.16f, bold = true)
        )
        canvas.drawText(
            "AEROBIC BASE (${zs.targetRatio} GOAL)",
            barLeft,
            s - pad - s * 0.155f,
            textPaint(p.onSurface, s * 0.044f, bold = true)
        )
        canvas.drawText(
            "Easy ${zs.easyAvgHr} bpm · Speed ${zs.qualityAvgHr} bpm",
            barLeft,
            s - pad - s * 0.085f,
            textPaint(p.onSurfaceVariant, s * 0.039f, bold = false)
        )
    }

    // =========================================================================
    // 4: COOKIE-12 SCALLOP SEAL (Full-Bleed Circle + Giant 16-Lobe M3 Cookie)
    // =========================================================================
    private fun drawCookie12ScallopSeal(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val outerR = s * 0.496f

        canvas.drawCircle(cx, cy, outerR, fillPaint(p.surfaceContainerLow))

        // Normalized via createM3CookiePath so the scalloped peaks fill 95% of the entire circle!
        val cookie16 = createM3CookiePath(
            cx = cx,
            cy = cy,
            outerRadius = outerR * 0.95f,
            lobes = 16,
            innerRatio = 0.86f
        )
        canvas.drawPath(cookie16, fillPaint(p.surfaceContainerHigh))

        val rc = data.raceCountdown
        val topPaint = textPaint(p.onSurface, s * 0.060f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText("PUNE HM", cx, cy - outerR * 0.38f, topPaint)

        val midPaint = textPaint(p.primary, s * 0.22f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText("${rc.daysLeft}d", cx, cy + s * 0.075f, midPaint)

        val pillW = s * 0.34f
        val pillH = s * 0.105f
        val pillTop = cy + outerR * 0.36f
        val pillRect = RectF(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, fillPaint(p.tertiaryContainer))

        val botPaint = textPaint(p.onTertiaryContainer, s * 0.048f, bold = true, align = Paint.Align.CENTER)
        canvas.drawText(rc.currentLtsp, cx, pillRect.centerY() + s * 0.017f, botPaint)
    }

    // =========================================================================
    // 5: LIQUID HORIZON + COOKIE-9 BADGE (M3 Container Split + Cookie9Sided)
    // =========================================================================
    private fun drawLiquidHorizonCookie9(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f

        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerLow))

        val horizonY = pad + outer.height() * 0.25f
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(outer, corner, corner, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawRect(pad, horizonY, s - pad, s - pad, fillPaint(p.surfaceContainerHigh))
        canvas.restoreToCount(save)

        // Official M3 Cookie9Sided Badge straddling the horizon
        val badgeCx = pad + s * 0.19f
        val badgeCy = horizonY
        val cookie9 = createM3CookiePath(
            cx = badgeCx,
            cy = badgeCy,
            outerRadius = s * 0.095f,
            lobes = 9,
            innerRatio = 0.82f,
            outerRoundFraction = 0.20f
        )
        canvas.drawPath(cookie9, fillPaint(p.tertiaryContainer))

        val iconRect = RectF(badgeCx - s * 0.024f, badgeCy - s * 0.038f, badgeCx + s * 0.024f, badgeCy + s * 0.038f)
        canvas.drawRoundRect(iconRect, s * 0.009f, s * 0.009f, fillPaint(p.onTertiaryContainer))

        val wm = data.weeklyMileage
        val subPaint = textPaint(p.onSurface, s * 0.050f, bold = true)
        canvas.drawText("COROS Pace Pro", pad + s * 0.08f, horizonY + s * 0.18f, subPaint)
        canvas.drawText(
            "Week ${data.raceCountdown.currentWeek} · ${wm.runsCompleted}/${wm.runsPlanned} Runs",
            pad + s * 0.08f,
            horizonY + s * 0.26f,
            textPaint(p.onSurfaceVariant, s * 0.046f, bold = true)
        )

        val heroPaint = textPaint(p.primary, s * 0.17f, bold = true)
        canvas.drawText(
            String.format(Locale.US, "%.1fK", wm.actualKm),
            pad + s * 0.075f,
            s - pad - s * 0.08f,
            heroPaint
        )
    }

    // =========================================================================
    // 6: ASYMMETRIC NESTED WORKOUT STACK (M3 Inner Corner Nesting + Cookie12 Badge)
    // =========================================================================
    private fun drawAsymmetricWorkoutStack(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f
        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerHigh))

        val headerPaint = textPaint(p.primary, s * 0.058f, bold = true)
        canvas.drawText("Week ${data.raceCountdown.currentWeek} Plan", pad + s * 0.075f, pad + s * 0.15f, headerPaint)

        val badgeCx = s - pad - s * 0.135f
        val badgeCy = pad + s * 0.125f
        val cookie12 = createM3CookiePath(badgeCx, badgeCy, s * 0.068f, lobes = 12, innerRatio = 0.84f)
        canvas.drawPath(cookie12, fillPaint(p.tertiaryContainer))

        for (i in -1..1) {
            val ly = badgeCy + i * s * 0.018f
            canvas.drawLine(
                badgeCx - s * 0.024f,
                ly,
                badgeCx + s * 0.024f,
                ly,
                fillPaint(p.onTertiaryContainer).apply { strokeWidth = s * 0.008f }
            )
        }

        val items = listOf(
            "✓ Tue Threshold · 8.5 km",
            "✓ Wed + Fri Easy · 13.8 km",
            "Next: ${data.nextWorkout.title}"
        )

        val pillLeft = pad + s * 0.058f
        val pillRight = s - pad - s * 0.058f
        var currTop = pad + s * 0.23f
        val pillH = s * 0.19f
        val gap = s * 0.022f
        val outerR = s * 0.085f
        val innerR = s * 0.026f

        items.forEachIndexed { idx, text ->
            val r = RectF(pillLeft, currTop, pillRight, currTop + pillH)
            val bgCol = if (idx == 2) p.primary else p.surfaceContainerLow
            val txtCol = if (idx == 2) p.onPrimary else p.onSurface

            when (idx) {
                0 -> drawAsymmetricRoundRect(canvas, r, outerR, outerR, innerR, innerR, fillPaint(bgCol))
                1 -> drawAsymmetricRoundRect(canvas, r, innerR, innerR, innerR, innerR, fillPaint(bgCol))
                else -> drawAsymmetricRoundRect(canvas, r, innerR, innerR, outerR, outerR, fillPaint(bgCol))
            }

            val tp = textPaint(txtCol, s * 0.048f, bold = idx == 2)
            canvas.drawText(text, r.left + s * 0.048f, r.centerY() + s * 0.017f, tp)
            currTop += pillH + gap
        }
    }

    // =========================================================================
    // 7: PIXEL WEATHER SLANTED PILL (Broad +45° Diagonal Stadium Capsule)
    // =========================================================================
    private fun drawSlantedPillWithSatellites(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val wm = data.weeklyMileage

        // Broad, chubby Pixel Weather +45° diagonal stadium:
        // beanW = 0.74 * s (broad!), beanH = 1.08 * s -> fills 98% of the square!
        val beanW = s * 0.74f
        val beanH = s * 1.08f
        val save = canvas.save()
        canvas.rotate(45f, cx, cy)
        val beanRect = RectF(cx - beanW / 2f, cy - beanH / 2f, cx + beanW / 2f, cy + beanH / 2f)
        canvas.drawRoundRect(beanRect, beanW / 2f, beanW / 2f, fillPaint(p.surfaceContainerHigh))
        canvas.restoreToCount(save)

        // Top-Right Dome Center: (0.61 * s, 0.39 * s)
        val trX = s * 0.605f
        val trY = s * 0.375f

        canvas.drawText(
            "WEEK ${data.raceCountdown.currentWeek}",
            trX,
            trY - s * 0.145f,
            textPaint(p.onSurfaceVariant, s * 0.042f, bold = true, align = Paint.Align.CENTER)
        )

        val kmStr = String.format(Locale.US, "%.1f", wm.actualKm)
        canvas.drawText(
            kmStr,
            trX,
            trY + s * 0.035f,
            textPaint(p.onSurface, s * 0.185f, bold = true, align = Paint.Align.CENTER)
        )

        canvas.drawText(
            "/ ${wm.plannedKm.toInt()} KM",
            trX,
            trY + s * 0.115f,
            textPaint(p.primary, s * 0.048f, bold = true, align = Paint.Align.CENTER)
        )

        // Bottom-Left Dome Center: (0.37 * s, 0.63 * s)
        // Pixel Weather style layered icon + weekly completion %
        val blX = s * 0.365f
        val blY = s * 0.635f

        // Layered M3 accent circle + overlapping warm orb (like Pixel Weather Sun + Cloud)
        canvas.drawCircle(blX + s * 0.105f, blY - s * 0.065f, s * 0.076f, fillPaint(p.tertiaryContainer))
        canvas.drawCircle(blX - s * 0.01f, blY - s * 0.01f, s * 0.135f, fillPaint(p.primary))

        canvas.drawText(
            "${wm.completionPct}%",
            blX - s * 0.01f,
            blY + s * 0.018f,
            textPaint(p.onPrimary, s * 0.082f, bold = true, align = Paint.Align.CENTER)
        )

        // Bottom-left caption inside lower dome
        canvas.drawText(
            "${wm.runsCompleted}/${wm.runsPlanned} RUNS",
            blX + s * 0.01f,
            blY + s * 0.185f,
            textPaint(p.onSurfaceVariant, s * 0.042f, bold = true, align = Paint.Align.CENTER)
        )
    }

    // =========================================================================
    // 8: CONCENTRIC TONAL ELLIPSES (M3 Layered Oval Stack)
    // =========================================================================
    private fun drawConcentricTonalEllipses(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.496f

        val save = canvas.save()
        val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        canvas.clipPath(clip)

        canvas.drawCircle(cx, cy, r, fillPaint(p.surfaceContainerLow))

        canvas.drawOval(RectF(cx - r * 0.9f, cy - r * 1.15f, cx + r * 0.9f, cy - r * 0.72f), fillPaint(p.surfaceContainerHigh))
        canvas.drawOval(RectF(cx - r * 0.9f, cy + r * 0.72f, cx + r * 0.9f, cy + r * 1.15f), fillPaint(p.surfaceContainerHigh))

        val upperOval = RectF(cx - r * 0.78f, cy - r * 0.65f, cx + r * 0.78f, cy - r * 0.26f)
        val lowerOval = RectF(cx - r * 0.78f, cy + r * 0.26f, cx + r * 0.78f, cy + r * 0.65f)
        canvas.drawOval(upperOval, fillPaint(p.secondaryContainer))
        canvas.drawOval(lowerOval, fillPaint(p.secondaryContainer))

        val centerOval = RectF(cx - r * 0.94f, cy - r * 0.26f, cx + r * 0.94f, cy + r * 0.26f)
        canvas.drawOval(centerOval, fillPaint(p.tertiaryContainer))

        canvas.restoreToCount(save)

        val rc = data.raceCountdown
        canvas.drawText(
            "Pune HM · ${rc.daysLeft}d",
            cx,
            upperOval.centerY() + s * 0.016f,
            textPaint(p.onSecondaryContainer, s * 0.048f, bold = true, align = Paint.Align.CENTER)
        )
        canvas.drawText(
            rc.currentLtsp,
            cx,
            cy + s * 0.052f,
            textPaint(p.onTertiaryContainer, s * 0.135f, bold = true, align = Paint.Align.CENTER)
        )
        canvas.drawText(
            "Goal ${rc.goalTime}",
            cx,
            lowerOval.centerY() + s * 0.016f,
            textPaint(p.onSecondaryContainer, s * 0.048f, bold = true, align = Paint.Align.CENTER)
        )
    }

    // =========================================================================
    // 9: COOKIE-12 FLOWER SILHOUETTE (Full-Bleed 99% M3 Cookie12Sided Boundary)
    // =========================================================================
    private fun drawCookie12OuterFlower(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val cx = s / 2f
        val cy = s / 2f
        val outerR = s * 0.496f

        val flowerPath = createM3CookiePath(
            cx = cx,
            cy = cy,
            outerRadius = outerR,
            lobes = 12,
            innerRatio = 0.85f,
            outerRoundFraction = 0.16f
        )
        canvas.drawPath(flowerPath, fillPaint(p.surfaceContainerHigh))

        val tl = data.trainingLoad
        canvas.drawText(
            "Readiness",
            cx,
            cy - s * 0.17f,
            textPaint(p.onSurfaceVariant, s * 0.055f, bold = true, align = Paint.Align.CENTER)
        )
        canvas.drawText(
            "${tl.readinessScore}",
            cx,
            cy + s * 0.05f,
            textPaint(p.primary, s * 0.195f, bold = true, align = Paint.Align.CENTER)
        )
        canvas.drawText(
            tl.statusBadge.lowercase(Locale.US).replaceFirstChar { it.uppercase() },
            cx,
            cy + s * 0.175f,
            textPaint(p.onSurface, s * 0.050f, bold = true, align = Paint.Align.CENTER)
        )

        val dotOrbitR = outerR * 0.72f
        val anglesDeg = listOf(148f, 122f, 96f, 70f, 44f, 18f)
        val days = data.weeklyMileage.days
        anglesDeg.forEachIndexed { idx, deg ->
            val rad = deg * (PI / 180.0)
            val dx = cx + dotOrbitR * cos(rad).toFloat()
            val dy = cy + dotOrbitR * sin(rad).toFloat()
            val active = idx < days.size && days[idx].actualKm > 0
            val col = if (idx == 0 || active) p.tertiary else p.surfaceContainerHighest
            val dotR = if (idx == 0) s * 0.035f else s * 0.023f
            canvas.drawCircle(dx, dy, dotR, fillPaint(col))
        }
    }

    // =========================================================================
    // 10: EXPRESSIVE SPLIT TYPOGRAPHY (M3 Condensed Display + Container Badge)
    // =========================================================================
    private fun drawExpressiveSplitBlocks(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f
        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerHigh))

        val rc = data.raceCountdown
        val leftPaint = textPaint(p.primary, s * 0.60f, bold = true, align = Paint.Align.CENTER, condensed = true)
        canvas.drawText(
            String.format(Locale.US, "%02d", rc.daysLeft),
            pad + outer.width() * 0.34f,
            s - pad - s * 0.10f,
            leftPaint
        )

        val trPaint = textPaint(p.tertiary, s * 0.27f, bold = true, align = Paint.Align.CENTER, condensed = true)
        canvas.drawText(
            "W${rc.currentWeek}",
            pad + outer.width() * 0.78f,
            pad + outer.height() * 0.44f,
            trPaint
        )

        val pillRect = RectF(
            pad + outer.width() * 0.62f,
            pad + outer.height() * 0.52f,
            s - pad - s * 0.06f,
            s - pad - s * 0.07f
        )
        canvas.drawRoundRect(pillRect, s * 0.07f, s * 0.07f, fillPaint(p.tertiaryContainer))
        val pillText = textPaint(p.onTertiaryContainer, s * 0.12f, bold = true, align = Paint.Align.CENTER, condensed = true)
        canvas.drawText("1:45", pillRect.centerX(), pillRect.centerY() + s * 0.044f, pillText)
    }

    // =========================================================================
    // 11: EVOLAB METRIC LIST + CLOVER-4 BADGE (M3 Clover4Leaf + Hairline Table)
    // =========================================================================
    private fun drawEvoLabSplitsClover4(canvas: Canvas, s: Float, data: LapsPayload, p: M3WidgetPalette) {
        val pad = s * 0.006f
        val outer = RectF(pad, pad, s - pad, s - pad)
        val corner = s * 0.19f
        canvas.drawRoundRect(outer, corner, corner, fillPaint(p.surfaceContainerHigh))

        canvas.drawText(
            "EvoLab Splits",
            pad + s * 0.075f,
            pad + s * 0.16f,
            textPaint(p.onSurface, s * 0.060f, bold = true)
        )

        // Official M3 Clover4Leaf / Cookie4Sided badge
        val badgeCx = s - pad - s * 0.135f
        val badgeCy = pad + s * 0.135f
        val clover4 = createM3CookiePath(badgeCx, badgeCy, s * 0.072f, lobes = 4, innerRatio = 0.68f, outerRoundFraction = 0.28f)
        canvas.drawPath(clover4, fillPaint(p.tertiaryContainer))
        canvas.drawText(
            "+",
            badgeCx,
            badgeCy + s * 0.024f,
            textPaint(p.onTertiaryContainer, s * 0.070f, bold = true, align = Paint.Align.CENTER)
        )

        val rows = listOf(
            "Threshold LT" to data.raceCountdown.currentLtsp,
            "HM Goal Pace" to data.raceCountdown.goalPace,
            "Easy Z2 HR" to "${data.zoneSpectrum.easyAvgHr} bpm",
            "Resting HR" to "${data.trainingLoad.rhr} bpm"
        )

        var y = pad + s * 0.32f
        val rowStep = s * 0.155f
        val divPaint = fillPaint(p.outlineVariant).apply { strokeWidth = 2f }

        rows.forEachIndexed { idx, (label, value) ->
            canvas.drawText(label, pad + s * 0.075f, y, textPaint(p.onSurfaceVariant, s * 0.050f, bold = false))
            canvas.drawText(
                value,
                s - pad - s * 0.075f,
                y,
                textPaint(p.primary, s * 0.052f, bold = true, align = Paint.Align.RIGHT)
            )
            if (idx < rows.lastIndex) {
                val lineY = y + s * 0.052f
                canvas.drawLine(pad + s * 0.075f, lineY, s - pad - s * 0.075f, lineY, divPaint)
            }
            y += rowStep
        }
    }
}
