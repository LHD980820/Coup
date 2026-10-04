package io.github.lhd980820.coup.engine.rules

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.rules.effect.ActionEffect

public data class ActionDefinition(
    public val id: ActionId,
    public val cost: Int = 0,
    public val targeting: Targeting = Targeting.None,
    public val blockPolicy: BlockPolicy = BlockPolicy.NONE,
    public val effect: ActionEffect,
    /** 코인이 [RuleParams.forcedActionThreshold] 이상일 때 유일하게 허용되는 행동(쿠). */
    public val isForcedWhenRich: Boolean = false,
)
