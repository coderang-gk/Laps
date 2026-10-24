package io.github.coderang.laps.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.coderang.laps.MainActivity
import io.github.coderang.laps.R
import io.github.coderang.laps.data.LastRunData

object LapsNotificationHelper {

    private const val CHANNEL_ID = "laps_run_analysis_channel"
    private const val NOTIFICATION_ID = 202611

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Laps AI Run Analysis"
            val desc = "Post-run AI coaching verdicts, physiological insights, and actionable tips"
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = desc
                enableLights(true)
                lightColor = ContextCompat.getColor(context, android.R.color.system_accent1_200)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun notifyNewRun(context: Context, run: LastRunData) {
        if (run.name.isBlank() || run.distanceKm <= 0.0) return

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_RUN_DETAIL, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (run.aiVerdictHeadline.isNotBlank()) "🏃 ${run.aiVerdictHeadline}" else "🏃 Run Logged: ${run.name}"
        val contentLine = "%.1f km · %s · Avg HR %d bpm".format(run.distanceKm, run.avgPace, run.avgHr)
        val body = run.aiExecutiveSummary.ifBlank { run.physiologyNote }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentLine\n\n$body"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
            // Catches SecurityException if notification permissions are denied on Android 13+
        }
    }
}
