package io.github.lhd980820.coup.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = CoupGold,
    onPrimary = Color(0xFF2A1D00),
    primaryContainer = CoupGoldDark,
    onPrimaryContainer = Color(0xFFFFEFD0),
    secondary = Color(0xFFD4C4A8),
    secondaryContainer = CoupPanelHigh,
    onSecondaryContainer = CoupParchment,
    background = CoupInk,
    onBackground = CoupParchment,
    surface = CoupInk,
    onSurface = CoupParchment,
    surfaceVariant = CoupPanel,
    onSurfaceVariant = Color(0xFFD9CDBB),
    error = CoupCrimsonLight,
    onError = Color(0xFF410002),
)

private val LightColorScheme = lightColorScheme(
    primary = CoupGoldDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0A0),
    secondary = Color(0xFF6B5A3E),
    secondaryContainer = Color(0xFFEADFC8),
    onSecondaryContainer = Color(0xFF2A2013),
    background = CoupParchment,
    onBackground = CoupInk,
    surface = CoupParchment,
    onSurface = CoupInk,
    surfaceVariant = Color(0xFFE6DAC4),
    onSurfaceVariant = Color(0xFF3F3524),
    error = CoupCrimson,
    onError = Color.White,
)

@Composable
fun CoupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 브랜드 일관성을 위해 기본은 끈다(설계 §10.1)
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}