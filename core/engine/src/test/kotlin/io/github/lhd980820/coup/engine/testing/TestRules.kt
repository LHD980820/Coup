package io.github.lhd980820.coup.engine.testing

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.ActionDefinition
import io.github.lhd980820.coup.engine.rules.HouseRule
import io.github.lhd980820.coup.engine.rules.RoleDefinition
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.rules.builtin.ClassicRuleSet
import io.github.lhd980820.coup.engine.rules.effect.ActionEffect

/**
 * 테스트 전용 확장: 가상 역할 "은행가"를 하우스룰 정의만으로 추가한다(설계 §5.3).
 * 엔진 코어를 수정하지 않고도 새 역할이 끝까지 플레이된다는 것을 지속적으로 증명한다.
 */
object TestRules {
    val INVEST = ActionId("invest")
    val BANKER = RoleId("banker")

    val BANKER_REPLACES_AMBASSADOR = HouseRule(
        id = "banker_replaces_ambassador",
        titleKey = "test",
        descriptionKey = "test",
        compatibleBases = setOf(ClassicRuleSet.ID),
    ) { b ->
        b.replaceRole(RoleId("ambassador"), RoleDefinition(BANKER, grantsActions = setOf(INVEST), blocksActions = setOf(ActionId("steal"))))
        b.removeAction(ActionId("exchange"))
        b.addAction(
            ActionDefinition(
                INVEST,
                targeting = Targeting.OtherAlivePlayer(),
                effect = ActionEffect { s, c ->
                    s.gainCoins(c.actor, 3)
                    s.transferCoins(c.actor, c.requireTarget(), 1)
                },
            ),
        )
    }

    fun registry(): RuleSetRegistry = BuiltinRules.registry().apply { registerHouseRule(BANKER_REPLACES_AMBASSADOR) }

    fun config(vararg houseRules: String, overrides: Map<String, String> = emptyMap()): RuleSetConfig =
        BuiltinRules.classicConfig().copy(houseRules = houseRules.toSet(), paramOverrides = overrides)

    /** 무작위 완주 테스트가 돌리는 하우스룰 조합. */
    val combos: List<RuleSetConfig> = listOf(
        config(),
        config("no_steal_from_broke"),
        config("last_stand"),
        config("no_steal_from_broke", "last_stand"),
        config("banker_replaces_ambassador"),
        config("banker_replaces_ambassador", "no_steal_from_broke", "last_stand", overrides = mapOf("forcedActionThreshold" to "8")),
    )
}
