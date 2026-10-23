package io.github.coderang.laps.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import io.github.coderang.laps.MainActivity
import io.github.coderang.laps.R
import io.github.coderang.laps.data.ExpressiveTileType
import io.github.coderang.laps.data.LapsPayload
import io.github.coderang.laps.data.LapsRepository
import io.github.coderang.laps.ui.ExpressiveWidgetRenderer
import java.util.concurrent.Executors

object LapsWidgetUpdateHelper {
    const val ACTION_FORCE_REFRESH = "io.github.coderang.laps.ACTION_FORCE_REFRESH"
    private val executor = Executors.newSingleThreadExecutor()

    private val providerClasses = listOf(
        WeeklyMileageWidgetProvider::class.java,
        TrainingLoadWidgetProvider::class.java,
        NextWorkoutWidgetProvider::class.java,
        ZoneSpectrumWidgetProvider::class.java,
        RaceCountdownWidgetProvider::class.java,
        CommandCenterWidgetProvider::class.java
    )

    fun refreshAllWidgetsAsync(context: Context, onComplete: ((Result<LapsPayload>) -> Unit)? = null) {
        val appCtx = context.applicationContext
        renderAllFromCache(appCtx)

        executor.execute {
            val result = LapsRepository.syncOnDevice(appCtx)
            val payload = result.getOrElse { LapsRepository.getCachedPayload() }
            renderAllWithPayload(appCtx, payload)
            onComplete?.invoke(result)
        }
    }

    fun renderAllFromCache(context: Context) {
        renderAllWithPayload(context, LapsRepository.getCachedPayload())
    }

    private fun renderAllWithPayload(context: Context, payload: LapsPayload) {
        val manager = AppWidgetManager.getInstance(context)
        providerClasses.forEach { cls ->
            val tileType = getTileTypeForClass(cls)
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            for (id in ids) {
                updateSingleWidget(context, manager, id, tileType, payload)
            }
        }
    }

    fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        tileType: ExpressiveTileType,
        payload: LapsPayload = LapsRepository.getCachedPayload()
    ) {
        val options = manager.getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)

        val defaultDims = when (tileType) {
            ExpressiveTileType.WEEKLY_MILEAGE -> Pair(820, 400)
            ExpressiveTileType.TRAINING_LOAD -> Pair(440, 420)
            ExpressiveTileType.NEXT_WORKOUT -> Pair(820, 210)
            ExpressiveTileType.ZONE_SPECTRUM -> Pair(440, 420)
            ExpressiveTileType.RACE_COUNTDOWN -> Pair(820, 400)
            ExpressiveTileType.COMMAND_CENTER -> Pair(820, 800)
        }

        val widthPx = if (minW > 60) (minW * density * 1.3f).toInt().coerceIn(420, 900) else defaultDims.first
        val heightPx = if (minH > 40) (minH * density * 1.3f).toInt().coerceIn(420, 900) else defaultDims.second
        val styleIdx = LapsRepository.getStyleForTile(context, tileType)

        val bitmap = ExpressiveWidgetRenderer.renderTileBitmap(
            context = context,
            tileType = tileType,
            payload = payload,
            widthPx = widthPx,
            heightPx = heightPx,
            styleIndexOverride = styleIdx
        )

        val views = RemoteViews(context.packageName, R.layout.widget_expressive_canvas)
        views.setImageViewBitmap(R.id.widget_canvas_image, bitmap)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            appWidgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_canvas_image, openPending)

        val refreshIntent = Intent(context, getProviderClassForTile(tileType)).apply {
            action = ACTION_FORCE_REFRESH
        }
        val refreshPending = PendingIntent.getBroadcast(
            context,
            appWidgetId + 10000,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_hotspot, refreshPending)

        manager.updateAppWidget(appWidgetId, views)
    }

    private fun getTileTypeForClass(cls: Class<out BaseLapsWidgetProvider>): ExpressiveTileType =
        when (cls) {
            WeeklyMileageWidgetProvider::class.java -> ExpressiveTileType.WEEKLY_MILEAGE
            TrainingLoadWidgetProvider::class.java -> ExpressiveTileType.TRAINING_LOAD
            NextWorkoutWidgetProvider::class.java -> ExpressiveTileType.NEXT_WORKOUT
            ZoneSpectrumWidgetProvider::class.java -> ExpressiveTileType.ZONE_SPECTRUM
            RaceCountdownWidgetProvider::class.java -> ExpressiveTileType.RACE_COUNTDOWN
            CommandCenterWidgetProvider::class.java -> ExpressiveTileType.COMMAND_CENTER
            else -> ExpressiveTileType.WEEKLY_MILEAGE
        }

    private fun getProviderClassForTile(tileType: ExpressiveTileType): Class<out BaseLapsWidgetProvider> =
        when (tileType) {
            ExpressiveTileType.WEEKLY_MILEAGE -> WeeklyMileageWidgetProvider::class.java
            ExpressiveTileType.TRAINING_LOAD -> TrainingLoadWidgetProvider::class.java
            ExpressiveTileType.NEXT_WORKOUT -> NextWorkoutWidgetProvider::class.java
            ExpressiveTileType.ZONE_SPECTRUM -> ZoneSpectrumWidgetProvider::class.java
            ExpressiveTileType.RACE_COUNTDOWN -> RaceCountdownWidgetProvider::class.java
            ExpressiveTileType.COMMAND_CENTER -> CommandCenterWidgetProvider::class.java
        }
}

abstract class BaseLapsWidgetProvider(private val tileType: ExpressiveTileType) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val cached = LapsRepository.getCachedPayload()
        for (id in appWidgetIds) {
            LapsWidgetUpdateHelper.updateSingleWidget(context, appWidgetManager, id, tileType, cached)
        }
        LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        LapsWidgetUpdateHelper.updateSingleWidget(context, appWidgetManager, appWidgetId, tileType)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            LapsWidgetUpdateHelper.ACTION_FORCE_REFRESH -> {
                LapsWidgetUpdateHelper.refreshAllWidgetsAsync(context)
            }
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_WALLPAPER_CHANGED -> {
                LapsWidgetUpdateHelper.renderAllFromCache(context)
            }
        }
    }
}

class WeeklyMileageWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.WEEKLY_MILEAGE)
class TrainingLoadWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.TRAINING_LOAD)
class NextWorkoutWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.NEXT_WORKOUT)
class ZoneSpectrumWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.ZONE_SPECTRUM)
class RaceCountdownWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.RACE_COUNTDOWN)
class CommandCenterWidgetProvider : BaseLapsWidgetProvider(ExpressiveTileType.COMMAND_CENTER)
