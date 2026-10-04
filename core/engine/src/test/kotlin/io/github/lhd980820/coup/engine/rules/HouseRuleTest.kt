package io.github.lhd980820.coup.engine.rules

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.testing.TestRules
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.coinsOf
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.hiddenRoles
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.reject
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HouseRuleTest {

    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }


    private fun validTargets(state: GameState, actor: String, action: String): Set<String>? =
        (testEngine.legalOptions(state, id(actor)) as DecisionRequest.ChooseAction).options
            .first { it.actionId.value == action }.validTargets?.map { it.value }?.toSet()

    @Nested
    inner class 강탈_대상_제한 {
        private fun table(vararg houseRules: String) = scenario(TestRules.config(*houseRules)) {
            player("a", "captain", "duke", coins = 2)
            player("b", "contessa", "assassin", coins = 0)
            player("c", "ambassador", "duke", coins = 3)
        }

        @Test
        fun `기본 룰에서는 코인 0인 대상도 지정하고 0을 강탈한다`() {
            val s = table()
            assertThat(validTargets(s, "a", "steal")).isEqualTo(setOf("b", "c"))
            val done = s.declare("a", "steal", "b").state.pass("b", "c")
            assertThat(done.coinsOf("a")).isEqualTo(2)
        }

        @Test
        fun `하우스룰을 켜면 코인 0인 대상은 목록에서 빠지고 지정하면 거절된다`() {
            val s = table("no_steal_from_broke")
            assertThat(validTargets(s, "a", "steal")).isEqualTo(setOf("c"))
            assertThat(s.reject(Command.DeclareAction(id("a"), ActionId("steal"), id("b")))).isEqualTo(Rejection.INVALID_TARGET)
            assertThat(s.declare("a", "steal", "c").state.pass("b", "c").coinsOf("a")).isEqualTo(4)
        }

        @Test
        fun `대상 후보가 없으면 선택 불가로 표시된다`() {
            val s = scenario(TestRules.config("no_steal_from_broke")) {
                player("a", "captain", "duke")
                player("b", "contessa", "assassin", coins = 0)
            }
            val steal = (testEngine.legalOptions(s, id("a")) as DecisionRequest.ChooseAction).options.first { it.actionId.value == "steal" }
            assertThat(steal.validTargets).isEqualTo(emptySet())
            assertThat(steal.selectable).isEqualTo(false)
        }
    }

    @Nested
    inner class 최후의_저항 {
        @Test
        fun `영향력이 1장이면 수입이 2코인, 2장이면 1코인`() {
            val s = scenario(TestRules.config("last_stand")) {
                player("a", "duke", "captain", coins = 2, revealed = setOf(0))
                player("b", "contessa", "assassin", coins = 2)
            }
            assertThat(s.declare("a", "income").state.coinsOf("a")).isEqualTo(4)

            val healthy = scenario(TestRules.config("last_stand")) {
                player("a", "duke", "captain", coins = 2)
                player("b", "contessa", "assassin", coins = 2)
            }
            assertThat(healthy.declare("a", "income").state.coinsOf("a")).isEqualTo(3)
        }

        @Test
        fun `끄면 영향력과 무관하게 수입은 1코인`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 2, revealed = setOf(0))
                player("b", "contessa", "assassin", coins = 2)
            }
            assertThat(s.declare("a", "income").state.coinsOf("a")).isEqualTo(3)
        }
    }

    @Nested
    inner class 파라미터_오버라이드 {
        @Test
        fun `강제 쿠 임계값을 8로 낮추면 8코인에서 강제된다`() {
            val s = scenario(TestRules.config(overrides = mapOf("forcedActionThreshold" to "8"))) {
                player("a", "duke", "captain", coins = 8)
                player("b", "contessa", "assassin")
            }
            assertThat(s.reject(Command.DeclareAction(id("a"), ActionId("income")))).isEqualTo(Rejection.FORCED_ACTION_REQUIRED)
        }

        @Test
        fun `시작 코인과 환불 파라미터가 적용된다`() {
            val state = testEngine.newGame(
                GameSetup("g", listOf(id("a"), id("b")), TestRules.config(overrides = mapOf("startingCoins" to "5")), 1),
            )
            assertThat(state.coinsOf("a")).isEqualTo(5)
        }
    }

    @Nested
    inner class 레지스트리 {
        @Test
        fun `기본 룰셋에서 쓸 수 있는 하우스룰 목록`() {
            val ids = TestRules.registry().availableHouseRules("classic").map { it.id }
            assertThat(ids).contains("no_steal_from_broke")
            assertThat(ids).contains("last_stand")
            assertThat(TestRules.registry().availableHouseRules("other_base")).isEmpty()
        }

        @Test
        fun `알 수 없거나 호환되지 않는 하우스룰은 거절된다`() {
            val registry = TestRules.registry()
            assertThrows<IllegalArgumentException> { registry.build(TestRules.config("nope")) }
            registry.registerBase("other", 1) { io.github.lhd980820.coup.engine.rules.builtin.ClassicRuleSet.create() }
            assertThrows<IllegalArgumentException> {
                registry.build(RuleSetConfig("other", 1, houseRules = setOf("no_steal_from_broke")))
            }
        }

        @Test
        fun `같은 설정은 하우스룰 순서와 무관하게 같은 룰셋을 만든다`() {
            val registry = TestRules.registry()
            val a = registry.build(TestRules.config("last_stand", "no_steal_from_broke"))
            val b = registry.build(TestRules.config("no_steal_from_broke", "last_stand"))
            assertThat(a.actions.map { it.id }).isEqualTo(b.actions.map { it.id })
            assertThat(a.params).isEqualTo(b.params)
        }

        @Test
        fun `하우스룰 결과가 검증에 실패하면 거절된다`() {
            val registry = TestRules.registry()
            registry.registerHouseRule(
                HouseRule("breaks_deck", "t", "t") { it.updateParams { p -> p.copy(maxPlayers = 12) } },
            )
            assertThrows<IllegalArgumentException> { registry.build(TestRules.config("breaks_deck")) }
        }

        @Test
        fun `중복 등록은 거절된다`() {
            val registry = TestRules.registry()
            assertThrows<IllegalArgumentException> { registry.registerHouseRule(TestRules.BANKER_REPLACES_AMBASSADOR) }
        }
    }

    @Nested
    inner class 은행가_확장 {
        private val banker = TestRules.config("banker_replaces_ambassador")

        @Test
        fun `역할이 교체되고 행동이 바뀌며 파생 관계가 자동으로 따라온다`() {
            val rules = TestRules.registry().build(banker)
            assertThat(rules.roles.map { it.id.value }).containsExactly("duke", "assassin", "captain", "banker", "contessa")
            assertThat(rules.action(ActionId("exchange")) == null).isEqualTo(true)
            assertThat(rules.rolesGranting(TestRules.INVEST)).isEqualTo(setOf(TestRules.BANKER))
            assertThat(rules.rolesBlocking(ActionId("steal"))).isEqualTo(setOf(RoleId("captain"), TestRules.BANKER))
            assertThat(rules.totalCards).isEqualTo(15)
        }

        @Test
        fun `투자는 도전 가능하고 통과하면 3코인을 얻고 대상에게 1코인을 준다`() {
            val s = scenario(banker) {
                player("a", "banker", "duke", coins = 2)
                player("b", "contessa", "assassin", coins = 2)
            }
            val window = s.declare("a", "invest", "b").state
            assertThat((testEngine.legalOptions(window, id("b")) as DecisionRequest.Respond).canChallenge).isEqualTo(true)
            val done = window.pass("b")
            assertThat(done.coinsOf("a")).isEqualTo(4)
            assertThat(done.coinsOf("b")).isEqualTo(3)
        }

        @Test
        fun `은행가로 강탈을 막는 선택지가 자동으로 생긴다`() {
            val s = scenario(banker) {
                player("a", "captain", "duke", coins = 2)
                player("b", "banker", "assassin", coins = 2)
            }
            val respond = testEngine.legalOptions(s.declare("a", "steal", "b").state, id("b")) as DecisionRequest.Respond
            assertThat(respond.blockOptions.map { it.role.value }).containsExactly("captain", "banker")
            assertThat(respond.blockOptions.first { it.role.value == "banker" }.iHoldRole).isEqualTo(true)
        }

        @Test
        fun `투자를 블러핑하다 도전받으면 그 카드를 잃는다`() {
            val s = scenario(banker) {
                player("a", "duke", "captain", coins = 2)
                player("b", "contessa", "assassin")
            }
            val challenged = s.declare("a", "invest", "b").state.accept(Command.Challenge(id("b"))).state
            val dukeId = challenged.players.getValue(id("a")).hiddenCards.first { it.role.value == "duke" }.id
            val state = challenged.accept(Command.RevealCard(id("a"), dukeId)).state
            assertThat(state.hiddenRoles("a")).containsExactly("captain")
            assertThat(state.coinsOf("a")).isEqualTo(2)
        }
    }
}
