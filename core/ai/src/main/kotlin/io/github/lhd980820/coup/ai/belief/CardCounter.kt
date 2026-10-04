package io.github.lhd980820.coup.ai.belief

import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView

/**
 * 확정 정보만으로 계산하는 카드 카운팅(설계 §7.3).
 * 미확인 풀 = 덱 구성 − 내 손패(공개 포함) − 상대의 공개 카드 − 내가 교환 중에 본 덱 카드.
 * 미확인 풀의 카드는 "상대들의 미공개 카드 + 덱 나머지"에 흩어져 있다.
 */
internal object CardCounter {

    fun unseen(view: PlayerView): Map<RoleId, Int> {
        val counts = view.ruleSet.roles.associate { it.id to it.copies }.toMutableMap()
        fun see(role: RoleId) {
            counts[role] = (counts[role] ?: 0) - 1
        }
        view.me?.hand?.forEach { see(it.card.role) }
        view.opponents.forEach { op -> op.revealed.forEach(::see) }
        val exchange = view.myDecision as? DecisionRequest.ChooseExchange
        if (exchange != null) {
            val handIds = view.me?.hand?.map { it.card.id }.orEmpty().toSet()
            exchange.candidates.filter { it.id !in handIds }.forEach { see(it.role) }
        }
        return counts.mapValues { (_, n) -> n.coerceAtLeast(0) }
    }

    /** 덱 구성상 역할별 장수(카드 카운팅을 하지 않는 난이도의 대충 추정용). */
    fun composition(view: PlayerView): Map<RoleId, Int> = view.ruleSet.roles.associate { it.id to it.copies }

    /**
     * 크기 [pool]의 풀에서 [draws]장을 뽑을 때, [hits]장 있는 "맞는 카드"가 한 장 이상 나올 확률(초기하).
     */
    fun atLeastOne(pool: Int, hits: Int, draws: Int): Double {
        if (draws <= 0 || hits <= 0 || pool <= 0) return 0.0
        if (hits >= pool) return 1.0
        var none = 1.0
        for (i in 0 until draws) {
            val remaining = pool - i
            if (remaining <= 0) break
            none *= (remaining - hits).coerceAtLeast(0).toDouble() / remaining
        }
        return 1.0 - none
    }
}
