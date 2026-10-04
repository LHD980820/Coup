package io.github.lhd980820.coup.engine.view

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import kotlinx.serialization.Serializable

/**
 * 행동 선택지 1개. UI 버튼과 AI 선택지의 유일한 근거.
 * [affordable]이 false인 옵션도 포함한다(비활성 버튼 + 사유 표시용).
 */
@Serializable
public data class ActionOption(
    public val actionId: ActionId,
    public val cost: Int,
    public val affordable: Boolean,
    /** 대상이 필요 없는 행동이면 null. 필요하지만 지정 가능한 대상이 없으면 빈 집합. */
    public val validTargets: Set<PlayerId>?,
    /** 이 행동을 하려면 주장해야 하는 역할(이 중 하나). 비어 있으면 주장 불필요. */
    public val claimedRoles: Set<RoleId>,
    /** false면 블러핑(주장 역할을 들고 있지 않음). */
    public val iHoldClaimedRole: Boolean,
    /** 코인 임계값 때문에 이 행동만 허용되는 상황. */
    public val forcedOnly: Boolean,
) {
    /** 지금 실제로 선언 가능한지(비용·대상 조건 충족). */
    public val selectable: Boolean get() = affordable && (validTargets == null || validTargets.isNotEmpty())
}

/** 플레이어가 지금 내려야 하는 결정. 응답/공개/교환 타입은 5단계 이후 추가된다. */
@Serializable
public sealed interface DecisionRequest {
    @Serializable
    public data class ChooseAction(public val options: List<ActionOption>) : DecisionRequest

    @Serializable
    public data class ChooseInfluenceToLose(public val cards: List<Card>, public val reason: LossReason) : DecisionRequest
}
