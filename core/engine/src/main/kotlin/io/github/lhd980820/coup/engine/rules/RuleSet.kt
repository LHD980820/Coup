package io.github.lhd980820.coup.engine.rules

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

/**
 * 역할·행동·파라미터의 묶음. "어떤 역할이 어떤 행동을 주장/차단하는가"는 역할 정의에서 파생되며,
 * 엔진 코어는 이 인덱스로만 조회한다(특정 역할 이름을 알지 못한다).
 */
public class RuleSet(
    public val id: String,
    public val version: Int,
    public val roles: List<RoleDefinition>,
    public val actions: List<ActionDefinition>,
    public val params: RuleParams = RuleParams(),
) {
    private val rolesById: Map<RoleId, RoleDefinition> = roles.associateBy { it.id }
    private val actionsById: Map<ActionId, ActionDefinition> = actions.associateBy { it.id }

    private val granting: Map<ActionId, Set<RoleId>> =
        actions.associate { action -> action.id to roles.filter { action.id in it.grantsActions }.map { it.id }.toSet() }
    private val blocking: Map<ActionId, Set<RoleId>> =
        actions.associate { action -> action.id to roles.filter { action.id in it.blocksActions }.map { it.id }.toSet() }

    public fun role(id: RoleId): RoleDefinition? = rolesById[id]

    public fun action(id: ActionId): ActionDefinition? = actionsById[id]

    /** 이 행동을 주장할 수 있는 역할들. 비어 있으면 주장이 필요 없는(도전 불가) 행동이다. */
    public fun rolesGranting(action: ActionId): Set<RoleId> = granting[action].orEmpty()

    /** 이 행동을 막을 수 있는 역할들. */
    public fun rolesBlocking(action: ActionId): Set<RoleId> = blocking[action].orEmpty()

    public val totalCards: Int get() = roles.sumOf { it.copies }

    /** 코인 임계값 이상일 때 강제되는 행동(없으면 null). */
    public val forcedAction: ActionDefinition? get() = actions.firstOrNull { it.isForcedWhenRich }
}
