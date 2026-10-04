package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.allCardIds
import io.github.lhd980820.coup.engine.testing.coinsOf
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.only
import io.github.lhd980820.coup.engine.testing.reject
import io.github.lhd980820.coup.engine.testing.revealedRoles
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.BlockOption
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResponseWindowTest {

    private val table = scenario {
        player("a", "duke", "captain", coins = 3)
        player("b", "contessa", "assassin", coins = 4)
        player("c", "ambassador", "duke", coins = 1)
    }

    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }

    private fun GameState.window() = (phase as Phase.AwaitingResponses).window

    private fun GameState.respond(player: String): DecisionRequest.Respond =
        testEngine.legalOptions(this, id(player)) as DecisionRequest.Respond

    @Nested
    inner class 세금 {
        @Test
        fun `주장이 있으므로 응답 창이 열리고 다른 모든 생존자가 도전만 할 수 있다`() {
            val (state, events) = table.declare("a", "tax")
            assertThat(state.window().kind).isEqualTo(WindowKind.ACTION)
            assertThat(state.window().eligible).isEqualTo(setOf(id("b"), id("c")))
            assertThat(state.window().allowed.getValue(id("b")).canChallenge).isTrue()
            assertThat(state.window().allowed.getValue(id("b")).blockRoles).isEmpty()
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("b"), id("c")))
            assertThat(state.coinsOf("a")).isEqualTo(3) // 아직 효과 전
            assertThat(events.only<GameEvent.ActionResolved>()).isEmpty()
        }

        @Test
        fun `행위자는 응답 결정이 없고 응답자는 Respond 결정을 받는다`() {
            val state = table.declare("a", "tax").state
            assertThat(testEngine.legalOptions(state, id("a"))).isNull()
            val respond = state.respond("b")
            assertThat(respond.canChallenge).isTrue()
            assertThat(respond.blockOptions).isEmpty()
            assertThat(respond.canPass).isTrue()
            assertThat(respond.pending.actionId).isEqualTo(ActionId("tax"))
            assertThat(respond.pending.claimedRoles).isEqualTo(setOf(RoleId("duke")))
        }

        @Test
        fun `일부만 통과하면 창이 유지되고 대기자가 줄어든다`() {
            val (state, events) = table.declare("a", "tax").state.accept(Command.Pass(id("b")))
            assertThat(state.window().waitingOn).isEqualTo(setOf(id("c")))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("c")))
            assertThat(testEngine.legalOptions(state, id("b"))).isNull()
            assertThat(events.only<GameEvent.Passed>().map { it.player }).containsExactly(id("b"))
            assertThat(state.version).isEqualTo(2L)
        }

        @Test
        fun `전원 통과하면 효과가 적용되고 턴이 넘어간다`() {
            val (state, events) = table.declare("a", "tax").state.accept(Command.Pass(id("b"))).state.accept(Command.Pass(id("c")))
            assertThat(state.coinsOf("a")).isEqualTo(6)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.currentAction).isNull()
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.SUCCESS)
        }

        @Test
        fun `이미 통과한 사람, 행위자, 응답 창에서의 행동 선언은 거절된다`() {
            val state = table.declare("a", "tax").state.accept(Command.Pass(id("b"))).state
            assertThat(state.reject(Command.Pass(id("b")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
            assertThat(state.reject(Command.Pass(id("a")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
            assertThat(state.reject(Command.DeclareAction(id("a"), ActionId("income")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
            assertThat(state.reject(Command.DeclareAction(id("c"), ActionId("income")))).isEqualTo(Rejection.WRONG_PHASE)
        }

        @Test
        fun `탈락자는 응답 대상에서 제외된다`() {
            val s = scenario {
                player("a", "duke", "captain")
                player("b", "contessa", "assassin")
                player("c", "ambassador", "duke", revealed = setOf(0, 1))
            }
            val state = s.declare("a", "tax").state
            assertThat(state.window().eligible).isEqualTo(setOf(id("b")))
            assertThat(state.pass("b").coinsOf("a")).isEqualTo(5)
        }

        @Test
        fun `2인 게임에서는 상대 한 명만 응답한다`() {
            val s = scenario {
                player("a", "duke", "captain")
                player("b", "contessa", "assassin")
            }
            val state = s.declare("a", "tax").state
            assertThat(state.window().eligible).isEqualTo(setOf(id("b")))
            assertThat(state.pass("b").phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }
    }

    @Nested
    inner class 해외원조 {
        @Test
        fun `주장이 없으므로 도전은 불가하고 모든 타인이 공작으로 막을 수 있다`() {
            val state = table.declare("a", "foreign_aid").state
            assertThat(state.respond("b").canChallenge).isFalse()
            assertThat(state.respond("b").blockOptions).containsExactly(BlockOption(RoleId("duke"), iHoldRole = false))
            assertThat(state.respond("c").blockOptions).containsExactly(BlockOption(RoleId("duke"), iHoldRole = true))
        }

        @Test
        fun `전원 통과하면 +2`() {
            val state = table.declare("a", "foreign_aid").state.pass("b", "c")
            assertThat(state.coinsOf("a")).isEqualTo(5)
        }
    }

    @Nested
    inner class 강탈 {
        @Test
        fun `대상은 도전과 두 역할로 막기, 비대상은 도전만 가능`() {
            val state = table.declare("a", "steal", "b").state
            val target = state.respond("b")
            assertThat(target.canChallenge).isTrue()
            assertThat(target.blockOptions.map { it.role.value }).containsExactly("captain", "ambassador")
            val bystander = state.respond("c")
            assertThat(bystander.canChallenge).isTrue()
            assertThat(bystander.blockOptions).isEmpty()
        }

        @Test
        fun `전원 통과하면 최대 2코인 강탈`() {
            val state = table.declare("a", "steal", "b").state.pass("b", "c")
            assertThat(state.coinsOf("a")).isEqualTo(5)
            assertThat(state.coinsOf("b")).isEqualTo(2)
        }

        @Test
        fun `대상 코인이 1이면 1만, 0이면 0 강탈`() {
            val one = table.declare("a", "steal", "c").state.pass("b", "c")
            assertThat(one.coinsOf("a")).isEqualTo(4)
            assertThat(one.coinsOf("c")).isEqualTo(0)

            val none = scenario {
                player("a", "duke", "captain", coins = 3)
                player("b", "contessa", "assassin", coins = 0)
            }.declare("a", "steal", "b").state.pass("b")
            assertThat(none.coinsOf("a")).isEqualTo(3)
            assertThat(none.coinsOf("b")).isEqualTo(0)
        }
    }

    @Nested
    inner class 암살 {
        @Test
        fun `선언 시 3코인을 지불하고 대상만 귀부인으로 막을 수 있다`() {
            val state = table.declare("a", "assassinate", "b").state
            assertThat(state.coinsOf("a")).isEqualTo(0)
            assertThat(state.respond("b").blockOptions).containsExactly(BlockOption(RoleId("contessa"), iHoldRole = true))
            assertThat(state.respond("c").blockOptions).isEmpty()
            assertThat(state.respond("c").canChallenge).isTrue()
        }

        @Test
        fun `전원 통과하면 대상이 잃을 카드를 고르고 그 뒤 턴이 넘어간다`() {
            val state = table.declare("a", "assassinate", "b").state.pass("b", "c")
            assertThat(state.phase).isEqualTo(
                Phase.AwaitingInfluenceLoss(id("b"), LossReason.ActionEffect(ActionId("assassinate"))),
            )
            assertThat(state.currentAction).isNotNull()
            val card = state.players.getValue(id("b")).hiddenCards.first { it.role.value == "contessa" }
            val next = state.accept(Command.LoseInfluence(id("b"), card.id)).state
            assertThat(next.revealedRoles("b")).containsExactly("contessa")
            assertThat(next.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(next.currentAction).isNull()
        }

        @Test
        fun `대상의 카드가 1장이면 자동으로 잃고 탈락`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 3)
                player("b", "contessa", "assassin", revealed = setOf(0))
                player("c", "ambassador", "duke")
            }
            val state = s.declare("a", "assassinate", "b").state.pass("b", "c")
            assertThat(state.eliminationOrder).containsExactly(id("b"))
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("c")))
        }

        @Test
        fun `2인 게임에서 마지막 영향력을 암살하면 게임 종료`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 3)
                player("b", "contessa", "assassin", revealed = setOf(0))
            }
            val state = s.declare("a", "assassinate", "b").state.pass("b")
            assertThat(state.isOver).isTrue()
            assertThat(state.phase).isInstanceOf(Phase.GameOver::class)
        }
    }

    @Test
    fun `응답 창을 거친 뒤에도 카드 총량이 보존된다`() {
        val state = table.declare("a", "assassinate", "b").state.pass("b", "c")
        assertThat(state.allCardIds()).hasSize(15)
    }

    @Test
    fun `도전과 막기 명령은 6~7단계 전까지 거절된다`() {
        val state = table.declare("a", "tax").state
        assertThat(state.reject(Command.Challenge(id("b")))).isEqualTo(Rejection.WRONG_PHASE)
        assertThat(state.reject(Command.Block(id("b"), RoleId("duke")))).isEqualTo(Rejection.WRONG_PHASE)
    }
}
