package io.github.lhd980820.coup.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.ui.theme.SeatTints

private val Glyph = Color(0xFF1B1408)

/** 자리 번호에 따른 아바타 색. */
fun seatTint(index: Int): Color = SeatTints[((index % SeatTints.size) + SeatTints.size) % SeatTints.size]

/** 아바타에 쓰는 한 글자: 이름이 숫자로 끝나면 그 숫자("AI 3" -> "3"), 아니면 첫 글자. */
fun avatarGlyph(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val last = trimmed.last()
    return if (last.isDigit()) last.toString() else trimmed.first().uppercase()
}

/** 플레이어를 나타내는 동그란 표식. 장식이라 접근성 트리에는 내지 않는다(이름은 옆의 글자가 전한다). */
@Composable
fun Avatar(
    name: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    ring: Color = lerp(tint, Color.Black, 0.45f),
    /** 글자 대신 그릴 그림(내 자리처럼 이름이 바로 옆에 있어 글자가 겹치는 곳). */
    icon: ImageVector? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.18f), lerp(tint, Color.Black, 0.3f))))
            .border((size.value * 0.07f).dp, ring, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Glyph, modifier = Modifier.size(size * 0.58f))
            return@Box
        }
        val fontSize = with(LocalDensity.current) { (size * 0.48f).toSp() }
        Text(avatarGlyph(name), color = Glyph, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Black, maxLines = 1)
    }
}
