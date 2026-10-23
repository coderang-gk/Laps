package io.github.coderang.laps.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.core.content.ContextCompat

/**
 * Material You Expressive Widget Palette resolved directly from Android's system
 * dynamic tonal palettes (`system_accent1_*`, `system_accent2_*`, `system_accent3_*`)
 * to match Google Weather and Pixel Material You widgets:
 * - Main Widget Containers: `system_accent2_700` (rich slate-indigo in Dark Mode)
 * - Deep Recess / Tracks: `system_accent2_800`
 * - Primary Accents & Hero Digits: `system_accent1_200` (pastel sky-blue in Dark Mode)
 * - Secondary Badges & Pills: `system_accent3_200` (pastel lavender in Dark Mode)
 */
data class M3WidgetPalette(
    val isDark: Boolean,
    val surface: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val outline: Int,
    val outlineVariant: Int
)

object ExpressiveTheme {

    fun getComposeColorScheme(context: Context): ColorScheme {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    private fun sysColor(context: Context, resId: Int): Int =
        ContextCompat.getColor(context, resId)

    fun getPalette(context: Context): M3WidgetPalette {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightFlags == Configuration.UI_MODE_NIGHT_YES

        return if (isDark) {
            val containerMain = sysColor(context, android.R.color.system_accent2_700)
            val containerDeep = sysColor(context, android.R.color.system_accent2_800)
            val containerElevated = sysColor(context, android.R.color.system_accent2_600)
            val textPrimary = sysColor(context, android.R.color.system_accent2_50)
            val textSecondary = sysColor(context, android.R.color.system_accent2_200)

            val accentPrimary = sysColor(context, android.R.color.system_accent1_200)
            val onAccentPrimary = sysColor(context, android.R.color.system_accent1_800)

            val accentTertiary = sysColor(context, android.R.color.system_accent3_200)
            val onAccentTertiary = sysColor(context, android.R.color.system_accent3_800)

            M3WidgetPalette(
                isDark = true,
                surface = containerDeep,
                surfaceContainerLow = containerDeep,
                surfaceContainer = containerMain,
                surfaceContainerHigh = containerMain,
                surfaceContainerHighest = containerElevated,
                onSurface = textPrimary,
                onSurfaceVariant = textSecondary,
                primary = accentPrimary,
                onPrimary = onAccentPrimary,
                primaryContainer = accentPrimary,
                onPrimaryContainer = onAccentPrimary,
                secondary = textSecondary,
                onSecondary = containerDeep,
                secondaryContainer = containerElevated,
                onSecondaryContainer = textPrimary,
                tertiary = accentTertiary,
                onTertiary = onAccentTertiary,
                tertiaryContainer = accentTertiary,
                onTertiaryContainer = onAccentTertiary,
                outline = containerElevated,
                outlineVariant = containerElevated
            )
        } else {
            val containerMain = sysColor(context, android.R.color.system_accent2_100)
            val containerDeep = sysColor(context, android.R.color.system_accent2_200)
            val containerElevated = sysColor(context, android.R.color.system_accent2_50)
            val textPrimary = sysColor(context, android.R.color.system_accent2_900)
            val textSecondary = sysColor(context, android.R.color.system_accent2_700)

            val accentPrimary = sysColor(context, android.R.color.system_accent1_600)
            val onAccentPrimary = sysColor(context, android.R.color.system_accent1_50)

            val accentTertiary = sysColor(context, android.R.color.system_accent3_600)
            val onAccentTertiary = sysColor(context, android.R.color.system_accent3_50)

            M3WidgetPalette(
                isDark = false,
                surface = containerDeep,
                surfaceContainerLow = containerDeep,
                surfaceContainer = containerMain,
                surfaceContainerHigh = containerMain,
                surfaceContainerHighest = containerElevated,
                onSurface = textPrimary,
                onSurfaceVariant = textSecondary,
                primary = accentPrimary,
                onPrimary = onAccentPrimary,
                primaryContainer = accentPrimary,
                onPrimaryContainer = onAccentPrimary,
                secondary = textSecondary,
                onSecondary = containerElevated,
                secondaryContainer = containerDeep,
                onSecondaryContainer = textPrimary,
                tertiary = accentTertiary,
                onTertiary = onAccentTertiary,
                tertiaryContainer = accentTertiary,
                onTertiaryContainer = onAccentTertiary,
                outline = containerDeep,
                outlineVariant = containerDeep
            )
        }
    }
}
