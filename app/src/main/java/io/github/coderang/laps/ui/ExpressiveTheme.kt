package io.github.coderang.laps.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * Pure Material You dynamic theming:
 * Automatically resolves the system accent and tonal palette directly from the user's
 * wallpaper via dynamicColorScheme (Android 12+ / API 31+).
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) darkColorScheme() else lightColorScheme()
        }
    }

    fun getPalette(context: Context): M3WidgetPalette {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        val scheme = getComposeColorScheme(context)

        return M3WidgetPalette(
            isDark = isDark,
            surface = scheme.surface.toArgb(),
            surfaceContainerLow = scheme.surfaceContainerLow.toArgb(),
            surfaceContainer = scheme.surfaceContainer.toArgb(),
            surfaceContainerHigh = scheme.surfaceContainerHigh.toArgb(),
            surfaceContainerHighest = scheme.surfaceContainerHighest.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            primaryContainer = scheme.primaryContainer.toArgb(),
            onPrimaryContainer = scheme.onPrimaryContainer.toArgb(),
            secondary = scheme.secondary.toArgb(),
            onSecondary = scheme.onSecondary.toArgb(),
            secondaryContainer = scheme.secondaryContainer.toArgb(),
            onSecondaryContainer = scheme.onSecondaryContainer.toArgb(),
            tertiary = scheme.tertiary.toArgb(),
            onTertiary = scheme.onTertiary.toArgb(),
            tertiaryContainer = scheme.tertiaryContainer.toArgb(),
            onTertiaryContainer = scheme.onTertiaryContainer.toArgb(),
            outline = scheme.outline.toArgb(),
            outlineVariant = scheme.outlineVariant.toArgb()
        )
    }
}
