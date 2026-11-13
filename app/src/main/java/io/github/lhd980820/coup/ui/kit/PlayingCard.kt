package io.github.lhd980820.coup.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.Parchment

/** 카드의 가로:세로. */
const val CARD_ASPECT: Float = 0.714f

/** 이 너비보다 좁으면 작은 카드(초상 얼굴 + 문양)로, 넓으면 온전한 카드(타원 초상 + 문양 + 이름)로 그린다. */
private val MINI_BELOW = 52.dp
private const val CORNER = 0.085f
private const val PORTRAIT_ASPECT = 0.745f
private const val MINI_ZOOM = 1.5f

private val OvalShape = GenericShape { size, _ -> addOval(Rect(0f, 0f, size.width, size.height)) }
private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val LostTop = Color(0xFF3D3833)
private val LostBottom = Color(0xFF161412)
private val LostAccent = Color(0xFFB5ADA2)
private val BackTop = Color(0xFF6B1F2A)
private val BackBottom = Color(0xFF2A0A10)

/**
 * 카드 앞면. 크기는 [modifier]로 정하고(가로:세로 = [CARD_ASPECT]) 안의 모든 것은 너비에 비례해 그린다 — 자리의 작은 카드부터 확대 창의 큰 카드까지 같은 그림이다.
 * [lost]는 잃어서 공개된 카드(빛이 바래고 X 표시가 붙는다), [selected]는 고른 카드(밝은 금색 테두리).
 * 카드 안의 글자는 그림의 일부라 접근성 트리에 내지 않는다 — 이름은 부르는 쪽이 [modifier]의 semantics로 알린다.
 */
@Composable
fun PlayingCardFace(visual: RoleVisual, name: String, modifier: Modifier = Modifier, lost: Boolean = false, selected: Boolean = false) {
    BoxWithConstraints(modifier.clearAndSetSemantics { }) {
        val w = maxWidth
        val h = maxHeight
        val shape = RoundedCornerShape(w * CORNER)
        val accent = if (lost) LostAccent else visual.accent
        val body = if (lost) listOf(LostTop, LostBottom) else listOf(visual.top, visual.bottom)
        val line = if (selected) GoldBright else accent.copy(alpha = 0.8f)
        val lineWidth = if (selected) (w * 0.04f).coerceAtLeast(2.5.dp) else (w * 0.018f).coerceAtLeast(1.dp)
        Box(Modifier.fillMaxSize().clip(shape).background(Brush.verticalGradient(body)).border(lineWidth, line, shape)) {
            if (w < MINI_BELOW) MiniFace(visual, accent, lost, w, h) else FullFace(visual, name, accent, lost, w, h)
        }
    }
}

@Composable
private fun MiniFace(visual: RoleVisual, accent: Color, lost: Boolean, w: Dp, h: Dp) {
    Column(Modifier.fillMaxSize().padding(w * 0.075f)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(w * 0.07f))) { Portrait(visual, accent, lost, zoomed = true) }
        Box(Modifier.fillMaxWidth().height(h * 0.25f), contentAlignment = Alignment.Center) {
            Icon(visual.emblem, contentDescription = null, tint = accent, modifier = Modifier.size(h * 0.2f))
        }
    }
    if (lost) LostMark(Modifier.fillMaxSize().padding(w * 0.2f))
}

@Composable
private fun FullFace(visual: RoleVisual, name: String, accent: Color, lost: Boolean, w: Dp, h: Dp) {
    val ovalWidth = w * 0.70f
    val ovalHeight = ovalWidth / PORTRAIT_ASPECT
    val ovalTop = w * 0.075f
    val medal = w * 0.23f
    val rim = (w * 0.014f).coerceAtLeast(1.dp)
    val rimColor = if (lost) LostAccent.copy(alpha = 0.7f) else Gold
    Box(Modifier.fillMaxSize()) {
        // 안쪽 실선
        Box(Modifier.fillMaxSize().padding(w * 0.04f).border((w * 0.008f).coerceAtLeast(0.5.dp), GoldDeep.copy(alpha = 0.55f), RoundedCornerShape(w * 0.055f)))
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = ovalTop).size(ovalWidth, ovalHeight).clip(OvalShape).border(rim, rimColor, OvalShape),
        ) {
            Portrait(visual, accent, lost, zoomed = false)
            if (lost) LostMark(Modifier.fillMaxSize().padding(ovalWidth * 0.2f))
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = ovalTop + ovalHeight - medal / 2)
                .size(medal)
                .clip(CircleShape)
                .background(Brush.verticalGradient(if (lost) listOf(LostTop, LostBottom) else listOf(visual.top, visual.bottom)))
                .border(rim, rimColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(visual.emblem, contentDescription = null, tint = if (lost) LostAccent else GoldBright, modifier = Modifier.size(medal * 0.62f))
        }
        val nameTop = ovalTop + ovalHeight + medal / 2
        // 문장 아래부터 안쪽 실선 위까지가 이름 자리다
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = nameTop).fillMaxWidth().height(h - nameTop - w * 0.065f).padding(horizontal = w * 0.07f),
            contentAlignment = Alignment.Center,
        ) {
            // 카드에 새긴 이름: 카드 크기에 맞춰 그리므로 글자 크기 설정을 따르지 않는다(이름은 확대 창과 스크린리더로도 전해진다)
            val size = with(LocalDensity.current) { (w * 0.125f).toSp() }
            Text(
                name,
                color = if (lost) LostAccent else Parchment,
                fontSize = size,
                lineHeight = size * 1.15f,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 초상. [zoomed]면 얼굴 쪽으로 당겨 네모 칸을 채우고(작은 카드), 아니면 타원 전체를 보인다. 초상이 없는 역할은 문양을 크게 그린다. */
@Composable
private fun Portrait(visual: RoleVisual, accent: Color, lost: Boolean, zoomed: Boolean) {
    val res = visual.portrait
    if (res == null) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(visual.emblem, contentDescription = null, tint = accent, modifier = Modifier.fillMaxSize(0.55f))
        }
        return
    }
    Image(
        painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = if (zoomed) BiasAlignment(0f, -0.6f) else Alignment.Center,
        colorFilter = if (lost) Greyscale else null,
        modifier = Modifier.fillMaxSize().then(
            // 타원 초상의 네 귀퉁이(테두리)가 보이지 않도록 가운데를 당겨 그린다
            if (zoomed) Modifier.graphicsLayer { scaleX = MINI_ZOOM; scaleY = MINI_ZOOM; transformOrigin = TransformOrigin(0.5f, 0.42f) } else Modifier,
        ),
    )
}

/** 잃은 카드 표시: 색만으로 전하지 않도록 X를 긋는다. */
@Composable
private fun LostMark(modifier: Modifier) {
    Icon(CoupIcons.Cross, contentDescription = null, tint = Crimson.copy(alpha = 0.92f), modifier = modifier)
}

/** 카드 뒷면: 짙은 붉은 바탕에 금색 마름모 격자와 가운데 문장. */
@Composable
fun PlayingCardBack(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val radius = CornerRadius(w * CORNER)
        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, radius)) }
        clipPath(outline) {
            drawRect(Brush.verticalGradient(listOf(BackTop, BackBottom)))
            val step = w * 0.21f
            val stroke = (w * 0.012f).coerceAtLeast(1f)
            var x = -h
            while (x < w) {
                drawLine(Gold.copy(alpha = 0.22f), Offset(x, 0f), Offset(x + h, h), stroke)
                drawLine(Gold.copy(alpha = 0.22f), Offset(x + h, 0f), Offset(x, h), stroke)
                x += step
            }
        }
        val edge = (w * 0.035f).coerceAtLeast(1.dp.toPx())
        drawRoundRect(Gold.copy(alpha = 0.9f), Offset(edge / 2, edge / 2), Size(w - edge, h - edge), radius, style = Stroke(edge))
        val inset = w * 0.11f
        drawRoundRect(
            Gold.copy(alpha = 0.5f), Offset(inset, inset), Size(w - inset * 2, h - inset * 2), CornerRadius(w * 0.04f),
            style = Stroke((w * 0.012f).coerceAtLeast(1f)),
        )
        val r = w * 0.21f
        drawCircle(BackBottom, r, center)
        drawCircle(Gold, r, center, style = Stroke((w * 0.028f).coerceAtLeast(1f)))
        val diamond = Path().apply {
            moveTo(center.x, center.y - r * 0.58f)
            lineTo(center.x + r * 0.42f, center.y)
            lineTo(center.x, center.y + r * 0.58f)
            lineTo(center.x - r * 0.42f, center.y)
            close()
        }
        drawPath(diamond, Brush.verticalGradient(listOf(GoldBright, Gold), startY = center.y - r, endY = center.y + r))
    }
}
