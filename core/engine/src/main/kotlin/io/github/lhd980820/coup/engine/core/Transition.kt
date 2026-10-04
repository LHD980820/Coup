package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.AllowedResponses
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.ResponseWindow
import io.github.lhd980820.coup.engine.model.TurnInfo
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.effect.EffectContext
import io.github.lhd980820.coup.engine.rules.effect.Primitive
import io.github.lhd980820.coup.engine.rules.effect.plan

/**
 * 명령 1개를 적용하는 동안만 존재하는 작업 공간. 외부에는 불변 [GameState]만 나간다.
 * [resolve]가 해결 스택을 입력이 필요할 때까지 소진한다(설계 문서 §4.5).
 */
internal class Transition(var state: GameState, val rules: RuleSet) {
    val events = mutableListOf<GameEvent>()

    fun emit(event: GameEvent) {
        events += event
    }

    fun updatePlayer(id: PlayerId, transform: (PlayerState) -> PlayerState) {
        state = state.copy(players = state.players + (id to transform(state.player(id))))
    }

    /** 스택 맨 앞에 단계들을 순서대로 끼워 넣는다. */
    fun push(vararg steps: ResolutionStep) {
        state = state.copy(stack = steps.toList() + state.stack)
    }

    fun changeCoins(player: PlayerId, delta: Int, actionId: ActionId?) {
        if (delta == 0) return
        updatePlayer(player) { it.copy(coins = it.coins + delta) }
        emit(GameEvent.CoinsChanged(player, delta, state.player(player).coins, actionId))
    }

    /** 입력이 필요한 단계를 만나거나 스택이 빌 때까지 해결한다. */
    fun resolve() {
        var guard = 0
        while (state.phase !is Phase.GameOver) {
            check(++guard < MAX_STEPS) { "resolution did not terminate" }
            val step = state.stack.firstOrNull() ?: return
            state = state.copy(stack = state.stack.drop(1))
            val paused = execute(step)
            if (paused) return
        }
    }

    /** @return 플레이어 입력을 기다려야 하면 true */
    private fun execute(step: ResolutionStep): Boolean = when (step) {
        is ResolutionStep.RequireInfluenceLoss -> requireInfluenceLoss(step.player, step.reason)
        ResolutionStep.ApplyEffect -> applyEffect().let { false }
        is ResolutionStep.Effect -> applyPrimitive(step.primitive).let { false }
        ResolutionStep.EndTurn -> endTurn().let { false }
        is ResolutionStep.OpenResponseWindow -> openResponseWindow(step.kind)
        is ResolutionStep.ReplaceProvenCard -> TODO("Phase 1 step 6: challenges")
        ResolutionStep.ContinueAfterActionChallengeFailed -> TODO("Phase 1 step 6: challenges")
        ResolutionStep.RefundCost -> TODO("Phase 1 step 6: challenges")
    }

    /**
     * 응답 창을 연다. 응답할 수 있는 사람이 아무도 없으면 열지 않고 곧바로 "전원 통과"로 처리한다.
     * @return 입력을 기다려야 하면 true
     */
    private fun openResponseWindow(kind: WindowKind): Boolean {
        val pending = checkNotNull(state.currentAction) { "response window without a pending action" }
        val action = checkNotNull(rules.action(pending.actionId))
        val allowed: Map<PlayerId, AllowedResponses> = when (kind) {
            WindowKind.ACTION, WindowKind.BLOCK_ONLY ->
                state.alivePlayers.filter { it != pending.actor }.mapNotNull { p ->
                    val canChallenge = kind == WindowKind.ACTION && pending.claimedRoles.isNotEmpty()
                    val canBlock = when (action.blockPolicy) {
                        BlockPolicy.NONE -> false
                        BlockPolicy.TARGET_ONLY -> p == pending.target
                        BlockPolicy.ANY_OTHER_PLAYER -> true
                    }
                    val blockRoles = if (canBlock) rules.rolesBlocking(action.id) else emptySet()
                    if (!canChallenge && blockRoles.isEmpty()) null else p to AllowedResponses(canChallenge, blockRoles)
                }.toMap()
            WindowKind.BLOCK_CHALLENGE -> TODO("Phase 1 step 7: blocks")
        }
        if (allowed.isEmpty()) {
            closeAllPassed(kind)
            return false
        }
        state = state.copy(phase = Phase.AwaitingResponses(ResponseWindow(kind, allowed.keys, emptySet(), allowed)))
        return true
    }

    /** 응답 창의 모든 사람이 허용(Pass)했을 때 이어질 단계를 스택에 넣는다. */
    fun closeAllPassed(kind: WindowKind) {
        when (kind) {
            WindowKind.ACTION, WindowKind.BLOCK_ONLY -> push(ResolutionStep.ApplyEffect, ResolutionStep.EndTurn)
            WindowKind.BLOCK_CHALLENGE -> TODO("Phase 1 step 7: blocks")
        }
    }

    private fun requireInfluenceLoss(player: PlayerId, reason: LossReason): Boolean {
        val hidden = state.player(player).hiddenCards
        return when (hidden.size) {
            0 -> false
            1 -> {
                reveal(player, hidden.single().id, reason)
                false
            }
            else -> {
                state = state.copy(phase = Phase.AwaitingInfluenceLoss(player, reason))
                true
            }
        }
    }

    /** 카드를 공개(상실)하고 탈락·게임 종료를 판정한다. */
    fun reveal(player: PlayerId, cardId: CardId, reason: LossReason) {
        updatePlayer(player) { p ->
            p.copy(influences = p.influences.map { if (it.card.id == cardId) it.copy(revealed = true) else it })
        }
        val card = state.player(player).influences.first { it.card.id == cardId }.card
        emit(GameEvent.InfluenceLost(player, card, reason))

        if (state.player(player).isAlive) return
        state = state.copy(eliminationOrder = state.eliminationOrder + player)
        emit(GameEvent.PlayerEliminated(player))

        val alive = state.alivePlayers
        if (alive.size == 1) {
            val winner = alive.single()
            val ranking = listOf(winner) + state.eliminationOrder.reversed()
            state = state.copy(phase = Phase.GameOver(winner, ranking), stack = emptyList(), currentAction = null)
            emit(GameEvent.GameEnded(winner, ranking))
        }
    }

    private fun applyEffect() {
        val pending = checkNotNull(state.currentAction) { "no action to apply" }
        val action = checkNotNull(rules.action(pending.actionId))
        val target = pending.target
        if (target != null && !state.player(target).isAlive) {
            emit(GameEvent.ActionResolved(pending.actionId, ActionOutcome.FIZZLED))
            return
        }
        val ctx = EffectContext(
            actor = pending.actor,
            target = target,
            params = rules.params,
            coins = { state.player(it).coins },
            influences = { state.player(it).hiddenCards.size },
        )
        emit(GameEvent.ActionResolved(pending.actionId, ActionOutcome.SUCCESS))
        push(*action.effect.plan(ctx).map { ResolutionStep.Effect(it) }.toTypedArray())
    }

    private fun applyPrimitive(primitive: Primitive) {
        val actionId = state.currentAction?.actionId
        when (primitive) {
            is Primitive.GainCoins -> changeCoins(primitive.player, primitive.amount, actionId)
            is Primitive.PayCoins ->
                changeCoins(primitive.player, -minOf(primitive.amount, state.player(primitive.player).coins), actionId)
            is Primitive.TransferCoins -> {
                val amount = minOf(primitive.max, state.player(primitive.from).coins)
                changeCoins(primitive.from, -amount, actionId)
                changeCoins(primitive.to, amount, actionId)
            }
            is Primitive.LoseInfluence -> push(
                ResolutionStep.RequireInfluenceLoss(
                    primitive.player,
                    LossReason.ActionEffect(checkNotNull(actionId) { "influence loss outside an action" }),
                ),
            )
            is Primitive.Exchange -> TODO("Phase 1 step 8: exchange")
        }
    }

    private fun endTurn() {
        val seats = state.seats
        val current = seats.indexOf(state.turn.activePlayer)
        val next = (1..seats.size).asSequence()
            .map { seats[(current + it) % seats.size] }
            .first { state.player(it).isAlive }
        val turn = TurnInfo(state.turn.number + 1, next)
        state = state.copy(turn = turn, phase = Phase.AwaitingAction(next), currentAction = null)
        emit(GameEvent.TurnStarted(next, turn.number))
    }

    private companion object {
        const val MAX_STEPS = 10_000
    }
}
