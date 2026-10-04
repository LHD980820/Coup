package io.github.lhd980820.coup.engine.rules.builtin

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.ActionDefinition
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.rules.RoleDefinition
import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.Targeting
import io.github.lhd980820.coup.engine.rules.effect.ActionEffect

/** 기본 5역할 룰셋. 역할/행동 이름 리터럴은 이 파일(내장 룰셋 정의)에만 존재해야 한다. */
public object ClassicRuleSet {
    public const val ID: String = "classic"
    public const val VERSION: Int = 1

    private val duke = RoleId("duke")
    private val assassin = RoleId("assassin")
    private val captain = RoleId("captain")
    private val ambassador = RoleId("ambassador")
    private val contessa = RoleId("contessa")

    private val income = ActionId("income")
    private val foreignAid = ActionId("foreign_aid")
    private val coup = ActionId("coup")
    private val tax = ActionId("tax")
    private val assassinate = ActionId("assassinate")
    private val steal = ActionId("steal")
    private val exchange = ActionId("exchange")

    public fun create(): RuleSet = RuleSet(
        id = ID,
        version = VERSION,
        roles = listOf(
            RoleDefinition(duke, grantsActions = setOf(tax), blocksActions = setOf(foreignAid)),
            RoleDefinition(assassin, grantsActions = setOf(assassinate)),
            RoleDefinition(captain, grantsActions = setOf(steal), blocksActions = setOf(steal)),
            RoleDefinition(ambassador, grantsActions = setOf(exchange), blocksActions = setOf(steal)),
            RoleDefinition(contessa, blocksActions = setOf(assassinate)),
        ),
        actions = listOf(
            ActionDefinition(income, effect = ActionEffect { s, c -> s.gainCoins(c.actor, 1) }),
            ActionDefinition(
                foreignAid,
                blockPolicy = BlockPolicy.ANY_OTHER_PLAYER,
                effect = ActionEffect { s, c -> s.gainCoins(c.actor, 2) },
            ),
            ActionDefinition(
                coup,
                cost = 7,
                targeting = Targeting.OtherAlivePlayer(),
                isForcedWhenRich = true,
                effect = ActionEffect { s, c -> s.loseInfluence(c.requireTarget()) },
            ),
            ActionDefinition(tax, effect = ActionEffect { s, c -> s.gainCoins(c.actor, 3) }),
            ActionDefinition(
                assassinate,
                cost = 3,
                targeting = Targeting.OtherAlivePlayer(),
                blockPolicy = BlockPolicy.TARGET_ONLY,
                effect = ActionEffect { s, c -> s.loseInfluence(c.requireTarget()) },
            ),
            ActionDefinition(
                steal,
                targeting = Targeting.OtherAlivePlayer(),
                blockPolicy = BlockPolicy.TARGET_ONLY,
                effect = ActionEffect { s, c -> s.transferCoins(from = c.requireTarget(), to = c.actor, max = 2) },
            ),
            ActionDefinition(exchange, effect = ActionEffect { s, c -> s.exchange(c.actor, drawCount = 2) }),
        ),
    )
}
