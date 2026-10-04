package io.github.lhd980820.coup.engine.rules

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.ClassicRuleSet
import io.github.lhd980820.coup.engine.rules.effect.ActionEffect
import org.junit.jupiter.api.Test

class RuleSetValidatorTest {
    private val base = ClassicRuleSet.create()

    private fun codes(rs: RuleSet) = RuleSetValidator.validate(rs).map { it.code }

    private fun withRoles(roles: List<RoleDefinition>) = RuleSet(base.id, base.version, roles, base.actions, base.params)

    @Test
    fun `존재하지 않는 행동을 참조하면 실패`() {
        val broken = withRoles(base.roles + RoleDefinition(RoleId("ghost"), grantsActions = setOf(ActionId("nope"))))
        assertThat(codes(broken)).contains("UNKNOWN_ACTION_REF")
    }

    @Test
    fun `역할 ID 중복은 실패`() {
        assertThat(codes(withRoles(base.roles + base.roles.first()))).contains("DUPLICATE_ROLE")
    }

    @Test
    fun `덱이 너무 작으면 실패`() {
        val small = withRoles(base.roles.map { it.copy(copies = 1) })
        assertThat(codes(small)).contains("DECK_TOO_SMALL")
    }

    @Test
    fun `강제 행동이 없으면 실패`() {
        val noForced = RuleSet(base.id, base.version, base.roles, base.actions.map { it.copy(isForcedWhenRich = false) }, base.params)
        assertThat(codes(noForced)).contains("FORCED_ACTION_COUNT")
    }

    @Test
    fun `항상 둘 수 있는 안전한 행동이 없으면 실패`() {
        val noSafe = RuleSet(base.id, base.version, base.roles, base.actions.filterNot { it.id == ActionId("income") || it.id == ActionId("foreign_aid") }, base.params)
        assertThat(codes(noSafe)).contains("NO_SAFE_ACTION")
    }

    @Test
    fun `새 역할은 엔진 코드 변경 없이 정의만으로 추가된다`() {
        val invest = ActionDefinition(
            ActionId("invest"),
            targeting = Targeting.OtherAlivePlayer(),
            effect = ActionEffect { s, c ->
                s.gainCoins(c.actor, 3)
                s.transferCoins(c.actor, c.requireTarget(), 1)
            },
        )
        val banker = RoleDefinition(RoleId("banker"), grantsActions = setOf(invest.id), blocksActions = setOf(ActionId("steal")))
        val expanded = RuleSet(base.id, base.version, base.roles + banker, base.actions + invest, base.params)
        assertThat(RuleSetValidator.validate(expanded)).isEmpty()
        assertThat(expanded.rolesGranting(invest.id)).contains(banker.id)
        assertThat(expanded.rolesBlocking(ActionId("steal"))).contains(banker.id)
    }
}
