package io.github.lhd980820.coup.game.sound

import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState

/**
 * 게임 상태가 바뀔 때 어떤 소리를 낼지 정한다. 상태의 **변화**만 보므로(이전 상태가 없으면 무음),
 * 화면을 다시 그리거나 회전해도 같은 소리가 반복되지 않는다. 한 번에 낼 소리는 중요한 것부터 [MAX_AT_ONCE]개까지다.
 */
internal object SoundCues {
    const val MAX_AT_ONCE = 2

    fun between(previous: GameUiState?, next: GameUiState): List<SoundCue> {
        if (previous == null) return emptyList()
        val cues = buildList {
            if (previous.result == null && next.result != null) add(if (next.result!!.iWon) SoundCue.WIN else SoundCue.LOSE)
            if (alive(next) < alive(previous)) add(SoundCue.ELIMINATED)
            if (next.banner is Banner.Challenged && previous.banner !is Banner.Challenged) add(SoundCue.CHALLENGE)
            if (revealed(next) > revealed(previous)) add(SoundCue.CARD_REVEAL)
            if (next.decision is DecisionUi.Respond && previous.decision !is DecisionUi.Respond) add(SoundCue.RESPOND)
            if (next.decision is DecisionUi.Actions && previous.decision !is DecisionUi.Actions) add(SoundCue.YOUR_TURN)
            coinCue(previous, next)?.let { add(it) }
        }
        return cues.take(MAX_AT_ONCE)
    }

    private fun alive(s: GameUiState): Int = (if (s.me?.isAlive == true) 1 else 0) + s.opponents.count { it.isAlive }

    private fun revealed(s: GameUiState): Int = s.opponents.sumOf { it.revealed.size } + (s.me?.hand?.count { it.revealed } ?: 0)

    /** 누구든 코인이 늘었으면 얻는 소리, 아니면 누구든 줄었으면 잃는 소리(뺏는 행동은 얻는 소리 하나로). */
    private fun coinCue(previous: GameUiState, next: GameUiState): SoundCue? {
        val before = coins(previous)
        val after = coins(next)
        val changes = after.mapNotNull { (id, now) -> before[id]?.let { now - it } }
        return when {
            changes.any { it > 0 } -> SoundCue.COIN_GAIN
            changes.any { it < 0 } -> SoundCue.COIN_LOSS
            else -> null
        }
    }

    private fun coins(s: GameUiState): Map<String, Int> =
        s.opponents.associate { it.id.value to it.coins } + (s.me?.let { mapOf(it.id.value to it.coins) } ?: emptyMap())
}
