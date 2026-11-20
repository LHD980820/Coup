package io.github.lhd980820.coup.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Parchment
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// 누가 누구에게 무엇을 하는가를 테이블 위에 그린다(docs/DESIGN.md "행동이 보이는 테이블").

/**
 * 테이블 위 자리들의 위치(화면 전체 기준). 자리와 내 손패가 놓일 때마다 적어 두고, 화살표(그리고 날아가는 코인)가 이 위치를 잇는다.
 */
@Stable
internal class TableAnchors {
    private val rects = mutableStateMapOf<PlayerId, Rect>()

    operator fun get(id: PlayerId): Rect? = rects[id]

    fun place(id: PlayerId): Modifier = Modifier.onGloballyPositioned { rects[id] = it.boundsInRoot() }

    /** 가운데 판이 놓인 곳. 화살표는 판 아래로 지나간다(판의 그림을 가리지 않는다). 판이 없으면(응답 시트가 대신할 때) null. */
    var stage: Rect? by mutableStateOf(null)

    fun stageArea(): Modifier = Modifier.onGloballyPositioned { stage = it.boundsInRoot() }
}

internal enum class ArrowKind {
    /** 행동이 대상을 노린다(갈취·암살·쿠). */
    ATTACK,

    /** 막는 사람이 행동한 사람을 막아선다. */
    BLOCK,

    /** 도전하는 사람이 주장한 사람에게 맞선다. */
    CHALLENGE,
}

/** 테이블에 그릴 화살표 하나: [from]에서 [to]로, 무엇으로([role] 또는 [action]). */
internal data class ArrowSpec(val from: PlayerId, val to: PlayerId, val kind: ArrowKind, val role: RoleId?, val action: ActionId?) {
    /** 자동 테스트가 무엇이 그려졌는지 확인하는 이름. */
    val label: String get() = "${from.value}>${to.value}:${kind.name.lowercase()}"
}

/** 지금 진행 배너가 말하는 "누가 누구에게"를 화살표로 바꾼다. 대상이 없는 행동(세금·교환·해외 원조·수입)에는 화살표가 없다. */
internal fun arrowsFor(banner: Banner): List<ArrowSpec> = when (banner) {
    is Banner.ActionDeclared -> listOfNotNull(banner.target?.let { ArrowSpec(banner.actor, it, ArrowKind.ATTACK, banner.claimedRoles.firstOrNull(), banner.actionId) })
    is Banner.BlockWindow -> listOfNotNull(banner.target?.let { ArrowSpec(banner.actor, it, ArrowKind.ATTACK, null, banner.actionId) })
    is Banner.BlockDeclared -> listOf(ArrowSpec(banner.blocker, banner.actor, ArrowKind.BLOCK, banner.role, banner.actionId))
    is Banner.Challenged -> listOf(ArrowSpec(banner.challenger, banner.challenged, ArrowKind.CHALLENGE, null, null))
    else -> emptyList()
}

/** 화살표의 색과 실려 가는 문양. 역할이 있으면 그 역할의 색·문양, 쿠는 붉은 번개, 도전은 붉은 칼. */
private class ArrowLook(val color: Color, val top: Color, val bottom: Color, val icon: ImageVector)

private fun lookOf(spec: ArrowSpec): ArrowLook {
    val visual = spec.role?.let { RoleVisuals.of(it.value) }
    return when {
        spec.kind == ArrowKind.CHALLENGE -> ArrowLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Swords)
        spec.kind == ArrowKind.BLOCK && visual != null -> ArrowLook(visual.accent, visual.top, visual.bottom, CoupIcons.Shield)
        visual != null -> ArrowLook(visual.accent, visual.top, visual.bottom, visual.emblem)
        spec.action?.value == "coup" -> ArrowLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Bolt)
        else -> ArrowLook(GoldBright, CrimsonDeep, Color(0xFF2A0A0E), spec.action?.let { ActionUiCatalog.icon(it) } ?: CoupIcons.Star)
    }
}

/** 화살표가 지금 무엇을 잇고 있는지(자동 테스트용). */
internal val ArrowsShown = SemanticsPropertyKey<List<String>>("ArrowsShown")
private var SemanticsPropertyReceiver.arrowsShown by ArrowsShown

private const val DRAW_IN_MS = 420

/**
 * 진행 배너가 말하는 "누가 누구에게"를 테이블 위의 화살표로 그린다. 행동한 자리에서 대상 자리(나면 내 손패)로 역할 색의 곡선이 뻗어 가고,
 * 그 가운데에 행동의 문양(암살 단검·갈취 닻·쿠 번개, 막기 방패, 도전 칼)이 실려 있다. 화살표는 흐르는 점선이라 방향이 한눈에 보인다.
 * 대상 자리는 테두리가 맥박친다. 그림일 뿐이라 누르는 것을 가로채지 않는다. 연출이 꺼져 있으면 움직이지 않는 화살표로 그린다.
 */
@Composable
internal fun ActionArrows(banner: Banner, anchors: TableAnchors, modifier: Modifier = Modifier) {
    val specs = arrowsFor(banner)
    val motion = LocalMotion.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val labels = specs.filter { anchors[it.from] != null && anchors[it.to] != null }.map { it.label }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .clearAndSetSemantics { testTag = GameTags.ARROWS; arrowsShown = labels },
    ) {
        val stage = anchors.stage?.translate(-origin)
        specs.forEach { spec ->
            val from = anchors[spec.from] ?: return@forEach
            val to = anchors[spec.to] ?: return@forEach
            Arrow(spec, from.translate(-origin), to.translate(-origin), stage, motion)
        }
    }
}

@Composable
private fun Arrow(spec: ArrowSpec, from: Rect, to: Rect, under: Rect?, motion: Motion) {
    val look = lookOf(spec)
    val density = LocalDensity.current
    // 처음 나타날 때 행동한 자리에서 대상으로 뻗어 간다
    val reach = remember(spec) { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(spec) { if (reach.value < 1f) reach.animateTo(1f, tween(motion.ms(DRAW_IN_MS), easing = FastOutSlowInEasing)) }
    val flow: State<Float>? = if (motion.enabled) {
        rememberInfiniteTransition(label = "arrow").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "flow")
    } else {
        null
    }
    val pulse: State<Float>? = if (motion.enabled) {
        rememberInfiniteTransition(label = "target").animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    } else {
        null
    }
    val curve = remember(from, to) { arrowCurve(from, to, with(density) { 6.dp.toPx() }) }
    Canvas(Modifier.fillMaxSize()) {
        // 가운데 판 위는 그리지 않는다: 화살표가 판 아래로 지나가는 것처럼 보이고 판의 그림·글을 가리지 않는다
        val hole = under?.let { Path().apply { addRoundRect(RoundRect(it, CornerRadius(16.dp.toPx()))) } }
        if (hole == null) {
            drawTarget(to, look.color, pulse?.value ?: 1f)
            drawArrow(curve, look.color, reach.value, flow?.value ?: 0f)
        } else {
            clipPath(hole, ClipOp.Difference) {
                drawTarget(to, look.color, pulse?.value ?: 1f)
                drawArrow(curve, look.color, reach.value, flow?.value ?: 0f)
            }
        }
    }
    // 곡선 가운데에 문양을 싣는다(곡선이 다 뻗은 뒤에 나타난다). 가운데가 판 아래에 묻히면 판 밖의 가장 가까운 곳으로 옮긴다
    val disc = 30.dp
    val half = with(density) { (disc / 2).toPx() }
    val mid = discPoint(curve, under?.inflate(half))
    Box(
        Modifier
            .offset { IntOffset((mid.x - half).roundToInt(), (mid.y - half).roundToInt()) }
            .size(disc)
            .graphicsLayer {
                val shown = ((reach.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
                alpha = shown
                scaleX = 0.6f + 0.4f * shown
                scaleY = 0.6f + 0.4f * shown
            }
            .panel(CircleShape, top = look.top, bottom = look.bottom, line = look.color, lineWidth = 1.5.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(look.icon, contentDescription = null, tint = if (spec.kind == ArrowKind.ATTACK && spec.role == null) GoldBright else Parchment, modifier = Modifier.size(17.dp)) }
}

/** 곡선 위에서 문양을 실을 곳: 가운데(t = 0.5)에서 시작해 [avoid] 밖의 가장 가까운 곳. 어디나 가려지면 가운데. */
internal fun discPoint(curve: ArrowCurve, avoid: Rect?): Offset {
    if (avoid == null) return curve.point(0.5f)
    for (step in 0..8) {
        for (sign in intArrayOf(-1, 1)) {
            val p = curve.point(0.5f + sign * step * 0.05f)
            if (!avoid.contains(p)) return p
        }
    }
    return curve.point(0.5f)
}

/** 두 자리를 잇는 곡선: 서로 마주 보는 테두리에서 나가고 들어오며, 가운데가 위쪽(세로로 놓이면 오른쪽)으로 휜다. */
internal class ArrowCurve(val start: Offset, val control: Offset, val end: Offset) {
    fun point(t: Float): Offset {
        val u = 1 - t
        return Offset(u * u * start.x + 2 * u * t * control.x + t * t * end.x, u * u * start.y + 2 * u * t * control.y + t * t * end.y)
    }

    /** t에서의 진행 방향(라디안). */
    fun heading(t: Float): Float {
        val dx = 2 * (1 - t) * (control.x - start.x) + 2 * t * (end.x - control.x)
        val dy = 2 * (1 - t) * (control.y - start.y) + 2 * t * (end.y - control.y)
        return atan2(dy, dx)
    }
}

internal fun arrowCurve(from: Rect, to: Rect, gap: Float, dip: Float = gap * 9f): ArrowCurve {
    // 같은 줄에 나란히 앉은 자리끼리는 마주 보는 테두리 사이가 너무 짧다: 아래로 크게 휘어 돌아간다
    if (from.top < to.bottom && to.top < from.bottom) {
        val start = Offset(from.center.x, from.bottom + gap)
        val end = Offset(to.center.x, to.bottom + gap)
        val control = Offset((start.x + end.x) / 2, maxOf(start.y, end.y) + maxOf(dip, abs(end.x - start.x) * 0.35f))
        return ArrowCurve(start, control, end)
    }
    val a = from.center
    val b = to.center
    val start = edgeToward(from, b, gap)
    val end = edgeToward(to, a, gap)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy).coerceAtLeast(1f)
    // 수직 방향으로 길이의 1/4만큼 휜다. 위쪽(y가 작은 쪽)으로, 거의 세로면 오른쪽으로
    var nx = -dy / length
    var ny = dx / length
    if (ny > 0f || (abs(ny) < 0.2f && nx < 0f)) {
        nx = -nx
        ny = -ny
    }
    val bend = length * 0.25f
    val control = Offset((start.x + end.x) / 2 + nx * bend, (start.y + end.y) / 2 + ny * bend)
    return ArrowCurve(start, control, end)
}

/** [rect]의 가운데에서 [toward] 쪽으로 나가는 선이 테두리와 만나는 점(조금 바깥). */
internal fun edgeToward(rect: Rect, toward: Offset, gap: Float): Offset {
    val c = rect.center
    val dx = toward.x - c.x
    val dy = toward.y - c.y
    if (dx == 0f && dy == 0f) return c
    val hw = rect.width / 2 + gap
    val hh = rect.height / 2 + gap
    val t = min(if (dx != 0f) hw / abs(dx) else Float.MAX_VALUE, if (dy != 0f) hh / abs(dy) else Float.MAX_VALUE)
    return Offset(c.x + dx * t, c.y + dy * t)
}

private fun DrawScope.drawArrow(curve: ArrowCurve, color: Color, reach: Float, flow: Float) {
    if (reach <= 0f) return
    val full = Path().apply {
        moveTo(curve.start.x, curve.start.y)
        quadraticTo(curve.control.x, curve.control.y, curve.end.x, curve.end.y)
    }
    val measure = PathMeasure().apply { setPath(full, false) }
    val head = 13.dp.toPx()
    val visible = Path()
    measure.getSegment(0f, (measure.length - head * 0.6f).coerceAtLeast(0f) * reach, visible, true)
    // 은은한 빛 + 흐르는 점선
    drawPath(visible, color.copy(alpha = 0.22f), style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round))
    val dash = 13.dp.toPx()
    val space = 8.dp.toPx()
    drawPath(
        visible,
        color.copy(alpha = 0.95f),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, space), -(dash + space) * flow)),
    )
    if (reach >= 1f) {
        // 화살촉: 끝에서의 진행 방향으로
        val tip = curve.end
        val angle = curve.heading(1f)
        val back = Offset(tip.x - head * kotlin.math.cos(angle), tip.y - head * kotlin.math.sin(angle))
        val wing = head * 0.62f
        val nx = -kotlin.math.sin(angle) * wing
        val ny = kotlin.math.cos(angle) * wing
        val tri = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(back.x + nx, back.y + ny)
            lineTo(back.x - nx, back.y - ny)
            close()
        }
        drawPath(tri, color)
    }
}

/** 대상 자리의 테두리가 맥박친다(노려지고 있다). */
private fun DrawScope.drawTarget(rect: Rect, color: Color, pulse: Float) {
    val grow = (2.dp + 4.dp * pulse).toPx()
    drawRoundRect(
        color.copy(alpha = 0.35f + 0.5f * (1f - pulse)),
        topLeft = Offset(rect.left - grow, rect.top - grow),
        size = Size(rect.width + grow * 2, rect.height + grow * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx() + grow),
        style = Stroke(2.5.dp.toPx()),
    )
}
