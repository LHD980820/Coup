package io.github.lhd980820.coup.engine.rules

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

public data class RoleDefinition(
    public val id: RoleId,
    /** 덱에 들어가는 장수. */
    public val copies: Int = 3,
    /** 이 역할을 주장(클레임)해 수행할 수 있는 행동. */
    public val grantsActions: Set<ActionId> = emptySet(),
    /** 이 역할을 주장해 막을 수 있는 행동. */
    public val blocksActions: Set<ActionId> = emptySet(),
    public val tags: Set<String> = emptySet(),
)
