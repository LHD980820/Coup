package io.github.lhd980820.coup.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.game.sound.SoundCue
import io.github.lhd980820.coup.presentation.UiEffect
import io.github.lhd980820.coup.ui.kit.drawCoin
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.GoldBright
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// 코인이 테이블 위를 날아간다(docs/DESIGN.md "코인이 날아간다"): 은행(가운데 판)과 자리 사이, 갈취는 자리에서 자리로.

/** 코인이 오가는 끝: 테이블 가운데의 은행, 또는 사람의 코인 더미. */
internal sealed interface CoinEnd {
    data object Bank : CoinEnd

    data class Purse(val player: PlayerId) : CoinEnd
}

private val CoinEnd.key: String get() = when (this) {
    CoinEnd.Bank -> "bank"
    is CoinEnd.Purse -> player.value
}

/** 한 번의 코인 이동: [from]에서 [to]로 [amount]닢. */
internal data class CoinFlight(val from: CoinEnd, val to: CoinEnd, val amount: Int) {
    /** 자동 테스트가 무엇이 날았는지 확인하는 이름("bank>me:3"). */
    val label: String get() = "${from.key}>${to.key}:$amount"
}

/**
 * 연출 신호의 코인 변화([UiEffect.CoinsChanged])를 코인 이동으로 바꾼다. 늘면 은행에서 오고, 줄면 은행으로 간다.
 * 갈취처럼 한 사람이 잃고 **바로 다음 신호로** 다른 사람이 같은 만큼 얻으면 자리에서 자리로 가는 한 번의 이동으로 묶는다 —
 * 그래서 잃음은 다음 신호를 볼 때까지 들고 있다([holding]). 다음 신호가 오지 않으면 [flush]로 은행에 보낸다.
 */
internal class CoinDirector {
    // 화면이 이 값을 읽어 코인 숫자를 정하므로 상태로 둔다(잃음을 들고 있는 동안에도 숫자는 잃기 전 값이다)
    private var held by mutableStateOf<UiEffect.CoinsChanged?>(null)

    val holding: Boolean get() = held != null

    /** 들고 있는 잃음만큼 [player]의 코인 숫자에 더할 값. */
    fun offset(player: PlayerId): Int = held?.takeIf { it.player == player }?.let { -it.delta } ?: 0

    /** 신호 하나를 받아 지금 정해진 이동들을 돌려준다. */
    fun on(effect: UiEffect): List<CoinFlight> {
        val loss = held
        if (effect !is UiEffect.CoinsChanged || effect.delta == 0) return flush()
        if (loss != null && effect.delta > 0 && effect.delta == -loss.delta && effect.player != loss.player) {
            held = null
            return listOf(CoinFlight(CoinEnd.Purse(loss.player), CoinEnd.Purse(effect.player), effect.delta))
        }
        val before = flush()
        if (effect.delta < 0) {
            held = effect
            return before
        }
        return before + CoinFlight(CoinEnd.Bank, CoinEnd.Purse(effect.player), effect.delta)
    }

    /** 들고 있던 잃음을 은행으로 보낸다(짝이 될 얻음이 오지 않았다). */
    fun flush(): List<CoinFlight> {
        val loss = held ?: return emptyList()
        held = null
        return listOf(CoinFlight(CoinEnd.Purse(loss.player), CoinEnd.Bank, -loss.delta))
    }

    companion object {
        /** 잃음 뒤에 짝이 될 얻음을 기다리는 시간. 한 번에 일어난 일의 신호는 거의 동시에 온다. */
        const val PAIR_WAIT_MS = 60L
    }
}

/** 한 번의 이동을 몇 개의 코인 그림으로 날리는가: 닢마다 하나, 많으면 [MAX_SPRITES]개에 나눠 싣는다(앞의 것이 더 싣는다). */
internal fun spritesOf(amount: Int): List<Int> {
    if (amount <= 0) return emptyList()
    val n = minOf(amount, MAX_SPRITES)
    return List(n) { i -> amount / n + if (i < amount % n) 1 else 0 }
}

internal const val MAX_SPRITES = 5

/** 코인 하나가 날아가는 시간, 다음 코인이 뒤따라 떠나는 간격, 닿은 뒤의 반짝임, +N 표시가 떠 있는 시간(보통 속도). */
internal const val FLY_MS = 560
internal const val STAGGER_MS = 110
private const val POP_MS = 180
private const val LABEL_MS = 760

/**
 * 날고 있는 이동 하나. 시계([clock], 0..1)는 [CoinStage]가 돌리고 화면은 그리는 단계에서만 읽는다.
 * 코인 그림 i는 [departs]에 떠나 [lands]에 닿는다. [departed]/[arrived]는 떠난·닿은 닢 수(정수라 바뀔 때만 다시 그린다).
 */
@Stable
internal class CoinRun(val id: Int, val flight: CoinFlight, fly: Int, stagger: Int) {
    val sprites: List<Int> = spritesOf(flight.amount)
    private val flyMs = fly.coerceAtLeast(1)
    private val staggerMs = stagger.coerceAtLeast(0)
    private val lastLand = staggerMs * (sprites.size - 1) + flyMs
    val totalMs: Int = maxOf(lastLand + POP_MS, flyMs + LABEL_MS)
    val clock = Animatable(0f)

    fun departs(i: Int): Float = (i * staggerMs).toFloat() / totalMs
    fun lands(i: Int): Float = (i * staggerMs + flyMs).toFloat() / totalMs

    /** 그림 i의 날아간 정도(0..1). 아직 떠나지 않았으면 음수, 닿았으면 1 이상. */
    fun progress(i: Int, t: Float): Float = (t - departs(i)) / (lands(i) - departs(i))

    val departed: Int by derivedStateOf { sprites.indices.filter { clock.value >= departs(it) }.sumOf { sprites[it] } }
    val arrived: Int by derivedStateOf { sprites.indices.filter { clock.value >= lands(it) }.sumOf { sprites[it] } }

    /** 이 이동이 [player]의 코인 숫자에 더하는 값: 아직 떠나지 않은 코인은 보낸 사람에게, 아직 닿지 않은 코인은 받는 사람에게서 빼고 보인다. */
    fun offsetFor(player: PlayerId): Int {
        var d = 0
        if (flight.from == CoinEnd.Purse(player)) d += flight.amount - departed
        if (flight.to == CoinEnd.Purse(player)) d -= flight.amount - arrived
        return d
    }

    /** 첫 코인이 닿는 때(ms). */
    val firstLandMs: Int get() = flyMs
}

/**
 * 코인 이동을 무대에 올린다. 극적인 순간이 그 결과를 말하는 동안에는 코인을 날리지 않고 기다린다([play]의 busy) —
 * 먼저 날면 결과(갈취가 통했다 등)를 미리 말해 버린다. 기다리는 동안과 나는 동안에는 코인 숫자를 날기 전 값으로 보인다([offset]).
 */
@Stable
internal class CoinStage {
    private val director = CoinDirector()
    private var seq = 0
    private var scope: CoroutineScope? = null
    private var fly = FLY_MS
    private var stagger = STAGGER_MS
    private var sound: (SoundCue) -> Unit = {}
    private var busy: () -> Boolean = { false }

    /** 극적인 순간이 끝나기를 기다리는 이동들. */
    val waiting = mutableStateListOf<CoinFlight>()

    /** 날고 있는 이동들. */
    val runs = mutableStateListOf<CoinRun>()

    fun offer(flight: CoinFlight) {
        waiting += flight
        if (!busy()) release()
    }

    /** 연출 신호를 받는다: 코인 변화는 이동으로 바꿔 올린다(갈취는 짝을 맞춘다). 잃음의 짝을 기다리는 중이면 [pairing]이다. */
    fun take(effect: UiEffect) = director.on(effect).forEach(::offer)

    val pairing: Boolean get() = director.holding

    /** 짝이 오지 않은 잃음을 은행으로 보낸다. */
    fun endPairing() = director.flush().forEach(::offer)

    /** [player]의 코인 숫자를 보일 때 더할 값(기다리는·나는 코인만큼 날기 전 값으로 되돌린다). */
    fun offset(player: PlayerId): Int {
        var d = director.offset(player)
        waiting.forEach { f ->
            if (f.from == CoinEnd.Purse(player)) d += f.amount
            if (f.to == CoinEnd.Purse(player)) d -= f.amount
        }
        runs.forEach { d += it.offsetFor(player) }
        return d
    }

    /** 판이 끝나 결과가 떴다: 남은 코인은 날리지 않는다(숫자는 바로 지금 값). */
    fun clear() {
        director.flush()
        waiting.clear()
        runs.clear()
    }

    /** 이동을 날린다. [busy]가 풀릴 때마다 기다리던 것을 내보낸다. 코인이 처음 닿을 때(은행으로 갈 때는 떠날 때) [sound]를 낸다. */
    suspend fun play(motion: Motion, busy: () -> Boolean, sound: (SoundCue) -> Unit) {
        coroutineScope {
            scope = this
            fly = motion.ms(FLY_MS)
            stagger = motion.ms(STAGGER_MS)
            this@CoinStage.sound = sound
            this@CoinStage.busy = busy
            try {
                launch { snapshotFlow { busy() }.collect { if (!it) release() } }
                awaitCancellation()
            } finally {
                scope = null
                this@CoinStage.busy = { false }
            }
        }
    }

    private fun release() {
        val s = scope ?: return
        if (waiting.isEmpty()) return
        val ready = waiting.toList()
        waiting.clear()
        ready.forEach { start(s, it) }
    }

    private fun start(s: CoroutineScope, flight: CoinFlight) {
        val run = CoinRun(++seq, flight, fly, stagger)
        runs += run
        s.launch {
            if (flight.to == CoinEnd.Bank) sound(SoundCue.COIN_LOSS)
            launch {
                if (flight.to != CoinEnd.Bank) {
                    kotlinx.coroutines.delay(run.firstLandMs.toLong())
                    sound(SoundCue.COIN_GAIN)
                }
            }
            try {
                run.clock.animateTo(1f, tween(run.totalMs, easing = LinearEasing))
            } finally {
                runs.remove(run)
            }
        }
    }
}

/** 코인 숫자에 더할 값(날기 전 값으로 보이게). 극적인 순간이 없는 화면(미리보기·테스트)에서는 0이다. */
internal val LocalCoinOffsets = staticCompositionLocalOf<(PlayerId) -> Int> { { 0 } }

/** 코인이 지금 날고 있는 이동들(자동 테스트용). */
internal val CoinsFlying = SemanticsPropertyKey<List<String>>("CoinsFlying")
private var SemanticsPropertyReceiver.coinsFlying by CoinsFlying

/**
 * 날고 있는 코인을 그린다: 출발점에서 도착점까지 살짝 휘어 날아가고(가운데쯤 커졌다 작아진다), 닿으면 반짝 고리가 퍼진다.
 * 받는 사람 옆에는 "+3", 내는 사람 옆에는 "−7"이 떠올랐다 사라진다. 끝점은 코인 더미([TableAnchors.purse])이고, 은행은 테이블 한가운데(화면 가운데)에
 * 코인이 오가는 동안만 금화 더미로 나타난다.
 * 그림일 뿐이라 누르는 것을 가로채지 않는다.
 */
@Composable
internal fun CoinOverlay(stage: CoinStage, anchors: TableAnchors, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val runs = stage.runs.toList()
    val labels = runs.map { it.flight.label }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .onSizeChanged { size = it }
            .clearAndSetSemantics { testTag = GameTags.COINS; coinsFlying = labels },
    ) {
        if (runs.isEmpty()) return@Box
        val center = Offset(size.width / 2f, size.height / 2f)
        fun point(end: CoinEnd): Offset = when (end) {
            CoinEnd.Bank -> center
            is CoinEnd.Purse -> anchors.purse(end.player)?.center?.minus(origin) ?: center
        }
        Canvas(Modifier.fillMaxSize()) {
            val radius = 9.dp.toPx()
            // 은행: 코인이 오가는 동안 테이블 한가운데에 금화 더미가 은은하게 빛난다
            val bank = runs.filter { it.flight.from == CoinEnd.Bank || it.flight.to == CoinEnd.Bank }
                .maxOfOrNull { r -> val t = r.clock.value; minOf(t / 0.12f, (1f - t) / 0.2f, 1f).coerceIn(0f, 1f) } ?: 0f
            if (bank > 0f) {
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(GoldBright.copy(alpha = 0.28f * bank), Color.Transparent), center = center, radius = radius * 4.5f), radius * 4.5f, center)
                // 나타날 때 부풀고 사라질 때 오그라든다
                val r = radius * bank
                listOf(Offset(-r * 0.9f, r * 0.35f), Offset(r * 0.9f, r * 0.35f), Offset(0f, -r * 0.45f)).forEach { o ->
                    drawCircle(Color.Black.copy(alpha = 0.25f * bank), r, center + o + Offset(0f, r * 0.4f))
                    drawCoin(center + o, r)
                }
            }
            runs.forEach { run ->
                val from = point(run.flight.from)
                val to = point(run.flight.to)
                val t = run.clock.value
                run.sprites.indices.forEach { i ->
                    val p = run.progress(i, t)
                    when {
                        p < 0f -> Unit
                        p < 1f -> {
                            val at = coinPath(from, to, i, run.sprites.size, FastOutSlowInEasing.transform(p), 7.dp.toPx())
                            val lift = 1f + 0.35f * sin(PI.toFloat() * p)
                            drawCircle(Color.Black.copy(alpha = 0.25f), radius * lift, at + Offset(0f, radius * 0.5f))
                            drawCoin(at, radius * lift)
                        }
                        else -> {
                            // 닿은 뒤: 금빛 고리가 퍼지며 사라진다
                            val pop = ((t - run.lands(i)) * run.totalMs / POP_MS).coerceIn(0f, 1f)
                            if (pop < 1f) drawCircle(GoldBright.copy(alpha = 0.7f * (1f - pop)), radius * (1f + pop * 1.6f), to, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        runs.forEach { run ->
            val amount = run.flight.amount
            if (run.flight.from is CoinEnd.Purse) FloatingAmount("−$amount", CrimsonLight, point(run.flight.from), run, startAt = 0f)
            if (run.flight.to is CoinEnd.Purse) FloatingAmount("+$amount", GoldBright, point(run.flight.to), run, startAt = run.lands(0))
        }
    }
}

/** 출발점과 도착점 사이의 휜 길에서 [t](0..1)의 위치. 그림마다 조금씩 다른 길로 날아 겹치지 않는다. */
internal fun coinPath(from: Offset, to: Offset, index: Int, count: Int, t: Float, spread: Float): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / length
    val ny = dx / length
    val bend = length * 0.18f + (index - (count - 1) / 2f) * spread
    val c = Offset((from.x + to.x) / 2 + nx * bend, (from.y + to.y) / 2 + ny * bend)
    val u = 1 - t
    return Offset(u * u * from.x + 2 * u * t * c.x + t * t * to.x, u * u * from.y + 2 * u * t * c.y + t * t * to.y)
}

/** 코인 더미 옆에 떠올랐다 사라지는 +N/−N. [startAt]부터 보인다. */
@Composable
private fun FloatingAmount(text: String, color: Color, at: Offset, run: CoinRun, startAt: Float) {
    Box(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .wrapContentSize(unbounded = true)
            .graphicsLayer {
                val shown = ((run.clock.value - startAt) * run.totalMs / LABEL_MS).coerceIn(0f, 1f)
                alpha = if (run.clock.value < startAt) 0f else (1f - shown) * 1.4f
                translationX = -size.width / 2f
                translationY = -size.height - 6.dp.toPx() - 18.dp.toPx() * shown
            },
    ) {
        Text(text, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}
