package io.github.lhd980820.coup.presentation

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.PublicPhase
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.SessionSnapshot

/** 화면 위에서만 존재하는 일시적 선택 상태(엔진은 모른다). 결정이 바뀌면 [GameController]가 비운다. */
public data class Interaction(
    /** 대상이 필요한 행동을 골랐고 대상을 고르는 중. */
    val pendingAction: ActionId? = null,
    val selectedCards: Set<CardId> = emptySet(),
)

/**
 * [SessionSnapshot] -> [GameUiState]의 순수 함수(설계 §10.4). 모든 버튼의 활성 여부·블러핑 표시는
 * 엔진이 계산한 [DecisionRequest]에서 나온다 — 화면이 규칙을 판단하지 않는다.
 */
public object GameUiMapper {

    public fun map(
        snapshot: SessionSnapshot,
        connection: ConnectionState,
        interaction: Interaction = Interaction(),
        busy: Boolean = false,
        log: List<LogEntry> = emptyList(),
    ): GameUiState {
        val view = snapshot.view
        val meId = view.me?.id
        val decision = decisionOf(view, interaction)
        return GameUiState(
            gameId = view.gameId,
            turnNumber = view.turn.number,
            banner = bannerOf(view),
            opponents = view.opponents.map { opponentOf(view, snapshot, it.id, decision) },
            me = view.me?.let { me ->
                MyUi(
                    id = me.id,
                    name = nameOf(snapshot, me.id),
                    coins = me.coins,
                    hand = me.hand.map { CardUi(it.card.id, it.card.role, it.revealed) },
                    isAlive = me.hand.any { !it.revealed },
                    status = statusOf(view, me.id),
                )
            },
            deckSize = view.deckSize,
            decision = decision,
            myDeadline = snapshot.myDeadline,
            connection = connection,
            result = view.result?.let { r ->
                val rank = meId?.let { r.ranking.indexOf(it) + 1 }?.takeIf { it > 0 }
                ResultUi(r.winner, r.ranking, iWon = r.winner == meId, myRank = rank)
            },
            busy = busy,
            log = log,
        )
    }

    private fun nameOf(snapshot: SessionSnapshot, id: PlayerId): String = snapshot.seatInfo[id]?.displayName ?: id.value

    // ---- 상대 ------------------------------------------------------------------------------------

    private fun opponentOf(view: PlayerView, snapshot: SessionSnapshot, id: PlayerId, decision: DecisionUi?): OpponentUi {
        val op = checkNotNull(view.opponent(id))
        val pending = view.currentAction
        val claiming = when {
            pending == null -> emptySet()
            pending.blockedBy?.blocker == id -> setOf(pending.blockedBy!!.role)
            pending.actor == id -> pending.claimedRoles
            else -> emptySet()
        }
        return OpponentUi(
            id = id,
            name = nameOf(snapshot, id),
            coins = op.coins,
            hiddenCount = op.hiddenCount,
            revealed = op.revealed,
            isAlive = op.isAlive,
            status = statusOf(view, id),
            claiming = claiming,
            deadline = snapshot.othersDeadlines[id],
            targetable = (decision as? DecisionUi.PickTarget)?.targets?.contains(id) == true,
        )
    }

    private fun statusOf(view: PlayerView, id: PlayerId): OpponentStatus {
        val alive = view.me?.takeIf { it.id == id }?.hand?.any { !it.revealed } ?: view.opponent(id)?.isAlive ?: false
        if (!alive) return OpponentStatus.ELIMINATED
        return when (val phase = view.phase) {
            is PublicPhase.AwaitingAction -> if (phase.actor == id) OpponentStatus.ACTING else OpponentStatus.IDLE
            is PublicPhase.AwaitingResponses -> when (id) {
                in phase.window.waitingOn -> OpponentStatus.THINKING
                in phase.window.passed -> OpponentStatus.RESPONDED
                else -> OpponentStatus.IDLE
            }
            is PublicPhase.AwaitingReveal -> if (phase.challenged == id) OpponentStatus.THINKING else OpponentStatus.IDLE
            is PublicPhase.AwaitingInfluenceLoss -> if (phase.player == id) OpponentStatus.THINKING else OpponentStatus.IDLE
            is PublicPhase.AwaitingExchange -> if (phase.player == id) OpponentStatus.THINKING else OpponentStatus.IDLE
            is PublicPhase.GameOver -> OpponentStatus.IDLE
        }
    }

    // ---- 배너 ------------------------------------------------------------------------------------

    private fun bannerOf(view: PlayerView): Banner {
        val me = view.me?.id
        val pending = view.currentAction
        return when (val phase = view.phase) {
            is PublicPhase.AwaitingAction ->
                if (phase.actor == me) {
                    val forced = (view.myDecision as? DecisionRequest.ChooseAction)?.options?.any { it.forcedOnly } == true
                    Banner.YourTurn(forced)
                } else {
                    Banner.WaitingFor(phase.actor)
                }
            is PublicPhase.AwaitingResponses -> {
                val p = checkNotNull(pending) { "response window without a pending action" }
                when (phase.window.kind) {
                    WindowKind.ACTION -> Banner.ActionDeclared(p.actor, p.actionId, p.target, p.claimedRoles, phase.window.waitingOn)
                    WindowKind.BLOCK_ONLY -> Banner.BlockWindow(p.actor, p.actionId, p.target, phase.window.waitingOn)
                    WindowKind.BLOCK_CHALLENGE -> {
                        val block = checkNotNull(p.blockedBy) { "block-challenge window without a block" }
                        Banner.BlockDeclared(block.blocker, block.role, p.actor, p.actionId, phase.window.waitingOn)
                    }
                }
            }
            is PublicPhase.AwaitingReveal ->
                Banner.Challenged(phase.challenger, phase.challenged, phase.claimedRoles, againstBlock = pending?.blockedBy != null)
            is PublicPhase.AwaitingInfluenceLoss -> Banner.LosingInfluence(phase.player, phase.reason)
            is PublicPhase.AwaitingExchange -> Banner.Exchanging(phase.player)
            is PublicPhase.GameOver -> Banner.GameOver(phase.winner, phase.ranking)
        }
    }

    // ---- 결정 ------------------------------------------------------------------------------------

    private fun decisionOf(view: PlayerView, interaction: Interaction): DecisionUi? {
        val request = view.myDecision ?: return null
        return when (request) {
            is DecisionRequest.ChooseAction -> {
                val buttons = request.options.map { o ->
                    val noTarget = o.validTargets != null && o.validTargets!!.isEmpty()
                    ActionButtonUi(
                        actionId = o.actionId,
                        cost = o.cost,
                        enabled = o.selectable,
                        disabledReason = when {
                            !o.affordable -> DisabledReason.INSUFFICIENT_COINS
                            noTarget -> DisabledReason.NO_VALID_TARGET
                            else -> null
                        },
                        bluff = !o.iHoldClaimedRole,
                        claimedRoles = o.claimedRoles,
                        needsTarget = o.validTargets != null,
                        validTargets = o.validTargets?.sortedBy { view.seats.indexOf(it) }.orEmpty(),
                        forced = o.forcedOnly,
                    )
                }
                val pendingButton = interaction.pendingAction?.let { id -> buttons.firstOrNull { it.actionId == id && it.enabled } }
                if (pendingButton != null) DecisionUi.PickTarget(pendingButton.actionId, pendingButton.validTargets) else DecisionUi.Actions(buttons)
            }
            is DecisionRequest.Respond -> {
                val pending = request.pending
                val block = pending.blockedBy
                val againstBlock = request.windowKind == WindowKind.BLOCK_CHALLENGE && block != null
                DecisionUi.Respond(
                    windowKind = request.windowKind,
                    actionId = pending.actionId,
                    actor = pending.actor,
                    target = pending.target,
                    claimant = if (againstBlock) block.blocker else pending.actor,
                    claimedRoles = if (againstBlock) setOf(block.role) else pending.claimedRoles,
                    canChallenge = request.canChallenge,
                    blocks = request.blockOptions.map { BlockButtonUi(it.role, bluff = !it.iHoldRole) },
                )
            }
            is DecisionRequest.ChooseRevealCard -> DecisionUi.PickCard(
                purpose = CardPurpose.REVEAL,
                cards = request.cards.map {
                    CardUi(it.id, it.role, revealed = false, selected = it.id in interaction.selectedCards, satisfiesClaim = it.role in request.claimedRoles)
                },
                claimedRoles = request.claimedRoles,
                reason = null,
                selected = interaction.selectedCards.firstOrNull(),
            )
            is DecisionRequest.ChooseInfluenceToLose -> DecisionUi.PickCard(
                purpose = CardPurpose.LOSE,
                cards = request.cards.map { CardUi(it.id, it.role, revealed = false, selected = it.id in interaction.selectedCards) },
                claimedRoles = emptySet(),
                reason = request.reason,
                selected = interaction.selectedCards.firstOrNull(),
            )
            is DecisionRequest.ChooseExchange -> DecisionUi.PickExchange(
                candidates = request.candidates.map { CardUi(it.id, it.role, revealed = false, selected = it.id in interaction.selectedCards) },
                keepCount = request.keepCount,
                selectedCount = request.candidates.count { it.id in interaction.selectedCards },
            )
        }
    }
}
