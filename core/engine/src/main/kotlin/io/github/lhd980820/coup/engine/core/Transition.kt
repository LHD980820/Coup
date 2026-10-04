package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.BlockClaim
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.ChallengeContext
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.AllowedResponses
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.ResponseWindow
import io.github.lhd980820.coup.engine.model.RoleId
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
        is ResolutionStep.ReplaceProvenCard -> replaceProvenCard(step.player, step.cardId).let { false }
        ResolutionStep.ContinueAfterActionChallengeFailed -> continueAfterActionChallengeFailed().let { false }
        ResolutionStep.RefundCost -> refundCost().let { false }
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
            // 막기에 대한 도전: 막은 사람을 제외한 생존자 전원(행위자 포함)이 도전만 할 수 있다.
            WindowKind.BLOCK_CHALLENGE -> {
                val blocker = checkNotNull(pending.blockedBy) { "block challenge window without a block" }.blocker
                state.alivePlayers.filter { it != blocker }
                    .associateWith { AllowedResponses(canChallenge = true, blockRoles = emptySet()) }
            }
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
            // 아무도 막기에 도전하지 않음 → 막기 성립. 이미 지불한 비용은 돌려주지 않는다.
            WindowKind.BLOCK_CHALLENGE -> {
                emit(GameEvent.ActionResolved(checkNotNull(state.currentAction).actionId, ActionOutcome.BLOCKED))
                push(ResolutionStep.EndTurn)
            }
        }
    }

    /** [blocker]가 [role]을 주장하며 현재 행동을 막는다. 응답 창을 닫고 막기에 대한 도전 창을 연다. */
    fun declareBlock(blocker: PlayerId, role: RoleId) {
        val pending = checkNotNull(state.currentAction)
        state = state.copy(currentAction = pending.copy(blockedBy = BlockClaim(blocker, role)))
        emit(GameEvent.BlockDeclared(blocker, role, pending.actionId))
        push(ResolutionStep.OpenResponseWindow(WindowKind.BLOCK_CHALLENGE))
    }

    /**
     * 도전 개시: 응답 창을 닫고 도전받은 사람의 공개를 기다린다.
     * 미공개 카드가 1장뿐이면 고를 것이 없으므로 즉시 공개한다.
     * @return 입력을 기다려야 하면 true
     */
    fun startChallenge(challenger: PlayerId, challenged: PlayerId, claimedRoles: Set<RoleId>, context: ChallengeContext): Boolean {
        emit(GameEvent.ChallengeIssued(challenger, challenged, claimedRoles))
        state = state.copy(phase = Phase.AwaitingReveal(challenged, challenger, claimedRoles, context))
        val hidden = state.player(challenged).hiddenCards
        if (hidden.size == 1) {
            resolveReveal(hidden.single().id)
            return false
        }
        return true
    }

    /**
     * 도전받은 사람이 [cardId]를 공개했다(설계 §4.5).
     * - 주장 역할이면 증명: 카드 교체 → 도전자 영향력 상실 → (행동 도전이면) 행동 계속.
     * - 아니면 블러핑 발각: 공개한 카드를 잃고 → (행동 도전이면) 행동 실패, 비용 환불, 턴 종료.
     */
    fun resolveReveal(cardId: CardId) {
        val phase = state.phase as Phase.AwaitingReveal
        val card = state.player(phase.challenged).hiddenCards.first { it.id == cardId }
        val proven = card.role in phase.claimedRoles
        emit(GameEvent.CardRevealed(phase.challenged, card, proven))

        if (proven) {
            // 교체를 상실보다 먼저 한다: 도전자가 잃을 카드를 고르는 동안 공개된 카드가 손패에 남아 있지 않게.
            val continuation = when (phase.context) {
                ChallengeContext.ACTION -> ResolutionStep.ContinueAfterActionChallengeFailed
                // 막기가 증명됨 → 막기 성립, 행동 실패(비용 환불 없음).
                ChallengeContext.BLOCK -> {
                    emit(GameEvent.ActionResolved(checkNotNull(state.currentAction).actionId, ActionOutcome.BLOCKED))
                    ResolutionStep.EndTurn
                }
            }
            push(
                ResolutionStep.ReplaceProvenCard(phase.challenged, cardId),
                ResolutionStep.RequireInfluenceLoss(phase.challenger, LossReason.ChallengeLost),
                continuation,
            )
        } else {
            when (phase.context) {
                ChallengeContext.ACTION -> {
                    val pending = checkNotNull(state.currentAction)
                    emit(GameEvent.ActionResolved(pending.actionId, ActionOutcome.FAILED))
                    push(ResolutionStep.RefundCost, ResolutionStep.EndTurn)
                }
                // 막기 블러핑 발각 → 막기 무효, 행동이 그대로 해결된다(대상이 막은 사람이면 추가로 잃을 수 있다).
                ChallengeContext.BLOCK -> push(ResolutionStep.ApplyEffect, ResolutionStep.EndTurn)
            }
            // 스택을 먼저 쌓은 뒤 공개한다: 이 공개로 게임이 끝나면 reveal()이 스택을 비운다.
            reveal(phase.challenged, cardId, LossReason.BluffExposed)
        }
    }

    /** 증명에 쓴 카드를 덱에 넣고 셔플한 뒤 맨 위 카드를 같은 자리에 받는다. */
    private fun replaceProvenCard(player: PlayerId, cardId: CardId) {
        val owner = state.player(player)
        val slot = owner.influences.indexOfFirst { it.card.id == cardId && !it.revealed }
        if (slot < 0) return // 그사이 카드가 사라졌다(예: 기권) — 교체할 것이 없다.
        val returned = owner.influences[slot].card
        val (shuffled, rng) = state.rng.shuffled(state.deck + returned)
        val drawn = shuffled.first()
        state = state.copy(deck = shuffled.drop(1), rng = rng)
        updatePlayer(player) { p ->
            p.copy(influences = p.influences.toMutableList().also { it[slot] = Influence(drawn) })
        }
        emit(GameEvent.CardReplaced(player, returned, drawn))
    }

    /** 행동에 대한 도전이 실패(행위자가 증명)한 뒤: 막을 수 있는 행동이면 막기 전용 창, 아니면 효과 적용. */
    private fun continueAfterActionChallengeFailed() {
        val pending = checkNotNull(state.currentAction)
        val action = checkNotNull(rules.action(pending.actionId))
        if (action.blockPolicy != BlockPolicy.NONE && pending.blockedBy == null) {
            push(ResolutionStep.OpenResponseWindow(WindowKind.BLOCK_ONLY))
        } else {
            push(ResolutionStep.ApplyEffect, ResolutionStep.EndTurn)
        }
    }

    private fun refundCost() {
        val pending = checkNotNull(state.currentAction)
        if (rules.params.refundCostWhenActionChallengeLost && pending.costPaid > 0) {
            changeCoins(pending.actor, pending.costPaid, pending.actionId)
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
