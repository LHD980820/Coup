package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rng.DeterministicRng
import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.serialization.ENGINE_SCHEMA_VERSION
import io.github.lhd980820.coup.engine.view.ActionSummary
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.GameResultView
import io.github.lhd980820.coup.engine.view.MyView
import io.github.lhd980820.coup.engine.view.OpponentView
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.PublicPhase
import io.github.lhd980820.coup.engine.view.RoleSummary
import io.github.lhd980820.coup.engine.view.RuleSetSummary
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.engine.view.VisibleEvent

/** 시점별 정보 은닉(설계 §4.6, §4.7)과 그 역방향인 추정 상태 생성(§4.3 determinize). */
internal object ViewProjector {

    fun view(state: GameState, rules: RuleSet, viewer: Viewer, decision: DecisionRequest?): PlayerView {
        val meId = (viewer as? Viewer.Player)?.id?.takeIf { it in state.seats }
        val me = meId?.let { state.player(it) }?.let { MyView(it.id, it.coins, it.influences) }
        val opponents = state.seats.filter { it != meId }.map { id ->
            val p = state.player(id)
            OpponentView(id, p.coins, p.hiddenCards.size, p.revealedRoles, p.isAlive)
        }
        val over = state.phase as? Phase.GameOver
        return PlayerView(
            gameId = state.gameId,
            version = state.version,
            viewer = viewer,
            ruleSet = summary(state, rules),
            seats = state.seats,
            me = me,
            opponents = opponents,
            deckSize = state.deck.size,
            turn = state.turn,
            phase = publicPhase(state.phase),
            currentAction = state.currentAction,
            myDecision = if (meId != null) decision else null,
            eliminationOrder = state.eliminationOrder,
            result = over?.let { GameResultView(it.winner, it.ranking) },
            pendingSteps = state.stack,
        )
    }

    /** 비공개 이벤트는 당사자에게만 원본을, 그 외에게는 마스킹된 이벤트를 준다. */
    fun projectEvents(events: List<GameEvent>, viewer: Viewer): List<VisibleEvent> {
        val meId = (viewer as? Viewer.Player)?.id
        return events.map { event ->
            VisibleEvent(
                when (event) {
                    is GameEvent.CardReplaced ->
                        if (event.player == meId) event else GameEvent.CardReplacedHidden(event.player, event.returned)
                    is GameEvent.ExchangeDrawn ->
                        if (event.player == meId) event else GameEvent.ExchangeDrawnHidden(event.player, event.cards.size)
                    else -> event
                },
            )
        }
    }

    private fun publicPhase(phase: Phase): PublicPhase = when (phase) {
        is Phase.AwaitingAction -> PublicPhase.AwaitingAction(phase.actor)
        is Phase.AwaitingResponses -> PublicPhase.AwaitingResponses(phase.window)
        is Phase.AwaitingReveal -> PublicPhase.AwaitingReveal(phase.challenged, phase.challenger, phase.claimedRoles, phase.context)
        is Phase.AwaitingInfluenceLoss -> PublicPhase.AwaitingInfluenceLoss(phase.player, phase.reason)
        is Phase.AwaitingExchange -> PublicPhase.AwaitingExchange(phase.player, phase.candidates.size, phase.keepCount)
        is Phase.GameOver -> PublicPhase.GameOver(phase.winner, phase.ranking)
    }

    private fun summary(state: GameState, rules: RuleSet) = RuleSetSummary(
        config = state.ruleSetConfig,
        roles = rules.roles.map { RoleSummary(it.id, it.copies, it.grantsActions, it.blocksActions) },
        actions = rules.actions.map {
            ActionSummary(
                id = it.id,
                cost = it.cost,
                targeted = it.targeting !is Targeting.None,
                blockPolicy = it.blockPolicy,
                isForcedWhenRich = it.isForcedWhenRich,
                claimedRoles = rules.rolesGranting(it.id),
                blockingRoles = rules.rolesBlocking(it.id),
            )
        },
        params = rules.params,
    )

    /**
     * 뷰 + 가정된 비공개 정보로 시뮬레이션 가능한 상태를 만든다. 진짜 상태와 비공개 부분만 다르고 공개 부분은 같다
     * (그 결과를 다시 같은 시점으로 투영하면 원래 뷰와 같아야 한다).
     * 상대의 카드 ID는 새로 부여한다(실제 ID는 알 수 없고 알 필요도 없다).
     * @throws IllegalArgumentException 가정이 공개 정보와 모순될 때(장수 불일치, 덱 구성 초과/부족)
     */
    fun determinize(view: PlayerView, rules: RuleSet, assignment: HiddenAssignment, seed: Long): GameState {
        val me = view.me
        val knownDeckTop: List<Card> = (view.myDecision as? DecisionRequest.ChooseExchange)
            ?.let { it.candidates.drop(it.keepCount) }.orEmpty()

        require(view.deckSize == knownDeckTop.size + assignment.deckOrder.size) {
            "deck order has ${assignment.deckOrder.size} cards but ${view.deckSize - knownDeckTop.size} are unknown"
        }
        val expectedHands = view.opponents.associate { it.id to it.hiddenCount }
        require(assignment.hands.keys.all { it in expectedHands }) { "hand assignment contains players that are not opponents" }
        view.opponents.forEach { op ->
            require(assignment.hands[op.id].orEmpty().size == op.hiddenCount) {
                "player ${op.id.value} has ${op.hiddenCount} hidden cards but ${assignment.hands[op.id].orEmpty().size} were assigned"
            }
        }

        // 역할 구성 검증: 공개된 것 + 가정한 것 = 룰셋의 덱 구성.
        val used = buildList {
            me?.hand?.forEach { add(it.card.role) }
            view.opponents.forEach { addAll(it.revealed) }
            assignment.hands.values.forEach { addAll(it) }
            knownDeckTop.forEach { add(it.role) }
            addAll(assignment.deckOrder)
        }.groupingBy { it }.eachCount()
        val composition = rules.roles.associate { it.id to it.copies }
        require(used == composition.filterValues { it > 0 }) { "assumed roles $used do not match the deck composition $composition" }

        // 카드 ID: 내가 아는 카드는 실제 ID 유지, 나머지는 겹치지 않게 새로 부여.
        val reserved = (me?.hand?.map { it.card.id }.orEmpty() + knownDeckTop.map { it.id }).map { it.value }.toSet()
        val freshIds = generateSequence(0) { it + 1 }.filter { it !in reserved }.iterator()
        fun fresh(role: RoleId) = Card(CardId(freshIds.next()), role)

        val players: Map<PlayerId, PlayerState> = view.seats.associateWith { id ->
            if (me != null && id == me.id) {
                PlayerState(id, me.coins, me.hand)
            } else {
                val op = checkNotNull(view.opponent(id))
                PlayerState(
                    id,
                    op.coins,
                    op.revealed.map { Influence(fresh(it), revealed = true) } +
                        assignment.hands[id].orEmpty().map { Influence(fresh(it)) },
                )
            }
        }
        val deck = knownDeckTop + assignment.deckOrder.map(::fresh)

        val phase: Phase = when (val p = view.phase) {
            is PublicPhase.AwaitingAction -> Phase.AwaitingAction(p.actor)
            is PublicPhase.AwaitingResponses -> Phase.AwaitingResponses(p.window)
            is PublicPhase.AwaitingReveal -> Phase.AwaitingReveal(p.challenged, p.challenger, p.claimedRoles, p.context)
            is PublicPhase.AwaitingInfluenceLoss -> Phase.AwaitingInfluenceLoss(p.player, p.reason)
            is PublicPhase.AwaitingExchange -> {
                val drawn = deck.take(p.candidateCount - p.keepCount)
                Phase.AwaitingExchange(p.player, players.getValue(p.player).hiddenCards + drawn, p.keepCount)
            }
            is PublicPhase.GameOver -> Phase.GameOver(p.winner, p.ranking)
        }

        return GameState(
            schemaVersion = ENGINE_SCHEMA_VERSION,
            gameId = view.gameId,
            version = view.version,
            ruleSetConfig = view.ruleSet.config,
            seats = view.seats,
            turn = view.turn,
            eliminationOrder = view.eliminationOrder,
            players = players,
            deck = deck,
            phase = phase,
            currentAction = view.currentAction,
            stack = view.pendingSteps,
            rng = DeterministicRng.ofSeed(seed),
        )
    }
}
