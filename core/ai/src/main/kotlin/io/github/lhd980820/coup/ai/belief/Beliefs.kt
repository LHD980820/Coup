package io.github.lhd980820.coup.ai.belief

import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.view.PlayerView

/**
 * 상대가 특정 역할을 가지고 있을 확률(설계 §7.3).
 * 사전확률: 미확인 풀에서 상대의 미공개 장수만큼 뽑았을 때 그 역할이 하나 이상 있을 확률(초기하).
 * 주장 보정: 그 역할을 주장한 상태면 우도비 1/블러핑비율로 갱신(베이즈).
 */
internal class Beliefs(
    private val view: PlayerView,
    private val history: ClaimHistory,
    private val countCards: Boolean,
    private val trackClaims: Boolean,
) {
    private val pool: Map<RoleId, Int> = if (countCards) CardCounter.unseen(view) else CardCounter.composition(view)
    private val poolSize = pool.values.sum()

    /** 미확인 풀에 이 역할이 한 장도 없다 = 누구도 가지고 있을 수 없다(카드 카운팅 시). */
    fun impossible(roles: Set<RoleId>): Boolean = countCards && roles.sumOf { pool[it] ?: 0 } == 0

    /**
     * @param ignoreClaims 주장이 정보가 없는 상황(어차피 그렇게 주장할 수밖에 없는 상황)이면 true — 사전확률만 쓴다.
     */
    fun pHolds(player: PlayerId, roles: Set<RoleId>, ignoreClaims: Boolean = false): Double {
        if (roles.isEmpty()) return 0.0
        val hidden = view.opponent(player)?.hiddenCount ?: return 0.0
        if (hidden == 0 || impossible(roles)) return 0.0
        val hits = roles.sumOf { pool[it] ?: 0 }
        val prior = CardCounter.atLeastOne(poolSize, hits, hidden).coerceIn(0.01, 0.99)
        if (ignoreClaims || !trackClaims || roles.none { it in history.claimed(player) }) return prior
        val likelihood = 1.0 / history.bluffRate(player, prior = BLUFF_PRIOR)
        val odds = prior / (1 - prior) * likelihood
        return odds / (1 + odds)
    }

    /** 공개적으로 이미 드러난 이 역할의 비율(상대들이 내 주장을 의심할 근거). 내 손패는 상대가 모르므로 제외. */
    fun publiclyExhausted(roles: Set<RoleId>): Double {
        val copies = view.ruleSet.roles.filter { it.id in roles }.sumOf { it.copies }
        if (copies == 0) return 1.0
        val revealed = view.opponents.sumOf { op -> op.revealed.count { it in roles } } +
            (view.me?.hand?.count { it.revealed && it.card.role in roles } ?: 0)
        return revealed.toDouble() / copies
    }

    companion object {
        const val BLUFF_PRIOR = 0.35
    }
}
