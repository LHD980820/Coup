package io.github.lhd980820.coup.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 극적인 순간의 연출(docs/DESIGN.md "극적인 순간"). 모두 그림일 뿐 누르는 것을 가로채지 않고, 연출이 꺼져 있으면 나타나지 않는다.

/** 지금 보이는 순간의 이름([Moment.kind]). 자동 테스트가 무엇이 보이는지 확인하는 데 쓴다. */
internal val MomentKind = SemanticsPropertyKey<String>("MomentKind")
private var SemanticsPropertyReceiver.momentKind by MomentKind

/** 탈락 도장이 다 찍혔는가(떨어지는 중이면 false). 자동 테스트가 도장이 "쾅" 하고 찍혔는지 확인하는 데 쓴다. */
internal val StampLanded = SemanticsPropertyKey<Boolean>("StampLanded")
private var SemanticsPropertyReceiver.stampLanded by StampLanded

private const val ENTER_MS = 240
private const val EXIT_MS = 260
private const val FLIP_START_MS = 140
private const val FLIP_MS = 380
private const val STAMP_START_MS = 600
private const val STAMP_MS = 260
private const val SHAKE_MS = 260

/** 도장이 "쾅" 하고 찍히는 곡선: 크게 시작해 1보다 조금 작아졌다가 자리를 잡는다. */
internal val SlamEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

private val BandInk = Color(0xFF0D0907)

private fun window(t: Float, from: Float, length: Float): Float = if (length <= 0f) 1f else ((t - from) / length).coerceIn(0f, 1f)

/** 들어오고(0 -> 1) 머물고 나가는(1 -> 0) 모양. */
private fun envelope(t: Float, enter: Float, exit: Float): Float = when {
    t < enter -> FastOutSlowInEasing.transform(window(t, 0f, enter))
    t > exit -> 1f - FastOutLinearInEasing.transform(window(t, exit, 1f - exit))
    else -> 1f
}

/** 가라앉는 흔들림: 처음에 크게, 점점 작게 [cycles]번 좌우로. */
internal fun shake(progress: Float, cycles: Int = 3): Float =
    if (progress <= 0f || progress >= 1f) 0f else sin(progress * cycles * 2 * PI).toFloat() * (1f - progress)

/** 순간마다 다른 모습: 강조 색, 둥근 문장의 바탕과 그림. 카드를 뒤집는 순간(공개)은 문장 대신 카드를 쓴다. */
private class MomentLook(val accent: Color, val top: Color, val bottom: Color, val icon: ImageVector?, val iconTint: Color, val stamp: Boolean)

private fun lookOf(m: Moment): MomentLook = when (m) {
    is Moment.Challenge -> MomentLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Swords, Parchment, stamp = false)
    is Moment.Reveal -> if (m.proven) {
        MomentLook(GoldBright, GoldBright, Gold, CoupIcons.Check, GoldInk, stamp = true)
    } else {
        MomentLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Cross, Parchment, stamp = true)
    }
    is Moment.Block -> RoleVisuals.of(m.role.value).let { MomentLook(it.accent, it.top, it.bottom, CoupIcons.Shield, GoldBright, stamp = false) }
    is Moment.Coup -> MomentLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Bolt, Parchment, stamp = false)
    is Moment.Eliminated -> MomentLook(CrimsonLight, Crimson, CrimsonDeep, CoupIcons.Cross, Parchment, stamp = true)
}

/**
 * 지금 무대에 오른 순간을 화면 가운데를 가로지르는 띠로 그린다(격투 게임의 "컷인"처럼).
 * 띠가 위아래로 펼쳐지며 들어오고, 문장과 글이 왼쪽에서 밀려 들어왔다가 오른쪽으로 빠진다. 공개(증명/거짓말)는 카드가 뒤집힌 뒤
 * 판정이 도장처럼 찍히고, 거짓말과 탈락은 도장이 찍힐 때 띠가 흔들린다. 다 그리면 [MomentStage.finished]로 다음 순간을 부른다.
 */
@Composable
internal fun MomentOverlay(stage: MomentStage, texts: GameTexts, state: GameUiState, modifier: Modifier = Modifier) {
    val played = stage.playing ?: return
    val motion = LocalMotion.current
    val m = played.moment
    val total = motion.ms(m.totalMillis).coerceAtLeast(1)
    val clock = remember(played.seq) { Animatable(0f) }
    LaunchedEffect(played.seq) {
        clock.animateTo(1f, tween(total, easing = LinearEasing))
        stage.finished(played)
    }
    fun at(base: Int) = motion.ms(base).toFloat() / total
    val enter = at(ENTER_MS)
    val exit = 1f - at(EXIT_MS)
    val text = texts.moment(m)
    val look = lookOf(m)
    val stampAt = if (m is Moment.Reveal) at(STAMP_START_MS) else at(ENTER_MS / 2)
    val stampLength = at(STAMP_MS)
    val shakes = look.stamp && !(m is Moment.Reveal && m.proven)

    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { testTag = GameTags.MOMENT; momentKind = m.kind }
            // 둘레를 살짝 어둡게 해 띠에 눈이 가게 한다
            .drawBehind {
                val a = envelope(clock.value, enter, exit)
                drawRect(Brush.radialGradient(0f to Color.Black.copy(alpha = 0.12f * a), 1f to Color.Black.copy(alpha = 0.42f * a), center = center, radius = size.maxDimension * 0.7f))
            },
    ) {
        Box(
            Modifier
                .align(BiasAlignment(0f, -0.12f))
                .fillMaxWidth()
                .graphicsLayer {
                    val t = clock.value
                    scaleY = if (t < enter) FastOutSlowInEasing.transform(window(t, 0f, enter)) else 1f
                    alpha = envelope(t, enter, exit)
                    // 도장이 찍히는 순간 띠가 흔들린다
                    if (shakes) translationX = shake(window(t, stampAt + stampLength * 0.7f, at(SHAKE_MS))) * 10.dp.toPx()
                }
                .drawWithContent {
                    drawBand(look.accent, sweep = window(clock.value, enter, exit - enter))
                    drawContent()
                }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = 560.dp)
                    .graphicsLayer {
                        val t = clock.value
                        // 왼쪽에서 밀려 들어오고 오른쪽으로 빠진다
                        translationX = when {
                            t < enter -> (1f - FastOutSlowInEasing.transform(window(t, 0f, enter))) * -56.dp.toPx()
                            t > exit -> FastOutLinearInEasing.transform(window(t, exit, 1f - exit)) * 40.dp.toPx()
                            else -> 0f
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (m) {
                    is Moment.Reveal -> FlippingCard(m, clock, at(FLIP_START_MS), at(FLIP_MS))
                    is Moment.Eliminated -> EliminatedFace(m.player, m.isMe, state)
                    else -> Medallion(look)
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Headline(text.headline, look, clock, stampAt, stampLength)
                    // 공개는 카드가 뒤집히고 판정이 찍힌 뒤에 설명이 나온다(먼저 나오면 결과를 미리 말해 버린다)
                    val explain = Modifier.graphicsLayer { if (m is Moment.Reveal) alpha = window(clock.value, stampAt + stampLength * 0.5f, stampLength) }
                    Text(text.line, explain, style = MaterialTheme.typography.titleSmall, color = Parchment, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    text.detail?.let { Text(it, explain, style = MaterialTheme.typography.labelLarge, color = ParchmentDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** 띠: 가장자리로 갈수록 투명해지는 먹색 바탕, 위아래 강조 색 실선, 지나가는 한 줄기 빛. */
private fun DrawScope.drawBand(accent: Color, sweep: Float) {
    // 가운데는 판이 비치지 않게 완전히 덮고, 양 끝만 테이블로 녹아든다
    val fade = Brush.horizontalGradient(0f to BandInk.copy(alpha = 0f), 0.08f to BandInk, 0.92f to BandInk, 1f to BandInk.copy(alpha = 0f))
    drawRect(fade)
    drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.3f, size.height / 2), radius = size.height * 1.6f))
    val line = Brush.horizontalGradient(0f to Color.Transparent, 0.2f to accent.copy(alpha = 0.9f), 0.8f to accent.copy(alpha = 0.9f), 1f to Color.Transparent)
    val w = 2.dp.toPx()
    drawRect(line, topLeft = Offset.Zero, size = Size(size.width, w))
    drawRect(line, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
    if (sweep in 0.001f..0.999f) {
        val x = -size.width * 0.3f + sweep * size.width * 1.6f
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent,
                start = Offset(x - size.height, 0f), end = Offset(x + size.height, size.height),
            ),
        )
    }
}

@Composable
private fun Medallion(look: MomentLook) {
    Box(
        Modifier.size(62.dp).panel(CircleShape, top = look.top, bottom = look.bottom, line = look.accent, lineWidth = 2.dp),
        contentAlignment = Alignment.Center,
    ) { look.icon?.let { Icon(it, contentDescription = null, tint = look.iconTint, modifier = Modifier.size(32.dp)) } }
}

/** 크게 외치는 한 마디. 판정(증명/거짓말/탈락)은 도장처럼 찍힌다. */
@Composable
private fun Headline(text: String, look: MomentLook, clock: Animatable<Float, *>, stampAt: Float, stampLength: Float) {
    val style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    val autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = style.fontSize, stepSize = 1.sp)
    if (!look.stamp) {
        Text(text, style = style, color = look.accent, maxLines = 1, softWrap = false, autoSize = autoSize)
        return
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .graphicsLayer {
                val p = window(clock.value, stampAt, stampLength)
                val s = 1f + (1f - SlamEasing.transform(p)) * 1.3f
                scaleX = s
                scaleY = s
                alpha = window(clock.value, stampAt, stampLength * 0.35f)
                rotationZ = -7f
            }
            .border(2.5.dp, look.accent, shape)
            .background(look.accent.copy(alpha = 0.12f), shape)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        look.icon?.let { Icon(it, contentDescription = null, tint = look.accent, modifier = Modifier.size(26.dp)) }
        Text(text, style = style, color = look.accent, maxLines = 1, softWrap = false, autoSize = autoSize)
    }
}

/** 공개된 카드: 뒷면에서 앞면으로 크게 뒤집힌다(그 뒤에 판정 도장이 찍힌다). */
@Composable
private fun FlippingCard(m: Moment.Reveal, clock: Animatable<Float, *>, start: Float, length: Float) {
    val faceUp by remember(clock) { derivedStateOf { window(clock.value, start, length) >= 0.5f } }
    Box(
        Modifier
            .size(width = 76.dp, height = 106.dp)
            .graphicsLayer {
                rotationY = window(clock.value, start, length) * 180f
                cameraDistance = 14f * density
            },
    ) {
        if (!faceUp) {
            RoleCard(role = null, faceUp = false, modifier = Modifier.fillMaxSize())
        } else {
            // 뒤집힌 뒤의 앞면은 거울상이 되므로 한 번 더 돌려 바로 세운다
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { RoleCard(role = m.revealed, faceUp = true, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** 탈락한 사람의 얼굴: 자리 색 아바타에 붉은 X. */
@Composable
private fun EliminatedFace(player: PlayerId, isMe: Boolean, state: GameUiState) {
    val index = state.opponents.indexOfFirst { it.id == player }
    val name = if (isMe) state.me?.name.orEmpty() else state.opponents.getOrNull(index)?.name ?: player.value
    Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
        Avatar(name, if (isMe) Gold else seatTint(index), size = 62.dp, icon = if (isMe) CoupIcons.Person else null)
        Icon(CoupIcons.Cross, contentDescription = null, tint = Crimson, modifier = Modifier.size(54.dp))
    }
}

// ---- 탈락 도장 ----------------------------------------------------------------------------------

/**
 * 탈락한 자리에 찍히는 "탈락" 도장. 탈락하는 순간을 화면이 봤다면([animate]) 크게 떨어지며 찍히고, 처음부터 탈락해 있었으면 그냥 찍혀 있다.
 * 도장은 탈락이라는 **상태**를 보여 주므로 연출이 꺼져 있어도 보인다(움직임만 없다). 글자는 스크린리더에도 읽힌다.
 */
@Composable
internal fun EliminatedStamp(animate: Boolean, modifier: Modifier = Modifier, large: Boolean = false) {
    val motion = LocalMotion.current
    val landing = remember { Animatable(if (animate && motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (landing.value < 1f) landing.animateTo(1f, tween(motion.ms(STAMP_MS + 120), easing = LinearEasing))
    }
    val shape = RoundedCornerShape(if (large) 10.dp else 6.dp)
    val outer = if (large) 3.dp else 2.dp
    val landed by remember { derivedStateOf { landing.value >= 1f } }
    val landedNow = landed // 다 찍히는 순간에 한 번만 다시 구성된다
    Box(
        modifier
            .semantics { stampLanded = landedNow }
            .graphicsLayer {
                val p = landing.value
                val s = 1f + (1f - SlamEasing.transform(p)) * 1.6f
                scaleX = s
                scaleY = s
                alpha = (p * 3f).coerceAtMost(1f)
                rotationZ = -12f
            }
            .border(outer, CrimsonLight, shape)
            .background(Crimson.copy(alpha = 0.16f), shape)
            .padding(outer + 2.dp)
            .border(1.dp, CrimsonLight.copy(alpha = 0.7f), RoundedCornerShape(if (large) 7.dp else 4.dp))
            .padding(horizontal = if (large) 16.dp else 10.dp, vertical = if (large) 4.dp else 1.dp),
    ) {
        Text(
            stringResource(R.string.game_eliminated),
            color = CrimsonLight,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Black,
            fontSize = if (large) 26.sp else 15.sp,
            letterSpacing = if (large) 4.sp else 2.sp,
            maxLines = 1,
        )
    }
}

// ---- 내가 영향력을 잃을 때 ----------------------------------------------------------------------

/** 화면 가장자리가 붉게 번쩍인다. [strength]는 1에서 0으로 줄어드는 값이고, 그리는 단계에서만 읽는다. */
internal fun Modifier.damageVignette(strength: () -> Float): Modifier = drawWithContent {
    drawContent()
    val a = strength()
    if (a > 0f) {
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Crimson.copy(alpha = 0.6f * a),
                center = center, radius = size.maxDimension * 0.72f,
            ),
        )
    }
}

// ---- 결과 ---------------------------------------------------------------------------------------

private const val BURST_MS = 1_600
private const val PARTICLES = 30

/** 결과 화면의 들어오는 정도(0 -> 1, 튕기며 1을 넘었다 돌아온다). 연출이 꺼져 있으면 처음부터 1이다. */
@Composable
internal fun rememberResultEntrance(): Animatable<Float, *> {
    val motion = LocalMotion.current
    val entrance = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) {
            entrance.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = if (motion.speed == MotionSpeed.FAST) Spring.StiffnessMedium else Spring.StiffnessMediumLow))
        }
    }
    return entrance
}

/**
 * 결과 화면의 문장(紋章). 이기면 금빛 왕관이 튀어 오르고 뒤에서 빛살이 천천히 돌며 금화와 불꽃이 터진다.
 * 지면 X 문장이 위에서 떨어져 내려앉는다. 연출이 꺼져 있으면 다 끝난 모습으로 그린다.
 */
@Composable
internal fun ResultEmblem(won: Boolean, entrance: Animatable<Float, *>) {
    val motion = LocalMotion.current
    val burst = remember { Animatable(if (motion.enabled && won) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (burst.value < 1f) burst.animateTo(1f, tween(motion.ms(BURST_MS), easing = LinearEasing))
    }
    val spin: State<Float>? = if (won && motion.enabled) {
        rememberInfiniteTransition(label = "rays").animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "rays")
    } else {
        null
    }
    val particles = remember { burstParticles() }
    Box(Modifier.size(width = 240.dp, height = 124.dp), contentAlignment = Alignment.Center) {
        if (won) Canvas(Modifier.size(116.dp).graphicsLayer { rotationZ = spin?.value ?: 0f; alpha = entrance.value.coerceIn(0f, 1f) }) { drawRays() }
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    val e = entrance.value
                    if (won) {
                        scaleX = 0.3f + 0.7f * e
                        scaleY = 0.3f + 0.7f * e
                    } else {
                        translationY = (1f - e) * -28.dp.toPx()
                    }
                    alpha = e.coerceIn(0f, 1f)
                }
                .panel(CircleShape, top = if (won) GoldBright else PanelTop, bottom = if (won) Gold else PanelHigh, line = if (won) GoldBright else ParchmentDim.copy(alpha = 0.5f), lineWidth = 1.5.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(if (won) CoupIcons.Crown else CoupIcons.Cross, contentDescription = null, tint = if (won) GoldInk else ParchmentDim, modifier = Modifier.size(34.dp)) }
        // 금화와 불꽃은 문장 앞에서 터진다
        if (won) Canvas(Modifier.fillMaxSize()) { drawBurst(particles, burst.value) }
    }
}

private fun DrawScope.drawRays() {
    val r = size.minDimension / 2
    repeat(12) { i ->
        val a = (i * 30.0) * PI / 180
        val b = a + 11 * PI / 180
        val path = Path().apply {
            moveTo(center.x, center.y)
            lineTo(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
            lineTo(center.x + (r * cos(b)).toFloat(), center.y + (r * sin(b)).toFloat())
            close()
        }
        drawPath(path, Brush.radialGradient(listOf(GoldBright.copy(alpha = 0.34f), Color.Transparent), center = center, radius = r))
    }
}

/** 터지는 조각 하나: 날아가는 방향(라디안), 빠르기(0..1), 크기(dp), 금화인지 불꽃인지. 같은 결과에는 늘 같은 모양이 되게 정해진 값으로 만든다. */
private class Particle(val angle: Double, val speed: Float, val size: Float, val coin: Boolean, val spin: Float)

private fun burstParticles(): List<Particle> = List(PARTICLES) { i ->
    val r1 = ((i * 73 + 17) % 100) / 100f
    val r2 = ((i * 37 + 11) % 100) / 100f
    // 사방으로 고르게(조금씩 흐트러뜨려) 퍼진다
    val angle = (360.0 * (i + r1 * 0.7) / PARTICLES) * PI / 180
    Particle(angle, 0.5f + 0.5f * r2, if (i % 3 == 0) 6.5f else 3.5f + 3f * r1, coin = i % 3 == 0, spin = r1 * 720f)
}

private fun DrawScope.drawBurst(particles: List<Particle>, p: Float) {
    if (p <= 0f || p >= 1f) return
    val rim = 30.dp.toPx() // 문장 가장자리에서 튀어나온다
    val reachX = size.width * 0.5f
    val reachY = size.height * 0.55f // 위아래로는 덜 퍼진다(결과 화면 안에 머물게)
    val fly = 1f - (1f - p) * (1f - p) * (1f - p) // 처음에 빠르고 점점 느리게
    val fall = p * p * size.height * 0.55f // 떨어지는 무게
    val alpha = (1f - p * p).coerceIn(0f, 1f)
    particles.forEach { q ->
        val cx = cos(q.angle).toFloat()
        val cy = sin(q.angle).toFloat()
        val x = center.x + cx * (rim + (reachX - rim) * q.speed * fly)
        val y = center.y + cy * (rim + (reachY - rim) * q.speed * fly) + fall
        val s = q.size.dp.toPx()
        if (q.coin) {
            drawCircle(Gold.copy(alpha = alpha), s, Offset(x, y))
            drawCircle(GoldDeep.copy(alpha = alpha), s * 0.62f, Offset(x, y), style = Stroke(s * 0.22f))
        } else {
            val turn = (q.spin * p) * PI.toFloat() / 180f
            val dx = cos(turn) * s
            val dy = sin(turn) * s
            val path = Path().apply {
                moveTo(x + dx, y + dy)
                lineTo(x - dy * 0.4f, y + dx * 0.4f)
                lineTo(x - dx, y - dy)
                lineTo(x + dy * 0.4f, y - dx * 0.4f)
                close()
            }
            drawPath(path, lerp(GoldBright, Color.White, 0.3f).copy(alpha = alpha))
        }
    }
}

/** 결과 화면 제목이 문장과 함께 살짝 커지며 나타난다. */
internal fun Modifier.entranceScale(entrance: Animatable<Float, *>): Modifier = graphicsLayer {
    val e = entrance.value
    val s = 0.85f + 0.15f * e
    scaleX = s
    scaleY = s
    alpha = e.coerceIn(0f, 1f)
}
