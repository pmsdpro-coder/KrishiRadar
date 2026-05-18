package com.krishiradar.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = KrishiGreen,
    onPrimary = KrishiOnGreen,
    primaryContainer = KrishiContainer,
    secondary = KrishiSecondary,
    tertiary = KrishiTertiary,
    background = KrishiBackground,
    surface = KrishiSurface,
    error = KrishiError
)

private val DarkColors = darkColorScheme(
    primary = KrishiGreenDark,
    onPrimary = KrishiContainerDark,
    primaryContainer = KrishiContainerDark,
    background = KrishiBackgroundDark,
    surface = KrishiSurfaceDark,
    error = KrishiError
)

@Composable
fun KrishiRadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,   // off by default — keep agricultural green identity
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
