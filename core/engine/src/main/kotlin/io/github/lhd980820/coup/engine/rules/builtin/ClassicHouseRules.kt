package io.github.lhd980820.coup.engine.rules.builtin

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.rules.ActionDefinition
import io.github.lhd980820.coup.engine.rules.HouseRule
import io.github.lhd980820.coup.engine.rules.TargetFilter
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.rules.effect.ActionEffect

/** 기본 룰셋용 내장 하우스룰. 새 하우스룰은 여기에 정의하고 [BuiltinRules.registry]에 한 줄 등록한다. */
public object ClassicHouseRules {

    /** 기존 앱 동작 재현: 코인이 없는 플레이어는 강탈 대상으로 지정할 수 없다. */
    public val NO_STEAL_FROM_BROKE: HouseRule = HouseRule(
        id = "no_steal_from_broke",
        titleKey = "house_rule_no_steal_from_broke_title",
        descriptionKey = "house_rule_no_steal_from_broke_desc",
        compatibleBases = setOf(ClassicRuleSet.ID),
    ) { builder ->
        builder.modifyAction(ActionId("steal")) { steal ->
            steal.copy(targeting = Targeting.OtherAlivePlayer(filter = TargetFilter { target -> target.coins >= 1 }))
        }
    }

    /** 영향력이 1장 남은 플레이어는 수입으로 2코인을 얻는다. */
    public val LAST_STAND: HouseRule = HouseRule(
        id = "last_stand",
        titleKey = "house_rule_last_stand_title",
        descriptionKey = "house_rule_last_stand_desc",
        compatibleBases = setOf(ClassicRuleSet.ID),
    ) { builder ->
        builder.modifyAction(ActionId("income")) { income -> income.withEffect(wrapLastStand(income.effect)) }
    }

    private fun ActionDefinition.withEffect(effect: ActionEffect) = copy(effect = effect)

    private fun wrapLastStand(original: ActionEffect) = ActionEffect { scope, ctx ->
        if (ctx.influenceCountOf(ctx.actor) == 1) scope.gainCoins(ctx.actor, 2) else original.resolve(scope, ctx)
    }
}
