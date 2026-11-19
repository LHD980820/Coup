package io.github.lhd980820.coup.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.OpponentStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 판이 극적인 순간을 기다렸다가 따라가게 한다(docs/DESIGN.md "극적인 순간 - 판이 기다린다").
 *
 * 남은 카드가 1장인 사람에게 도전하면 도전·공개·탈락이 한꺼번에 일어나 상태가 단번에 바뀐다. 그대로 그리면 띠가 "도전!"을 외치는 동안
 * 자리에는 벌써 탈락 도장이 찍혀 결과를 미리 말해 버린다. 그래서 **보이는 판**(자리의 카드·생사, 내 손패, 진행 문구, 기록)만
 * 순간이 다 보일 때까지 붙잡아 둔다(차례 표시도 이야기가 끝난 뒤에 넘어간다). 결정·남은 시간·코인·버튼·결과처럼 사람이 반응해야 하는 것은 언제나 지금 상태를 따른다.
 */
internal object BoardPacing {
    /** 상태가 바뀐 뒤, 그 변화를 설명할 연출 신호가 따라올 때까지 기다리는 시간. 상태가 신호보다 먼저 오기 때문이다(세션이 상태를 먼저 알린다). */
    const val GRACE_MS = 120L

    /**
     * 보이는 판 [shown]을 [ripe](신호를 기다리는 시간이 지난 상태)로 옮긴다. [held]인 사람의 카드·생사와,
     * 순간이 무대에 있는 동안([busy])의 진행 문구·기록은 그대로 둔다.
     */
    fun pace(shown: GameUiState, ripe: GameUiState, held: Set<PlayerId>, busy: Boolean): GameUiState {
        val before = shown.opponents.associateBy { it.id }
        return ripe.copy(
            opponents = ripe.opponents.map { o ->
                val old = before[o.id]
                if (o.id in held && old != null) o.copy(hiddenCount = old.hiddenCount, revealed = old.revealed, isAlive = old.isAlive) else o
            },
            me = ripe.me?.let { m ->
                val old = shown.me
                if (m.id in held && old != null && old.id == m.id) m.copy(hand = old.hand, isAlive = old.isAlive) else m
            },
            banner = if (busy) shown.banner else ripe.banner,
            log = if (busy) shown.log else ripe.log,
            // 차례도 이야기가 끝난 뒤에 넘어간다(띠가 지난 턴의 일을 말하는 동안 다음 사람이 차례로 보이면 헷갈린다)
            turnPlayer = if (busy) shown.turnPlayer else ripe.turnPlayer,
        )
    }

    /** 보이는 판 [board]에 지금 상태 [live]의 살아 움직이는 부분(결정·시간·코인·상태·버튼·결과)을 얹는다. 차례 표시는 보이는 판을 따른다. */
    fun withLive(board: GameUiState, live: GameUiState): GameUiState {
        val shown = board.opponents.associateBy { it.id }
        return live.copy(
            opponents = live.opponents.map { o ->
                val b = shown[o.id] ?: return@map o
                o.copy(
                    hiddenCount = b.hiddenCount,
                    revealed = b.revealed,
                    isAlive = b.isAlive,
                    // 아직 살아 있는 것으로 보이는 동안에는 탈락 상태를 내지 않는다
                    status = if (b.isAlive && o.status == OpponentStatus.ELIMINATED) OpponentStatus.IDLE else o.status,
                )
            },
            me = live.me?.let { m -> board.me?.takeIf { it.id == m.id }?.let { b -> m.copy(hand = b.hand, isAlive = b.isAlive) } ?: m },
            banner = board.banner,
            log = board.log,
            turnPlayer = board.turnPlayer,
        )
    }
}

/**
 * 지금 그릴 판. 연출이 꺼져 있으면([enabled]가 false) 지금 상태 그대로다. [released]면(결과 화면이 떴으면) 붙잡지 않는다.
 */
@Composable
internal fun rememberPacedBoard(live: GameUiState, stage: MomentStage, enabled: Boolean, released: Boolean): GameUiState {
    val current by rememberUpdatedState(live)
    var ripe by remember { mutableStateOf(live) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        // 바뀐 상태 하나하나가 같은 시간 뒤에 차례로 익는다(계속 바뀌어도 밀리지 않는다)
        snapshotFlow { current }.collect { v ->
            launch {
                delay(BoardPacing.GRACE_MS)
                ripe = v
            }
        }
    }
    val shown = remember { arrayOf(live) }
    if (!enabled) {
        shown[0] = live
        return live
    }
    val holding = !released
    val next = BoardPacing.pace(shown[0], ripe, if (holding) stage.held else emptySet(), busy = holding && !stage.idle)
    shown[0] = next
    return BoardPacing.withLive(next, live)
}
