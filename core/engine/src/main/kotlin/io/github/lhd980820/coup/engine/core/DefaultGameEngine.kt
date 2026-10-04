package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.PendingAction
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.TurnInfo
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rng.DeterministicRng
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.serialization.ENGINE_SCHEMA_VERSION
import io.github.lhd980820.coup.engine.view.DecisionRequest

internal class DefaultGameEngine(private val registry: RuleSetRegistry) : GameEngine {

    override fun newGame(setup: GameSetup): GameState {
        val rules = registry.build(setup.ruleSetConfig)
        val params = rules.params
        val seats = setup.seats

        require(seats.size in params.minPlayers..params.maxPlayers) {
            "player count ${seats.size} is outside ${params.minPlayers}..${params.maxPlayers}"
        }
        require(seats.toSet().size == seats.size) { "duplicate seats: $seats" }
        require(setup.firstPlayer == null || setup.firstPlayer in seats) { "first player is not seated: ${setup.firstPlayer}" }

        // 덱 생성: 역할 정의 순서대로 CardId를 0부터 부여한 뒤 셔플한다.
        var nextId = 0
        val ordered = rules.roles.flatMap { role -> List(role.copies) { Card(CardId(nextId++), role.id) } }
        var rng = DeterministicRng.ofSeed(setup.seed)
        val (shuffled, afterShuffle) = rng.shuffled(ordered)
        rng = afterShuffle

        // 분배: 좌석 순서대로 덱 위에서 handSize장씩.
        var deck = shuffled
        val players = seats.associateWith { id ->
            val hand = deck.take(params.handSize)
            deck = deck.drop(params.handSize)
            PlayerState(id, coins = params.startingCoins, influences = hand.map { Influence(it) })
        }

        val first = setup.firstPlayer ?: rng.nextInt(seats.size).let { (index, next) ->
            rng = next
            seats[index]
        }

        return GameState(
            schemaVersion = ENGINE_SCHEMA_VERSION,
            gameId = setup.gameId,
            version = 0,
            ruleSetConfig = setup.ruleSetConfig,
            seats = seats,
            turn = TurnInfo(number = 1, activePlayer = first),
            eliminationOrder = emptyList(),
            players = players,
            deck = deck,
            phase = Phase.AwaitingAction(first),
            currentAction = null,
            stack = emptyList(),
            rng = rng,
        )
    }

    override fun apply(state: GameState, command: Command): ApplyResult {
        if (state.isOver) return ApplyResult.Rejected(Rejection.GAME_OVER)
        val expected = command.expectedVersion
        if (expected != null && expected != state.version) return ApplyResult.Rejected(Rejection.STALE_VERSION)
        val actor = state.players[command.actor] ?: return ApplyResult.Rejected(Rejection.NOT_YOUR_DECISION)
        if (!actor.isAlive) return ApplyResult.Rejected(Rejection.PLAYER_ELIMINATED)

        val tx = Transition(state, rulesOf(state))
        val rejection = when (command) {
            is Command.DeclareAction -> declareAction(tx, command)
            is Command.LoseInfluence -> loseInfluence(tx, command)
            is Command.Pass,
            is Command.Challenge,
            is Command.Block,
            is Command.RevealCard,
            is Command.ChooseExchange,
            -> wrongPhaseOrNotYours(state, command.actor)
        }
        if (rejection != null) return ApplyResult.Rejected(rejection)

        tx.resolve()
        return ApplyResult.Accepted(tx.state.copy(version = state.version + 1), tx.events.toList())
    }

    override fun pendingDeciders(state: GameState): Set<PlayerId> = when (val phase = state.phase) {
        is Phase.AwaitingAction -> setOf(phase.actor)
        is Phase.AwaitingResponses -> phase.window.waitingOn
        is Phase.AwaitingReveal -> setOf(phase.challenged)
        is Phase.AwaitingInfluenceLoss -> setOf(phase.player)
        is Phase.AwaitingExchange -> setOf(phase.player)
        is Phase.GameOver -> emptySet()
    }

    override fun legalOptions(state: GameState, player: PlayerId): DecisionRequest? {
        if (player !in pendingDeciders(state)) return null
        return when (val phase = state.phase) {
            is Phase.AwaitingAction -> DecisionRequest.ChooseAction(LegalMoves.actionOptions(state, rulesOf(state), player))
            is Phase.AwaitingInfluenceLoss -> DecisionRequest.ChooseInfluenceToLose(state.player(player).hiddenCards, phase.reason)
            // 응답/공개/교환 결정은 5~8단계에서 추가된다.
            is Phase.AwaitingResponses, is Phase.AwaitingReveal, is Phase.AwaitingExchange, is Phase.GameOver -> null
        }
    }

    private fun declareAction(tx: Transition, cmd: Command.DeclareAction): Rejection? {
        val state = tx.state
        val phase = state.phase as? Phase.AwaitingAction ?: return wrongPhaseOrNotYours(state, cmd.actor)
        if (phase.actor != cmd.actor) return Rejection.NOT_YOUR_DECISION

        val rules = tx.rules
        val action = rules.action(cmd.actionId) ?: return Rejection.UNKNOWN_ACTION
        if (LegalMoves.isForced(state, rules, cmd.actor) && !action.isForcedWhenRich) return Rejection.FORCED_ACTION_REQUIRED
        if (state.player(cmd.actor).coins < action.cost) return Rejection.INSUFFICIENT_COINS
        val targets = LegalMoves.validTargets(state, cmd.actor, action)
        when (action.targeting) {
            Targeting.None -> if (cmd.target != null) return Rejection.INVALID_TARGET
            is Targeting.OtherAlivePlayer -> if (cmd.target == null || targets == null || cmd.target !in targets) {
                return Rejection.INVALID_TARGET
            }
        }

        val claimed = rules.rolesGranting(action.id)
        tx.state = tx.state.copy(
            currentAction = PendingAction(cmd.actor, action.id, cmd.target, claimed, costPaid = action.cost),
        )
        tx.emit(GameEvent.ActionDeclared(cmd.actor, action.id, cmd.target, claimed))
        tx.changeCoins(cmd.actor, -action.cost, action.id)

        val needsResponses = claimed.isNotEmpty() || action.blockPolicy != BlockPolicy.NONE
        if (needsResponses) {
            tx.push(ResolutionStep.OpenResponseWindow(WindowKind.ACTION))
        } else {
            tx.push(ResolutionStep.ApplyEffect, ResolutionStep.EndTurn)
        }
        return null
    }

    private fun loseInfluence(tx: Transition, cmd: Command.LoseInfluence): Rejection? {
        val phase = tx.state.phase as? Phase.AwaitingInfluenceLoss ?: return wrongPhaseOrNotYours(tx.state, cmd.actor)
        if (phase.player != cmd.actor) return Rejection.NOT_YOUR_DECISION
        val influence = tx.state.player(cmd.actor).influences.firstOrNull { it.card.id == cmd.cardId }
            ?: return Rejection.CARD_NOT_OWNED
        if (influence.revealed) return Rejection.CARD_ALREADY_REVEALED
        tx.reveal(cmd.actor, cmd.cardId, phase.reason)
        return null
    }

    /** 현재 페이즈에서 이 플레이어가 결정권자가 아니면 NOT_YOUR_DECISION, 결정권자지만 명령 종류가 틀리면 WRONG_PHASE. */
    private fun wrongPhaseOrNotYours(state: GameState, actor: PlayerId): Rejection =
        if (actor in pendingDeciders(state)) Rejection.WRONG_PHASE else Rejection.NOT_YOUR_DECISION

    private fun rulesOf(state: GameState): RuleSet = registry.build(state.ruleSetConfig)
}
