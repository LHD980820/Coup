package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.ResponseWindow
import io.github.lhd980820.coup.engine.rules.ActionDefinition
import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.TargetContext
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.view.ActionOption
import io.github.lhd980820.coup.engine.view.BlockOption
import io.github.lhd980820.coup.engine.view.DecisionRequest

/** 합법 수 계산. 명령 검증과 [io.github.lhd980820.coup.engine.view.DecisionRequest] 생성이 같은 규칙을 쓰도록 한 곳에 둔다. */
internal object LegalMoves {

    /** 코인이 임계값 이상이라 강제 행동만 허용되는가. */
    fun isForced(state: GameState, rules: RuleSet, player: PlayerId): Boolean =
        rules.forcedAction != null && state.player(player).coins >= rules.params.forcedActionThreshold

    fun validTargets(state: GameState, actor: PlayerId, action: ActionDefinition): Set<PlayerId>? =
        when (val targeting = action.targeting) {
            Targeting.None -> null
            is Targeting.OtherAlivePlayer -> state.alivePlayers
                .filter { it != actor }
                .filter { id ->
                    val p = state.player(id)
                    targeting.filter?.accepts(TargetContext(id, p.coins, p.hiddenCards.size)) ?: true
                }
                .toSet()
        }

    fun actionOptions(state: GameState, rules: RuleSet, player: PlayerId): List<ActionOption> {
        val me = state.player(player)
        val forced = isForced(state, rules, player)
        val handRoles = me.hiddenCards.map { it.role }.toSet()
        return rules.actions
            .filter { !forced || it.isForcedWhenRich }
            .map { action ->
                val claimed = rules.rolesGranting(action.id)
                ActionOption(
                    actionId = action.id,
                    cost = action.cost,
                    affordable = me.coins >= action.cost,
                    validTargets = validTargets(state, player, action),
                    claimedRoles = claimed,
                    iHoldClaimedRole = claimed.isEmpty() || claimed.any { it in handRoles },
                    forcedOnly = forced,
                )
            }
    }

    /** 응답 창에서 [player]가 받는 결정. 응답 권한이 없으면 null. */
    fun respondRequest(state: GameState, player: PlayerId, window: ResponseWindow): DecisionRequest.Respond? {
        val allowed = window.allowed[player] ?: return null
        val pending = checkNotNull(state.currentAction) { "response window without a pending action" }
        val handRoles = state.player(player).hiddenCards.map { it.role }.toSet()
        return DecisionRequest.Respond(
            windowKind = window.kind,
            pending = pending,
            canChallenge = allowed.canChallenge,
            blockOptions = allowed.blockRoles.map { BlockOption(it, iHoldRole = it in handRoles) },
        )
    }
}
