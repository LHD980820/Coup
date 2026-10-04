package io.github.lhd980820.coup.ai.policy

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.view.ActionSummary
import io.github.lhd980820.coup.engine.view.PlayerView

/**
 * 행동·역할·상대의 가치 추정(설계 §7.4, §7.6).
 * 기본 룰셋의 알려진 행동은 표로, 처음 보는 행동은 룰 요약(비용, 대상 여부, 막기 정책)으로 추정한다.
 * 그래서 하우스룰로 추가된 역할도 별도 코드 없이 다룰 수 있다.
 */
internal object Valuation {
    private val known: Map<String, Double> = mapOf(
        "income" to 1.0,
        "foreign_aid" to 2.0,
        "tax" to 3.0,
        "steal" to 2.2,
        "exchange" to 1.2,
        "assassinate" to 4.0,
        "coup" to 5.0,
    )

    /** 행동이 성공했을 때의 대략적인 이득(코인 1 ≈ 1점, 상대 영향력 1장 ≈ 4~5점). */
    fun actionGain(action: ActionSummary): Double = known[action.id.value] ?: when {
        action.targeted && action.cost > 0 -> 4.0
        action.targeted -> 2.0
        action.claimedRoles.isEmpty() -> 1.0
        else -> 2.0
    }

    fun summary(view: PlayerView, id: ActionId): ActionSummary? = view.ruleSet.actions.firstOrNull { it.id == id }

    /** 카드로서의 가치: 주장할 수 있는 행동 중 최고 이득 + 막을 수 있는 행동 수. */
    fun roleValue(view: PlayerView, role: RoleId): Double {
        val def = view.ruleSet.roles.firstOrNull { it.id == role } ?: return 0.0
        val grants = view.ruleSet.actions.filter { it.id in def.grantsActions }.maxOfOrNull { actionGain(it) } ?: 0.0
        val blocksTargeted = view.ruleSet.actions.count { it.id in def.blocksActions && it.blockPolicy == BlockPolicy.TARGET_ONLY }
        val blocksAny = view.ruleSet.actions.count { it.id in def.blocksActions && it.blockPolicy == BlockPolicy.ANY_OTHER_PLAYER }
        return grants + 2.0 * blocksTargeted + 0.8 * blocksAny
    }

    /** 상대의 위협도: 남은 영향력과 코인(쿠가 가까울수록 위험). */
    fun threat(view: PlayerView, player: PlayerId): Double {
        val op = view.opponent(player) ?: return 0.0
        if (!op.isAlive) return -100.0
        return op.hiddenCount * 2.0 + op.coins / 3.0 + if (op.coins >= 7) 2.0 else 0.0
    }

    /** 내 카드 한 장을 잃는 비용. 마지막 카드면 탈락이라 훨씬 크다. */
    fun myLossCost(view: PlayerView): Double {
        val hidden = view.me?.hand?.count { !it.revealed } ?: 0
        return if (hidden <= 1) 10.0 else 4.0
    }
}
