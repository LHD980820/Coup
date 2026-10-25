package io.github.lhd980820.coup.ai.belief

import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import kotlin.random.Random

/**
 * 뷰와 모순되지 않는 비공개 정보(상대 손패·덱 순서)를 신념에 따라 뽑는다(설계 §7.3 `sampleHiddenAssignment`).
 * 미확인 풀(덱 구성 − 내가 본 카드)을 섞어 나눠 주되, 상대가 지금도 들고 있다고 주장한 역할은
 * 그 상대의 관찰된 블러핑 비율만큼만 의심해서(= 1 − 블러핑 비율의 확률로 그 역할을 먼저 쥐여 준다) 주장 정보를 반영한다.
 * 진짜 상태를 읽지 않는다 — 입력은 뷰와 공개 기록뿐이다.
 */
internal class HiddenSampler(private val view: PlayerView, private val history: ClaimHistory) {
    private val unseen: Map<RoleId, Int> = CardCounter.unseen(view)

    /** 내가 교환 중이라 이미 아는 덱 위 카드 수(determinize가 따로 다룬다). */
    private val knownDeckTop: Int = (view.myDecision as? DecisionRequest.ChooseExchange)
        ?.let { ex ->
            val handIds = view.me?.hand?.map { it.card.id }.orEmpty().toSet()
            ex.candidates.count { it.id !in handIds }
        } ?: 0

    private val deckUnknown: Int = view.deckSize - knownDeckTop

    /** 뷰가 허용하는 배정을 뽑는다. 풀과 자리 수가 맞지 않는(뷰가 이상한) 경우 null. */
    fun sample(random: Random): HiddenAssignment? {
        val pool = unseen.toMutableMap()
        val hands = view.opponents.filter { it.hiddenCount > 0 }.associate { it.id to mutableListOf<RoleId>() }

        // 주장 반영: 주장한 역할을 (1 - 블러핑 비율)의 확률로 실제로 쥐고 있다고 본다
        for (op in view.opponents) {
            val hand = hands[op.id] ?: continue
            val claimed = history.claimed(op.id).filter { (pool[it] ?: 0) > 0 }.sortedBy { it.value }
            if (claimed.isEmpty()) continue
            if (random.nextDouble() < 1.0 - history.bluffRate(op.id, Beliefs.BLUFF_PRIOR)) {
                val role = claimed.random(random)
                hand += role
                pool[role] = pool.getValue(role) - 1
            }
        }

        val rest = pool.entries.sortedBy { it.key.value }.flatMap { (role, n) -> List(n) { role } }.toMutableList()
        rest.shuffle(random)
        var next = 0
        for (op in view.opponents) {
            val hand = hands[op.id] ?: continue
            while (hand.size < op.hiddenCount) {
                if (next >= rest.size) return null
                hand += rest[next++]
            }
        }
        val deck = rest.subList(next, rest.size).toList()
        if (deck.size != deckUnknown) return null
        return HiddenAssignment(hands, deck)
    }
}
