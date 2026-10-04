package io.github.lhd980820.coup.engine.rules

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.ClassicRuleSet
import io.github.lhd980820.coup.engine.rules.effect.EffectContext
import io.github.lhd980820.coup.engine.rules.effect.Primitive
import io.github.lhd980820.coup.engine.rules.effect.plan
import org.junit.jupiter.api.Test

class ClassicRuleSetTest {
    private val rules = ClassicRuleSet.create()
    private val actor = PlayerId("a")
    private val target = PlayerId("t")

    private fun ctx(target: PlayerId? = null) =
        EffectContext(actor, target, rules.params, coins = { 5 }, influences = { 2 })

    private fun effectOf(id: String) = checkNotNull(rules.action(ActionId(id))).effect.plan(ctx(target))

    @Test
    fun `기본 룰셋은 검증기를 통과한다`() {
        assertThat(RuleSetValidator.validate(rules)).isEmpty()
    }

    @Test
    fun `덱은 5역할 x 3장 = 15장`() {
        assertThat(rules.totalCards).isEqualTo(15)
    }

    @Test
    fun `행동을 주장할 수 있는 역할이 정의에서 파생된다`() {
        assertThat(rules.rolesGranting(ActionId("tax"))).containsExactlyInAnyOrder(RoleId("duke"))
        assertThat(rules.rolesGranting(ActionId("income"))).isEmpty()
        assertThat(rules.rolesGranting(ActionId("coup"))).isEmpty()
    }

    @Test
    fun `강탈은 사령관과 외교관이 막을 수 있다`() {
        assertThat(rules.rolesBlocking(ActionId("steal")))
            .containsExactlyInAnyOrder(RoleId("captain"), RoleId("ambassador"))
        assertThat(rules.rolesBlocking(ActionId("assassinate"))).containsExactlyInAnyOrder(RoleId("contessa"))
        assertThat(rules.rolesBlocking(ActionId("foreign_aid"))).containsExactlyInAnyOrder(RoleId("duke"))
    }

    @Test
    fun `쿠가 강제 행동이다`() {
        assertThat(rules.forcedAction?.id).isEqualTo(ActionId("coup"))
    }

    @Test
    fun `효과는 동작 목록으로 기록될 뿐 상태를 바꾸지 않는다`() {
        assertThat(effectOf("income")).isEqualTo(listOf(Primitive.GainCoins(actor, 1)))
        assertThat(effectOf("tax")).isEqualTo(listOf(Primitive.GainCoins(actor, 3)))
        assertThat(effectOf("steal")).isEqualTo(listOf(Primitive.TransferCoins(target, actor, 2)))
        assertThat(effectOf("assassinate")).isEqualTo(listOf(Primitive.LoseInfluence(target)))
        assertThat(effectOf("exchange")).isEqualTo(listOf(Primitive.Exchange(actor, 2)))
    }
}
