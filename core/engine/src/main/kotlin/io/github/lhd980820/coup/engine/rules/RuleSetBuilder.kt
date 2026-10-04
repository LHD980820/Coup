package io.github.lhd980820.coup.engine.rules

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

/**
 * 하우스룰이 기반 룰셋을 변형할 때 쓰는 유일한 도구. 엔진 코어를 건드리지 않고 역할·행동·파라미터를 추가/교체/제거한다.
 * 효과 변형은 [modifyAction]으로 기존 `effect`를 감싸면 된다(별도 훅 타입 없음).
 */
public class RuleSetBuilder internal constructor(base: RuleSet) {
    private val id = base.id
    private val version = base.version
    private val roles = base.roles.toMutableList()
    private val actions = base.actions.toMutableList()
    private var params = base.params

    public fun addRole(role: RoleDefinition) {
        require(roles.none { it.id == role.id }) { "role already exists: ${role.id.value}" }
        roles += role
    }

    /** 같은 자리에서 [oldId] 역할을 [role]로 바꾼다(덱 구성 순서 유지). */
    public fun replaceRole(oldId: RoleId, role: RoleDefinition) {
        val index = roles.indexOfFirst { it.id == oldId }
        require(index >= 0) { "unknown role: ${oldId.value}" }
        roles[index] = role
    }

    public fun removeRole(id: RoleId) {
        require(roles.removeAll { it.id == id }) { "unknown role: ${id.value}" }
    }

    public fun addAction(action: ActionDefinition) {
        require(actions.none { it.id == action.id }) { "action already exists: ${action.id.value}" }
        actions += action
    }

    public fun modifyAction(id: ActionId, transform: (ActionDefinition) -> ActionDefinition) {
        val index = actions.indexOfFirst { it.id == id }
        require(index >= 0) { "unknown action: ${id.value}" }
        actions[index] = transform(actions[index])
    }

    /** 행동을 제거하고, 역할 정의에서 그 행동을 가리키는 참조(주장/막기)도 함께 제거한다. */
    public fun removeAction(id: ActionId) {
        require(actions.removeAll { it.id == id }) { "unknown action: ${id.value}" }
        for (i in roles.indices) {
            val r = roles[i]
            roles[i] = r.copy(grantsActions = r.grantsActions - id, blocksActions = r.blocksActions - id)
        }
    }

    public fun updateParams(transform: (RuleParams) -> RuleParams) {
        params = transform(params)
    }

    internal fun build(): RuleSet = RuleSet(id, version, roles.toList(), actions.toList(), params)
}

/**
 * 토글 가능한 변형 룰. [compatibleBases]가 비어 있으면 모든 기반 룰셋과 호환된다.
 * [apply]는 결정적이어야 한다(모든 참가자가 같은 [RuleSetConfig]로 같은 룰셋을 만들어야 한다).
 */
public class HouseRule(
    public val id: String,
    public val titleKey: String,
    public val descriptionKey: String,
    public val compatibleBases: Set<String> = emptySet(),
    public val apply: (RuleSetBuilder) -> Unit,
)
