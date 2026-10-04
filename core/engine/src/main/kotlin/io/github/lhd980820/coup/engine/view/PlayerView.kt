package io.github.lhd980820.coup.engine.view

import io.github.lhd980820.coup.engine.core.ResolutionStep
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.PendingAction
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.TurnInfo
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.rules.RuleParams
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import kotlinx.serialization.Serializable

/** 내 상태: 내 카드는 공개/미공개 모두 역할까지 보인다. */
@Serializable
public data class MyView(public val id: PlayerId, public val coins: Int, public val hand: List<Influence>)

/** 상대 상태: 미공개 카드는 장수만, 공개된 카드는 역할만. 카드 ID는 노출하지 않는다. */
@Serializable
public data class OpponentView(
    public val id: PlayerId,
    public val coins: Int,
    public val hiddenCount: Int,
    public val revealed: List<RoleId>,
    public val isAlive: Boolean,
)

@Serializable
public data class RoleSummary(
    public val id: RoleId,
    public val copies: Int,
    public val grantsActions: Set<ActionId>,
    public val blocksActions: Set<ActionId>,
)

@Serializable
public data class ActionSummary(
    public val id: ActionId,
    public val cost: Int,
    public val targeted: Boolean,
    public val blockPolicy: BlockPolicy,
    public val isForcedWhenRich: Boolean,
    /** 이 행동을 주장할 수 있는 역할(비어 있으면 주장 불필요). */
    public val claimedRoles: Set<RoleId>,
    /** 이 행동을 막을 수 있는 역할. */
    public val blockingRoles: Set<RoleId>,
)

/** 룰셋의 공개 요약. 덱 구성(카드 카운팅의 근거)과 역할↔행동 관계를 담는다. */
@Serializable
public data class RuleSetSummary(
    public val config: RuleSetConfig,
    public val roles: List<RoleSummary>,
    public val actions: List<ActionSummary>,
    public val params: RuleParams,
) {
    public val totalCards: Int get() = roles.sumOf { it.copies }
}

@Serializable
public data class GameResultView(public val winner: PlayerId, public val ranking: List<PlayerId>)

/**
 * 특정 시점(플레이어 또는 관전자)에서 본 게임 상태. 비공개 정보(상대의 미공개 카드, 덱 내용, RNG)는 포함하지 않는다.
 * 엔진만 만들 수 있다(생성자 internal) — AI나 UI가 뷰를 위조해 엔진에 넣을 수 없다.
 */
@Serializable
@ConsistentCopyVisibility
public data class PlayerView internal constructor(
    public val gameId: String,
    public val version: Long,
    public val viewer: Viewer,
    public val ruleSet: RuleSetSummary,
    /** 좌석 순서 = 턴 순서. */
    public val seats: List<PlayerId>,
    /** 관전자면 null. */
    public val me: MyView?,
    /** 나를 제외한 플레이어(좌석 순서). 관전자면 전원. */
    public val opponents: List<OpponentView>,
    public val deckSize: Int,
    public val turn: TurnInfo,
    public val phase: PublicPhase,
    /** 현재 해결 중인 행동(공개 정보). */
    public val currentAction: PendingAction?,
    /** 내가 지금 내려야 할 결정. 없으면 null. */
    public val myDecision: DecisionRequest?,
    public val eliminationOrder: List<PlayerId>,
    public val result: GameResultView?,
    /** 해결 스택(비밀 정보 아님). 추정 상태 생성(determinize)이 정확히 재개할 수 있도록 엔진 내부용으로만 싣는다. */
    internal val pendingSteps: List<ResolutionStep>,
) {
    public fun opponent(id: PlayerId): OpponentView? = opponents.firstOrNull { it.id == id }
}

/**
 * 시점별로 가시성이 필터링된 이벤트. 엔진의 투영으로만 만들어진다(생성자 internal).
 * AI와 원격 클라이언트는 이 타입만 받는다.
 */
@Serializable
@ConsistentCopyVisibility
public data class VisibleEvent internal constructor(public val event: GameEvent)
