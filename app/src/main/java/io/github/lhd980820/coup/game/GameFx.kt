package io.github.lhd980820.coup.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.ui.kit.CoinPile
import io.github.lhd980820.coup.ui.kit.PlayingCardBack
import io.github.lhd980820.coup.ui.kit.PlayingCardFace
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.PanelTop
import kotlinx.coroutines.delay

// ---- 카드 ---------------------------------------------------------------------------------------

/** 역할 카드 한 장. [role]을 모르거나 [faceUp]이 아니면 뒷면이다. 크기는 [modifier]로 정한다. */
@Composable
internal fun RoleCard(role: RoleId?, faceUp: Boolean, modifier: Modifier = Modifier, lost: Boolean = false, selected: Boolean = false) {
    if (role != null && faceUp) {
        PlayingCardFace(RoleVisuals.of(role.value), stringResource(RoleUiCatalog.of(role).name), modifier, lost = lost, selected = selected)
    } else {
        PlayingCardBack(modifier)
    }
}

// ---- 연출 ---------------------------------------------------------------------------------------
// 모든 연출은 [LocalMotion]이 꺼져 있으면 즉시 끝난다(설정 "끄기" 또는 기기의 애니메이션 없음). 상태는 연출과 상관없이 바로 바뀌어 있다.

/** 카드가 뒤집히는 진행(0 = 뒷면, 1 = 앞면). 자동 테스트가 연출 도중을 확인하는 데 쓴다. */
internal val FlipProgress = SemanticsPropertyKey<Float>("FlipProgress")
private var SemanticsPropertyReceiver.flipProgress by FlipProgress

/** 값이 바뀔 때 부드럽게 따라가는 값(투명도·크기 등). 연출이 꺼져 있으면 바로 바뀐다. */
@Composable
internal fun fadeTo(target: Float, millis: Int = FADE_MS): Float {
    val motion = LocalMotion.current
    val value by animateFloatAsState(target, if (motion.enabled) tween(motion.ms(millis)) else snap(), label = "fade")
    return value
}

/** 주의를 끄는 느린 맥박(0..1 사이를 오간다). 연출이 꺼져 있으면 [resting]에 머문다. */
@Composable
internal fun pulse(resting: Float = 1f): State<Float> {
    val motion = LocalMotion.current
    if (!motion.enabled) return remember(resting) { androidx.compose.runtime.mutableFloatStateOf(resting) }
    return rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(motion.ms(PULSE_MS)), RepeatMode.Reverse),
        label = "pulse",
    )
}

/** 잃어서 공개된 카드. [animate]면 뒷면에서 앞면으로 뒤집히며 나타난다. */
@Composable
internal fun FlipCard(role: RoleId, animate: Boolean, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val rotation = remember { Animatable(if (animate && motion.enabled) 0f else 180f) }
    LaunchedEffect(Unit) {
        if (rotation.value < 180f) rotation.animateTo(180f, tween(motion.ms(FLIP_MS)))
    }
    val name = stringResource(RoleUiCatalog.of(role).name)
    Box(
        modifier
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = FLIP_CAMERA * density
            }
            .semantics { flipProgress = rotation.value / 180f; contentDescription = name },
    ) {
        if (rotation.value < 90f) {
            RoleCard(role = null, faceUp = false, modifier = Modifier.fillMaxSize())
        } else {
            // 뒤집힌 뒤의 앞면은 거울상이 되므로 한 번 더 돌려 바로 세운다
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { RoleCard(role = role, faceUp = true, modifier = Modifier.fillMaxSize(), lost = true) }
        }
    }
}

/**
 * 코인: 코인 그림 + 숫자. 바뀌면 숫자가 올라가거나(늘 때) 내려가며(줄 때) 바뀐다. 스크린리더에는 항상 "코인 N" 하나만 읽힌다.
 * [large]는 내 코인(크게).
 */
@Composable
internal fun CoinCount(coins: Int, tag: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val motion = LocalMotion.current
    val label = stringResource(R.string.game_coins, coins)
    val style = if (large) MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.25f) else MaterialTheme.typography.titleSmall
    Row(
        modifier.testTag(tag).clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp),
    ) {
        CoinPile(coins, size = if (large) 22.dp else 15.dp)
        if (!motion.enabled) {
            Text(coins.toString(), style = style, fontWeight = FontWeight.Bold)
        } else {
            AnimatedContent(
                targetState = coins,
                transitionSpec = {
                    val up = targetState > initialState
                    val ms = motion.ms(COIN_MS)
                    (slideInVertically(tween(ms)) { if (up) it else -it } + fadeIn(tween(ms))) togetherWith
                        (slideOutVertically(tween(ms)) { if (up) -it else it } + fadeOut(tween(ms)))
                },
                label = "coins",
            ) { c -> Text(c.toString(), style = style, fontWeight = FontWeight.Bold) }
        }
    }
}

/** 응답 시트가 아래에서 올라오며 나타난다. 연출이 꺼져 있으면 바로 나타난다. */
@Composable
internal fun SheetEntrance(content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    if (!motion.enabled) {
        content()
        return
    }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInVertically(tween(motion.ms(SHEET_MS))) { it } + fadeIn(tween(motion.ms(SHEET_MS))),
        exit = ExitTransition.None,
    ) { content() }
}

private const val FADE_MS = 400
private const val FLIP_MS = 500
private const val COIN_MS = 300
private const val SHEET_MS = 300
private const val PULSE_MS = 900
private const val FLIP_CAMERA = 12f

// ---- 남은 시간 ----------------------------------------------------------------------------------

internal enum class TimerStyle {
    /** 줄어드는 링 + 초(상대 자리, 확대 창). */
    RING,

    /** 가로로 줄어드는 막대 + 초(내 결정: 한눈에 보이게 크게). */
    BAR,
}

/**
 * 마감까지 남은 시간: 줄어드는 링(또는 막대) + 초. 전체 길이는 이 마감을 처음 본 순간의 남은 시간이다.
 * 5초 이하면 경고색이 되고 글씨가 굵어진다(숫자는 항상 글자로도 보인다). 스크린리더에는 "남은 시간 N초"로 읽히되 매초 알리지는 않는다.
 */
@Composable
internal fun Countdown(deadline: Long, tag: String, modifier: Modifier = Modifier, style: TimerStyle = TimerStyle.RING) {
    val now = LocalNowMillis.current
    val remaining by produceState(initialValue = millisLeft(deadline, now), deadline, now) {
        while (true) {
            value = millisLeft(deadline, now)
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }
    val total = remember(deadline) { remaining.coerceAtLeast(1) }
    val seconds = ((remaining + 999) / 1000).toInt()
    val urgent = seconds <= URGENT_SECONDS
    val label = stringResource(R.string.game_timer_label, seconds)
    val fraction = (remaining.toFloat() / total).coerceIn(0f, 1f)
    val color = if (urgent) Crimson else Gold
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (style == TimerStyle.BAR) 8.dp else 4.dp),
    ) {
        when (style) {
            TimerStyle.RING -> CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(16.dp),
                strokeWidth = 3.dp,
                color = color,
                trackColor = PanelTop,
            )
            TimerStyle.BAR -> Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(PanelTop)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(color))
            }
        }
        Text(
            stringResource(R.string.game_timer_seconds, seconds),
            style = if (style == TimerStyle.BAR) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = if (urgent) FontWeight.Black else FontWeight.SemiBold,
            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun millisLeft(deadline: Long, now: () -> Long) = (deadline - now()).coerceAtLeast(0)

internal val MIN_TOUCH = 48.dp
private const val COUNTDOWN_TICK_MILLIS = 250L
private const val URGENT_SECONDS = 5
