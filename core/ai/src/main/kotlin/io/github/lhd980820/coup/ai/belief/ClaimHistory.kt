package io.github.lhd980820.coup.ai.belief

import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.view.VisibleEvent

/**
 * 공개 이벤트에서 추출한 주장 기록(설계 §7.3). 상대가 "지금도 가지고 있다고 주장하는" 역할과 블러핑 이력.
 * 증명 후 카드 교체, 교환 완료는 지식을 리셋한다(증명한 카드는 덱으로 돌아갔다).
 */
internal class ClaimHistory {
    private val claims = mutableMapOf<PlayerId, MutableSet<RoleId>>()
    private val pendingChallenge = mutableMapOf<PlayerId, Set<RoleId>>()
    private val claimsMade = mutableMapOf<PlayerId, Int>()
    private val exposed = mutableMapOf<PlayerId, Int>()

    fun observe(events: List<VisibleEvent>) = events.forEach { observe(it.event) }

    fun observe(event: GameEvent) {
        when (event) {
            is GameEvent.ActionDeclared -> if (event.claimedRoles.isNotEmpty()) claim(event.actor, event.claimedRoles)
            is GameEvent.BlockDeclared -> claim(event.blocker, setOf(event.role))
            is GameEvent.ChallengeIssued -> pendingChallenge[event.challenged] = event.claimedRoles
            is GameEvent.CardRevealed -> {
                val challenged = pendingChallenge.remove(event.player).orEmpty()
                if (event.proven) {
                    claims[event.player]?.remove(event.card.role) // 덱으로 돌아갔다
                } else {
                    exposed.merge(event.player, 1, Int::plus)
                    claims[event.player]?.removeAll(challenged)
                }
            }
            is GameEvent.InfluenceLost -> claims[event.player]?.remove(event.card.role)
            is GameEvent.ExchangeCompleted -> claims.remove(event.player)
            is GameEvent.PlayerEliminated -> claims.remove(event.player)
            else -> Unit
        }
    }

    private fun claim(player: PlayerId, roles: Set<RoleId>) {
        claims.getOrPut(player) { mutableSetOf() } += roles
        claimsMade.merge(player, 1, Int::plus)
    }

    /** [player]가 현재 가지고 있다고 주장한 역할들. */
    fun claimed(player: PlayerId): Set<RoleId> = claims[player].orEmpty()

    /** 관찰된 블러핑 비율(사전값 쪽으로 평활화). */
    fun bluffRate(player: PlayerId, prior: Double): Double {
        val made = claimsMade[player] ?: 0
        val caught = exposed[player] ?: 0
        return ((caught + prior * PRIOR_WEIGHT) / (made + PRIOR_WEIGHT)).coerceIn(0.1, 0.8)
    }

    private companion object {
        const val PRIOR_WEIGHT = 3.0
    }
}
