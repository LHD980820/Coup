package io.github.lhd980820.coup.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.ParchmentDim

private fun DrawScope.coin(center: Offset, r: Float) {
    drawCircle(
        Brush.radialGradient(listOf(GoldBright, Gold, GoldDeep), center = center + Offset(-r * 0.35f, -r * 0.4f), radius = r * 1.7f),
        r, center,
    )
    drawCircle(GoldDeep, r - r * 0.07f, center, style = Stroke(r * 0.14f))
    drawCircle(GoldDeep.copy(alpha = 0.75f), r * 0.58f, center, style = Stroke(r * 0.1f))
    drawCircle(GoldDeep.copy(alpha = 0.6f), r * 0.16f, center)
}

/** 코인 하나. */
@Composable
fun CoinIcon(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) { coin(center, this.size.minDimension / 2f) }
}

/**
 * 코인 더미: 많을수록 겹쳐 보인다(1~2개는 한 닢, 3~6개는 두 닢, 7개부터는 세 닢 — 7개면 쿠를 할 수 있다). 0개는 빈 자리만 그린다.
 * 정확한 수는 옆의 숫자가 전한다.
 */
@Composable
fun CoinPile(count: Int, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val layers = when {
        count >= 7 -> 3
        count >= 3 -> 2
        else -> 1
    }
    Canvas(modifier.size(width = size * (1f + (layers - 1) * OVERLAP), height = size)) {
        val r = this.size.height / 2f
        if (count <= 0) {
            drawCircle(ParchmentDim.copy(alpha = 0.55f), r - r * 0.1f, Offset(r, r), style = Stroke(r * 0.16f))
            return@Canvas
        }
        repeat(layers) { i -> coin(Offset(r + i * r * 2f * OVERLAP, r), r) }
    }
}

private const val OVERLAP = 0.42f
