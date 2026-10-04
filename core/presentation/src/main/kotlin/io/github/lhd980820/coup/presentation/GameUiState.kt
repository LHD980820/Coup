package io.github.lhd980820.coup.presentation

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.runtime.session.ConnectionState

/**
 * 게임 화면이 그리는 모든 것(설계 §10.4). 문자열이 아니라 **구조화된 값**이다 — 번역과 표시는 `:app`이 문자열 리소스·
 * 카드 아트 카탈로그로 한다(역할/행동 ID -> 이름·아이콘, 없으면 제네릭 카드로 폴백). 이 계층은 번역을 모른다.
 */
public data class GameUiState(
    val gameId: String,
    val turnNumber: Int,
    val banner: Banner,
    val opponents: List<OpponentUi>,
    val me: MyUi?,
    val deckSize: Int,
    val decision: DecisionUi?,
    /** 내 결정 마감(epoch millis). 제한이 없으면 null. */
    val myDeadline: Long?,
    val connection: ConnectionState,
    val result: ResultUi?,
    /** 명령을 보낸 뒤 응답을 기다리는 중 — 버튼을 잠근다(중복 탭 방지). */
    val busy: Boolean,
    val log: List<LogEntry>,
)

public enum class OpponentStatus {
    IDLE,

    /** 지금 행동을 선택하는 중. */
    ACTING,

    /** 응답(도전·막기·허용)이나 카드 선택을 기다리는 중. */
    THINKING,

    /** 응답 창에서 이미 허용했다. */
    RESPONDED,
    ELIMINATED,
}

public data class OpponentUi(
    val id: PlayerId,
    val name: String,
    val coins: Int,
    val hiddenCount: Int,
    val revealed: List<RoleId>,
    val isAlive: Boolean,
    val status: OpponentStatus,
    /** 이 플레이어가 지금 주장 중인 역할(진행 중인 행동/막기). 배지로 보여준다. */
    val claiming: Set<RoleId>,
    val deadline: Long?,
    /** 대상 선택 중일 때 눌러서 고를 수 있는가. */
    val targetable: Boolean,
)

public data class CardUi(
    val id: CardId,
    val role: RoleId,
    val revealed: Boolean,
    val selected: Boolean = false,
    /** 도전받은 주장(공개해야 하는 역할)을 증명하는 카드인가. 내 카드이므로 알려줘도 된다. */
    val satisfiesClaim: Boolean = false,
)

public data class MyUi(
    val id: PlayerId,
    val name: String,
    val coins: Int,
    val hand: List<CardUi>,
    val isAlive: Boolean,
    val status: OpponentStatus,
)

public enum class DisabledReason { INSUFFICIENT_COINS, NO_VALID_TARGET }

public data class ActionButtonUi(
    val actionId: ActionId,
    val cost: Int,
    val enabled: Boolean,
    val disabledReason: DisabledReason?,
    /** 주장할 역할이 내 손에 없다(블러핑). 색만으로 전달하지 않도록 별도 표시를 쓴다. */
    val bluff: Boolean,
    val claimedRoles: Set<RoleId>,
    val needsTarget: Boolean,
    val validTargets: List<PlayerId>,
    /** 코인이 너무 많아 이 행동만 가능하다. */
    val forced: Boolean,
)

public data class BlockButtonUi(val role: RoleId, val bluff: Boolean)

public enum class CardPurpose { REVEAL, LOSE }

public sealed interface DecisionUi {
    public data class Actions(val buttons: List<ActionButtonUi>) : DecisionUi

    /** 대상이 필요한 행동을 골랐고 대상을 고르는 중. */
    public data class PickTarget(val actionId: ActionId, val targets: List<PlayerId>) : DecisionUi

    public data class Respond(
        val windowKind: WindowKind,
        val actionId: ActionId,
        val actor: PlayerId,
        val target: PlayerId?,
        /** 도전하면 증명해야 하는 사람. 막기에 대한 도전이면 막은 사람, 아니면 행위자. */
        val claimant: PlayerId,
        val claimedRoles: Set<RoleId>,
        val canChallenge: Boolean,
        val blocks: List<BlockButtonUi>,
    ) : DecisionUi

    public data class PickCard(
        val purpose: CardPurpose,
        val cards: List<CardUi>,
        /** [CardPurpose.REVEAL]일 때 도전받은 역할들. */
        val claimedRoles: Set<RoleId>,
        val reason: LossReason?,
        val selected: CardId?,
    ) : DecisionUi

    public data class PickExchange(
        val candidates: List<CardUi>,
        val keepCount: Int,
        val selectedCount: Int,
    ) : DecisionUi {
        val canConfirm: Boolean get() = selectedCount == keepCount
    }
}

/** 화면 상단 진행 배너: 지금 무슨 일이 벌어지고 있는가. */
public sealed interface Banner {
    /** 내 행동 차례. [forced]면 코인이 많아 쿠만 가능. */
    public data class YourTurn(val forced: Boolean) : Banner

    public data class WaitingFor(val actor: PlayerId) : Banner

    /** 행동이 선언됐고 응답을 기다리는 중. */
    public data class ActionDeclared(
        val actor: PlayerId,
        val actionId: ActionId,
        val target: PlayerId?,
        val claimedRoles: Set<RoleId>,
        val waitingOn: Set<PlayerId>,
    ) : Banner

    /** 막기 선언에 대한 도전을 기다리는 중. */
    public data class BlockDeclared(
        val blocker: PlayerId,
        val role: RoleId,
        val actor: PlayerId,
        val actionId: ActionId,
        val waitingOn: Set<PlayerId>,
    ) : Banner

    /** 행동 도전이 실패한 뒤 막기만 가능한 창. */
    public data class BlockWindow(
        val actor: PlayerId,
        val actionId: ActionId,
        val target: PlayerId?,
        val waitingOn: Set<PlayerId>,
    ) : Banner

    public data class Challenged(
        val challenger: PlayerId,
        val challenged: PlayerId,
        val claimedRoles: Set<RoleId>,
        val againstBlock: Boolean,
    ) : Banner

    public data class LosingInfluence(val player: PlayerId, val reason: LossReason) : Banner
    public data class Exchanging(val player: PlayerId) : Banner
    public data class GameOver(val winner: PlayerId, val ranking: List<PlayerId>) : Banner
}

public data class ResultUi(val winner: PlayerId, val ranking: List<PlayerId>, val iWon: Boolean, val myRank: Int?)
