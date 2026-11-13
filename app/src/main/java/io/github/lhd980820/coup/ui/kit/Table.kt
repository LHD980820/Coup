package io.github.lhd980820.coup.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.ui.theme.FeltHigh
import io.github.lhd980820.coup.ui.theme.FeltLow
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.TableBase
import io.github.lhd980820.coup.ui.theme.TableEdge
import io.github.lhd980820.coup.ui.theme.TableGlow

/** 금색 실선(패널 테두리). */
val Hairline: Color = Gold.copy(alpha = 0.22f)
val PanelShape: Shape = RoundedCornerShape(16.dp)

/** 이미 바깥에서 테이블을 그렸는가(그렸으면 안쪽 화면은 다시 그리지 않아 이음매가 생기지 않는다). */
private val LocalTableDrawn = staticCompositionLocalOf { false }

private fun Modifier.table(): Modifier = drawBehind {
    drawRect(TableEdge)
    // 위쪽 가운데에서 비추는 촛불빛: 가운데가 밝고 가장자리로 갈수록 어둡다
    drawRect(
        Brush.radialGradient(
            0f to TableGlow,
            0.45f to TableBase,
            1f to TableEdge,
            center = Offset(size.width / 2f, size.height * 0.34f),
            radius = maxOf(size.width, size.height) * 0.85f,
        ),
    )
}

/**
 * 모든 화면의 바탕인 테이블. 화면마다 `Surface(color = background)` 대신 쓴다.
 * 액티비티가 창 전체(시스템 바 아래까지)에 한 번 그리면 안쪽 화면은 투명하게 얹힌다.
 */
@Composable
fun TableSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val drawn = LocalTableDrawn.current
    CompositionLocalProvider(LocalTableDrawn provides true, LocalContentColor provides Parchment) {
        Box((if (drawn) modifier else modifier.table()).fillMaxSize(), content = content)
    }
}

/** 테이블 위에 얹힌 판: 위가 살짝 밝은 면 + 금색 실선 테두리. */
fun Modifier.panel(shape: Shape = PanelShape, top: Color = PanelHigh, bottom: Color = Panel, line: Color = Hairline, lineWidth: Dp = 1.dp): Modifier =
    this.clip(shape).background(Brush.verticalGradient(listOf(top, bottom))).border(lineWidth, line, shape)

/** 가운데 무대: 붉은 펠트. */
fun Modifier.felt(shape: Shape = PanelShape): Modifier =
    this.clip(shape).background(Brush.verticalGradient(listOf(FeltHigh, FeltLow))).border(1.dp, Gold.copy(alpha = 0.45f), shape)

/**
 * 스크롤 영역의 위아래가 잘릴 때 그 가장자리를 서서히 흐리게 한다 — 글자가 반쯤 잘려 보이는 대신 "더 있다"는 신호가 된다.
 * `verticalScroll` **앞에** 붙인다(보이는 창 전체에 그려야 한다).
 */
fun Modifier.fadingEdges(state: ScrollState, length: Dp = 20.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val l = length.toPx()
        if (state.canScrollBackward) {
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = l), blendMode = BlendMode.DstIn)
        }
        if (state.canScrollForward) {
            drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - l, endY = size.height), blendMode = BlendMode.DstIn)
        }
    }
