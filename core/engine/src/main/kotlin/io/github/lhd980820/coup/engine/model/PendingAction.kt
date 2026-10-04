package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.Serializable

/** 막기 선언: [blocker]가 [role]을 주장하며 막았다. */
@Serializable
public data class BlockClaim(public val blocker: PlayerId, public val role: RoleId)

/**
 * 현재 해결 중인 행동. 공개 정보다(누가 무엇을 어떤 역할로 주장했는지는 모두가 안다).
 * [claimedRoles]가 비어 있으면 주장 없는(도전 불가) 행동이다.
 */
@Serializable
public data class PendingAction(
    public val actor: PlayerId,
    public val actionId: ActionId,
    public val target: PlayerId?,
    public val claimedRoles: Set<RoleId>,
    public val costPaid: Int,
    public val blockedBy: BlockClaim? = null,
)
