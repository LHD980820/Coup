package io.github.lhd980820.coup.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CoupColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = GoldInk,
    primaryContainer = Color(0xFF574012),
    onPrimaryContainer = Color(0xFFFFEFC9),
    inversePrimary = GoldDeep,
    secondary = Color(0xFFD9C8A7),
    onSecondary = Color(0xFF2B2010),
    secondaryContainer = PanelTop,
    onSecondaryContainer = Parchment,
    tertiary = Color(0xFF9FD0BF),
    onTertiary = Color(0xFF07251D),
    tertiaryContainer = Color(0xFF1F4A3E),
    onTertiaryContainer = Color(0xFFD3F2E7),
    background = TableBase,
    onBackground = Parchment,
    surface = TableBase,
    onSurface = Parchment,
    surfaceVariant = Panel,
    onSurfaceVariant = ParchmentDim,
    surfaceTint = Gold,
    inverseSurface = Parchment,
    inverseOnSurface = Color(0xFF2A2218),
    error = CrimsonLight,
    onError = Color(0xFF410002),
    errorContainer = CrimsonDeep,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF94856B),
    outlineVariant = Color(0xFF4D4133),
    scrim = Color.Black,
    surfaceBright = PanelTop,
    surfaceDim = TableEdge,
    surfaceContainerLowest = TableEdge,
    surfaceContainerLow = PanelLow,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelTop,
)

/**
 * 앱 전체의 모습. 기기의 밝은/어두운 설정과 상관없이 어두운 테이블 한 가지다(게임의 분위기와 카드·역할 색의 대비를 한 가지로 맞춘다).
 */
@Composable
fun CoupTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 어두운 배경 위이므로 상태 표시줄·내비게이션 아이콘은 밝게 그린다
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }
    MaterialTheme(colorScheme = CoupColorScheme, typography = Typography, content = content)
}
