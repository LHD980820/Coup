package io.github.lhd980820.coup.presentation

import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.view.VisibleEvent

/** 진행 로그의 한 줄(구조화된 값, 번역은 `:app`). 노이즈를 줄이려고 "허용(Pass)"과 코인 증감은 싣지 않는다. */
public sealed interface LogEntry {
    public data class ActionDeclared(val actor: PlayerId, val actionId: ActionId, val target: PlayerId?, val claimedRoles: Set<RoleId>) : LogEntry
    public data class Blocked(val blocker: PlayerId, val role: RoleId, val actionId: ActionId) : LogEntry
    public data class Challenged(val challenger: PlayerId, val challenged: PlayerId, val claimedRoles: Set<RoleId>) : LogEntry
    public data class Revealed(val player: PlayerId, val role: RoleId, val proven: Boolean) : LogEntry
    public data class InfluenceLost(val player: PlayerId, val role: RoleId, val reason: LossReason) : LogEntry
    public data class CardReplaced(val player: PlayerId) : LogEntry
    public data class ExchangeDone(val player: PlayerId) : LogEntry
    public data class Resolved(val actionId: ActionId, val outcome: ActionOutcome) : LogEntry
    public data class Conceded(val player: PlayerId) : LogEntry
    public data class Eliminated(val player: PlayerId) : LogEntry
    public data class GameEnded(val winner: PlayerId) : LogEntry
}

/**
 * 애니메이션 신호(설계 §10.3: 상태는 즉시 반영하고 애니메이션은 이벤트로 덧입힌다).
 * 화면은 큐에 쌓아 순서대로 재생하거나 무시해도 된다 — 진실은 항상 [GameUiState]다.
 */
public sealed interface UiEffect {
    public data class CoinsChanged(val player: PlayerId, val delta: Int) : UiEffect
    public data class CardRevealed(val player: PlayerId, val role: RoleId, val proven: Boolean) : UiEffect
    public data class InfluenceLost(val player: PlayerId, val role: RoleId) : UiEffect
    public data class CardReplaced(val player: PlayerId) : UiEffect
    public data class Passed(val player: PlayerId) : UiEffect
    public data class Eliminated(val player: PlayerId) : UiEffect
    public data class TurnStarted(val player: PlayerId, val turnNumber: Int) : UiEffect
    public data class GameEnded(val winner: PlayerId) : UiEffect
}

/** 사용자에게 잠깐 알려주는 메시지(스낵바/토스트). */
public sealed interface UiMessage {
    public data class Rejected(val reason: io.github.lhd980820.coup.engine.command.Rejection) : UiMessage
    public data object NetworkError : UiMessage
}

public object EventMapper {
    public fun log(event: VisibleEvent): LogEntry? = when (val e = event.event) {
        is GameEvent.ActionDeclared -> LogEntry.ActionDeclared(e.actor, e.actionId, e.target, e.claimedRoles)
        is GameEvent.BlockDeclared -> LogEntry.Blocked(e.blocker, e.role, e.actionId)
        is GameEvent.ChallengeIssued -> LogEntry.Challenged(e.challenger, e.challenged, e.claimedRoles)
        is GameEvent.CardRevealed -> LogEntry.Revealed(e.player, e.card.role, e.proven)
        is GameEvent.InfluenceLost -> LogEntry.InfluenceLost(e.player, e.card.role, e.reason)
        is GameEvent.CardReplaced, is GameEvent.CardReplacedHidden -> LogEntry.CardReplaced(playerOf(e))
        is GameEvent.ExchangeCompleted -> LogEntry.ExchangeDone(e.player)
        is GameEvent.ActionResolved -> LogEntry.Resolved(e.actionId, e.outcome)
        is GameEvent.PlayerConceded -> LogEntry.Conceded(e.player)
        is GameEvent.PlayerEliminated -> LogEntry.Eliminated(e.player)
        is GameEvent.GameEnded -> LogEntry.GameEnded(e.winner)
        is GameEvent.TurnStarted,
        is GameEvent.Passed,
        is GameEvent.CoinsChanged,
        is GameEvent.ExchangeDrawn,
        is GameEvent.ExchangeDrawnHidden,
        -> null
    }

    public fun effect(event: VisibleEvent): UiEffect? = when (val e = event.event) {
        is GameEvent.CoinsChanged -> UiEffect.CoinsChanged(e.player, e.delta)
        is GameEvent.CardRevealed -> UiEffect.CardRevealed(e.player, e.card.role, e.proven)
        is GameEvent.InfluenceLost -> UiEffect.InfluenceLost(e.player, e.card.role)
        is GameEvent.CardReplaced, is GameEvent.CardReplacedHidden -> UiEffect.CardReplaced(playerOf(e))
        is GameEvent.Passed -> UiEffect.Passed(e.player)
        is GameEvent.PlayerEliminated -> UiEffect.Eliminated(e.player)
        is GameEvent.TurnStarted -> UiEffect.TurnStarted(e.player, e.turnNumber)
        is GameEvent.GameEnded -> UiEffect.GameEnded(e.winner)
        else -> null
    }

    private fun playerOf(e: GameEvent): PlayerId = when (e) {
        is GameEvent.CardReplaced -> e.player
        is GameEvent.CardReplacedHidden -> e.player
        else -> error("not a card replacement: $e")
    }
}
