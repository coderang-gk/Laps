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
import io.github.coderang.laps.data.LapsRepository

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

    fun getComposeColorScheme(context: Context, forcedTheme: String = "SYSTEM"): ColorScheme {
        val isDark = when (forcedTheme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> {
                val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) darkColorScheme() else lightColorScheme()
        }
    }

    fun getPalette(context: Context, forcedTheme: String? = null): M3WidgetPalette {
        val theme = forcedTheme ?: LapsRepository.getAppTheme(context)
        val isDark = when (theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> {
                val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        if (!isDark) {
            return M3WidgetPalette(
                isDark = false,
                surface = 0xFFFFFFFF.toInt(),
                surfaceContainerLow = 0xFFF4F6FB.toInt(),
                surfaceContainer = 0xFFEDF1F8.toInt(),
                surfaceContainerHigh = 0xFFE2E8F3.toInt(),
                surfaceContainerHighest = 0xFFD8E0EE.toInt(),
                onSurface = 0xFF111827.toInt(), // Deep athletic slate
                onSurfaceVariant = 0xFF4B5563.toInt(),
                primary = 0xFF0B57D0.toInt(), // Athletic Royal Blue
                onPrimary = 0xFFFFFFFF.toInt(),
                primaryContainer = 0xFFD3E3FD.toInt(),
                onPrimaryContainer = 0xFF041E49.toInt(),
                secondary = 0xFF475569.toInt(),
                onSecondary = 0xFFFFFFFF.toInt(),
                secondaryContainer = 0xFFE2E8F0.toInt(),
                onSecondaryContainer = 0xFF0F172A.toInt(),
                tertiary = 0xFFE03E3E.toInt(), // Athletic Coral Red
                onTertiary = 0xFFFFFFFF.toInt(),
                tertiaryContainer = 0xFFFEE2E2.toInt(),
                onTertiaryContainer = 0xFF7F1D1D.toInt(),
                outline = 0xFF94A3B8.toInt(),
                outlineVariant = 0xFFCBD5E1.toInt()
            )
        }

        val scheme = getComposeColorScheme(context, "DARK")
        return M3WidgetPalette(
            isDark = true,
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
