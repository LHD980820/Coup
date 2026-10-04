package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.allCardIds
import io.github.lhd980820.coup.engine.testing.coinsOf
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.hiddenRoles
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.only
import io.github.lhd980820.coup.engine.testing.reject
import io.github.lhd980820.coup.engine.testing.revealedRoles
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConcedeTest {

    private fun GameState.concede(player: String) = accept(Command.Concede(id(player)))
    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }
    private fun GameState.cardOf(player: String, role: String): CardId =
        players.getValue(id(player)).hiddenCards.first { it.role.value == role }.id
    private fun GameState.window() = (phase as Phase.AwaitingResponses).window

    private val table = scenario {
        player("a", "duke", "captain", coins = 3)
        player("b", "contessa", "assassin", coins = 4)
        player("c", "ambassador", "duke", coins = 2)
        player("d", "captain", "assassin", coins = 2)
    }

    @Test
    fun `기권하면 남은 카드를 모두 잃고 탈락한다`() {
        val (state, events) = table.concede("c")
        assertThat(state.players.getValue(id("c")).isAlive).isFalse()
        assertThat(state.revealedRoles("c")).containsExactlyInAnyOrder("ambassador", "duke")
        assertThat(state.eliminationOrder).containsExactly(id("c"))
        assertThat(events.only<GameEvent.InfluenceLost>().map { it.reason }.toSet()).isEqualTo(setOf(LossReason.Concede))
        assertThat(events.first()).isEqualTo(GameEvent.PlayerConceded(id("c")))
        assertThat(state.allCardIds()).hasSize(15)
    }

    @Test
    fun `결정권이 없어도 기권할 수 있고, 진행 중인 결정은 그대로 유지된다`() {
        val (state, _) = table.concede("c")
        assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("a")))
        assertThat(state.version).isEqualTo(1L)
    }

    @Test
    fun `탈락자는 기권할 수 없다`() {
        val state = table.concede("c").state
        assertThat(state.reject(Command.Concede(id("c")))).isEqualTo(Rejection.PLAYER_ELIMINATED)
    }

    @Test
    fun `2인전에서 기권하면 상대가 승리한다`() {
        val s = scenario {
            player("a", "duke", "captain")
            player("b", "contessa", "assassin")
        }
        val state = s.concede("a").state
        assertThat(state.isOver).isTrue()
        assertThat((state.phase as Phase.GameOver).ranking).containsExactly(id("b"), id("a"))
    }

    @Nested
    inner class 자기_턴 {
        @Test
        fun `자기 턴에 기권하면 다음 생존자에게 턴이 간다`() {
            val (state, events) = table.concede("a")
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.turn.number).isEqualTo(2)
            assertThat(events.only<GameEvent.TurnStarted>()).hasSize(1)
        }
    }

    @Nested
    inner class 응답_창 {
        @Test
        fun `응답자가 기권하면 창에서 빠지고 남은 사람만 기다린다`() {
            val state = table.declare("a", "tax").state.concede("b").state
            assertThat(state.window().eligible).isEqualTo(setOf(id("c"), id("d")))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("c"), id("d")))
        }

        @Test
        fun `마지막 대기자가 기권하면 전원 통과로 처리되어 행동이 해결된다`() {
            val state = table.declare("a", "tax").state.pass("b", "c").concede("d").state
            assertThat(state.coinsOf("a")).isEqualTo(6)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `이미 통과한 사람이 기권해도 다른 대기자는 그대로`() {
            val state = table.declare("a", "tax").state.pass("b").concede("b").state
            assertThat(state.window().waitingOn).isEqualTo(setOf(id("c"), id("d")))
        }

        @Test
        fun `행위자가 기권하면 행동이 취소되고 다음 생존자 턴`() {
            val (state, events) = table.declare("a", "tax").state.pass("b").concede("a")
            assertThat(state.coinsOf("a")).isEqualTo(3)
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.currentAction).isEqualTo(null)
        }

        @Test
        fun `강탈 대상이 기권하면 강탈은 무효(fizzle)`() {
            val state = table.declare("a", "steal", "b").state.concede("b")
            assertThat(state.events.only<GameEvent.ActionResolved>()).isEmpty() // 다른 응답자(c, d)를 아직 기다린다
            val done = state.state.pass("c", "d")
            assertThat(done.coinsOf("a")).isEqualTo(3)
            assertThat(done.phase).isEqualTo(Phase.AwaitingAction(id("c")))
        }

        @Test
        fun `막기 도전 창에서 막은 사람이 기권하면 막기는 무효, 행동이 해결된다`() {
            val blocked = table.declare("a", "foreign_aid").state.accept(Command.Block(id("c"), RoleId("duke"))).state
            assertThat(blocked.window().kind).isEqualTo(WindowKind.BLOCK_CHALLENGE)
            val (state, events) = blocked.concede("c")
            assertThat(state.coinsOf("a")).isEqualTo(5)
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.SUCCESS)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `막기 도전 창에서 다른 응답자가 기권하면 창에서 빠진다`() {
            val blocked = table.declare("a", "foreign_aid").state.accept(Command.Block(id("c"), RoleId("duke"))).state
            val state = blocked.concede("d").state
            assertThat(state.window().eligible).isEqualTo(setOf(id("a"), id("b")))
        }
    }

    @Nested
    inner class 공개_대기 {
        @Test
        fun `도전받은 행위자가 공개하지 않고 기권하면 행동 취소, 도전자는 잃지 않는다`() {
            val challenged = table.declare("a", "tax").state.accept(Command.Challenge(id("b"))).state
            val (state, events) = challenged.concede("a")
            assertThat(state.revealedRoles("b")).isEmpty()
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `도전받은 막은 사람이 기권하면 막기 무효, 행동이 해결된다`() {
            val challenged = table.declare("a", "foreign_aid").state
                .accept(Command.Block(id("c"), RoleId("duke"))).state
                .accept(Command.Challenge(id("a"))).state
            val state = challenged.concede("c").state
            assertThat(state.coinsOf("a")).isEqualTo(5)
            assertThat(state.revealedRoles("a")).isEmpty()
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `도전자가 기권해도 도전받은 사람의 공개는 계속된다`() {
            val challenged = table.declare("a", "tax").state.accept(Command.Challenge(id("b"))).state
            val state = challenged.concede("b").state
            assertThat(state.phase is Phase.AwaitingReveal).isTrue()
            val done = state.accept(Command.RevealCard(id("a"), state.cardOf("a", "duke"))).state
            assertThat(done.coinsOf("a")).isEqualTo(6)
            assertThat(done.phase).isEqualTo(Phase.AwaitingAction(id("c"))) // b는 탈락했으므로 건너뜀
        }
    }

    @Nested
    inner class 상실_대기 {
        @Test
        fun `잃을 카드를 고르던 쿠 대상이 기권하면 진행이 이어진다`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin")
                player("c", "ambassador", "duke")
            }
            val awaiting = s.declare("a", "coup", "b").state
            val state = awaiting.concede("b").state
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("c")))
            assertThat(state.revealedRoles("b")).hasSize(2)
        }

        @Test
        fun `다른 사람이 잃을 카드를 고르는 중에 제3자가 기권해도 그 결정은 건너뛰지 않는다`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin")
                player("c", "ambassador", "duke")
                player("d", "captain", "assassin")
            }
            val awaiting = s.declare("a", "coup", "b").state
            val state = awaiting.concede("d").state
            assertThat(state.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ActionEffect(ActionId("coup"))))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("b")))
            assertThat(state.stack).isEqualTo(awaiting.stack)
        }

        @Test
        fun `증명에 진 도전자가 고르는 중에 행위자가 기권하면 도전자는 여전히 잃고 행동은 취소된다`() {
            val proven = table.declare("a", "steal", "b").state
                .accept(Command.Challenge(id("b"))).state
                .accept(Command.RevealCard(id("a"), table.cardOf("a", "captain"))).state
            assertThat(proven.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ChallengeLost))
            val afterConcede = proven.concede("a").state
            assertThat(afterConcede.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ChallengeLost))
            val (state, events) = afterConcede.accept(Command.LoseInfluence(id("b"), afterConcede.cardOf("b", "assassin")))
            assertThat(state.revealedRoles("b")).containsExactly("assassin")
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
            assertThat(state.coinsOf("b")).isEqualTo(4)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }
    }

    @Nested
    inner class 교환_대기 {
        @Test
        fun `교환 중 기권하면 덱이 그대로 유지되고 턴이 넘어간다`() {
            val s = scenario {
                player("a", "ambassador", "duke")
                player("b", "contessa", "assassin")
                player("c", "captain", "duke")
            }
            val awaiting = s.declare("a", "exchange").state.pass("b", "c")
            val deck = awaiting.deck
            val state = awaiting.concede("a").state
            assertThat(state.deck).isEqualTo(deck)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.hiddenRoles("a")).isEmpty()
            assertThat(state.allCardIds()).hasSize(15)
        }

        @Test
        fun `교환 중 제3자가 기권해도 교환 선택은 유지된다`() {
            val s = scenario {
                player("a", "ambassador", "duke")
                player("b", "contessa", "assassin")
                player("c", "captain", "duke")
            }
            val awaiting = s.declare("a", "exchange").state.pass("b", "c")
            val state = awaiting.concede("c").state
            assertThat(state.phase is Phase.AwaitingExchange).isTrue()
            assertThat(state.stack).isEqualTo(awaiting.stack)
        }
    }
}
