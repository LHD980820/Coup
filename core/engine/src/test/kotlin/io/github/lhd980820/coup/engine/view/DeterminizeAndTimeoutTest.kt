package io.github.lhd980820.coup.engine.view

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.hiddenRoles
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.randomCommand
import io.github.lhd980820.coup.engine.testing.randomGame
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.testing.trueAssignment
import io.github.lhd980820.coup.engine.testing.viewersOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

class DeterminizeAndTimeoutTest {

    @Nested
    inner class 추정_상태 {
        @ParameterizedTest
        @ValueSource(ints = [2, 3, 5, 6])
        fun `정답 가정으로 만든 상태를 다시 투영하면 원래 뷰와 같다(모든 상태, 모든 시점)`(players: Int) {
            repeat(10) { game ->
                randomGame(seed = 500L + players * 10 + game, players = players) { state ->
                    viewersOf(state).forEach { viewer ->
                        val view = testEngine.view(state, viewer)
                        val det = testEngine.determinize(view, trueAssignment(state, viewer), seed = 1)
                        check(testEngine.view(det, viewer) == view) { "round trip differs for $viewer at v${state.version}" }
                    }
                }
            }
        }

        @Test
        fun `추정 상태는 끝까지 시뮬레이션할 수 있다`() {
            var checked = 0
            randomGame(seed = 42, players = 4) { state ->
                if (state.isOver || state.version % 7L != 3L) return@randomGame
                val viewer = Viewer.Player(state.seats.first { state.players.getValue(it).isAlive })
                var sim = testEngine.determinize(testEngine.view(state, viewer), trueAssignment(state, viewer), seed = state.version)
                val rnd = Random(state.version)
                var steps = 0
                while (!sim.isOver) {
                    check(++steps < 3000)
                    val who = testEngine.pendingDeciders(sim).random(rnd)
                    sim = (testEngine.apply(sim, randomCommand(sim, who, rnd)) as ApplyResult.Accepted).state
                }
                checked++
            }
            assertThat(checked > 0).isEqualTo(true)
        }

        @Test
        fun `다른 가정으로 만들어도 내 시점의 뷰는 같다`() {
            val state = scenario {
                player("me", "duke", "captain")
                player("x", "contessa", "assassin")
                player("y", "ambassador", "duke")
            }
            val view = testEngine.view(state, Viewer.Player(id("me")))
            val truth = trueAssignment(state, Viewer.Player(id("me")))
            // x와 y의 손패를 맞바꾼 가정(덱 구성은 그대로)
            val swapped = HiddenAssignment(
                hands = mapOf(id("x") to truth.hands.getValue(id("y")), id("y") to truth.hands.getValue(id("x"))),
                deckOrder = truth.deckOrder.reversed(),
            )
            val det = testEngine.determinize(view, swapped, seed = 3)
            assertThat(testEngine.view(det, Viewer.Player(id("me")))).isEqualTo(view)
            assertThat(det.hiddenRoles("x")).isEqualTo(listOf("ambassador", "duke"))
        }

        @Test
        fun `공개 정보와 모순되는 가정은 거절된다`() {
            val state = scenario {
                player("me", "duke", "captain")
                player("x", "contessa", "assassin")
            }
            val view = testEngine.view(state, Viewer.Player(id("me")))
            val truth = trueAssignment(state, Viewer.Player(id("me")))
            val wrongCount = truth.copy(hands = mapOf(id("x") to listOf(RoleId("contessa"))))
            val wrongDeck = truth.copy(deckOrder = truth.deckOrder.drop(1))
            val wrongComposition = truth.copy(hands = mapOf(id("x") to listOf(RoleId("duke"), RoleId("duke"))))
            val unknownPlayer = truth.copy(hands = truth.hands + (id("ghost") to emptyList()))
            listOf(wrongCount, wrongDeck, wrongComposition, unknownPlayer).forEach { bad ->
                assertThrows<IllegalArgumentException> { testEngine.determinize(view, bad, seed = 1) }
            }
        }
    }

    @Nested
    inner class 시간_초과 {
        private val table = scenario {
            player("a", "duke", "captain", coins = 3)
            player("b", "contessa", "assassin")
            player("c", "ambassador", "duke")
        }

        @Test
        fun `행동 대기 - 수입`() {
            val cmd = testEngine.timeoutCommand(table, id("a")) as Command.DeclareAction
            assertThat(cmd.actionId).isEqualTo(ActionId("income"))
            assertThat(cmd.expectedVersion).isEqualTo(table.version)
        }

        @Test
        fun `강제 상황 - 좌석 순서상 첫 대상에게 쿠`() {
            val rich = scenario {
                player("a", "duke", "captain", coins = 10)
                player("b", "contessa", "assassin", revealed = setOf(0, 1))
                player("c", "ambassador", "duke")
            }
            assertThat(testEngine.timeoutCommand(rich, id("a")))
                .isEqualTo(Command.DeclareAction(id("a"), ActionId("coup"), id("c"), 0))
        }

        @Test
        fun `응답 - 허용, 공개와 상실 - 첫 미공개 카드, 교환 - 현재 손패 유지`() {
            val window = table.declare("a", "tax").state
            assertThat(testEngine.timeoutCommand(window, id("b"))).isInstanceOf(Command.Pass::class)

            val reveal = window.accept(Command.Challenge(id("b"))).state
            val revealCmd = testEngine.timeoutCommand(reveal, id("a")) as Command.RevealCard
            assertThat(revealCmd.cardId).isEqualTo(reveal.players.getValue(id("a")).hiddenCards.first().id)

            val exchanging = table.declare("a", "exchange").state.accept(Command.Pass(id("b"))).state.accept(Command.Pass(id("c"))).state
            val keep = testEngine.timeoutCommand(exchanging, id("a")) as Command.ChooseExchange
            assertThat(keep.keep).isEqualTo(exchanging.players.getValue(id("a")).hiddenCards.map { it.id })
            val after = exchanging.accept(keep).state
            assertThat(after.hiddenRoles("a")).isEqualTo(listOf("duke", "captain"))
            assertThat(after.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `결정권자가 아니면 예외`() {
            assertThrows<IllegalArgumentException> { testEngine.timeoutCommand(table, id("b")) }
        }

        @ParameterizedTest
        @ValueSource(ints = [2, 4, 6])
        fun `무작위 게임의 모든 대기 상태에서 기본 명령은 항상 수락된다`(players: Int) {
            repeat(10) { game ->
                randomGame(seed = 900L + players * 10 + game, players = players) { state ->
                    testEngine.pendingDeciders(state).forEach { who ->
                        val result = testEngine.apply(state, testEngine.timeoutCommand(state, who))
                        check(result is ApplyResult.Accepted) { "timeout command for $who rejected: $result" }
                    }
                }
            }
        }
    }
}
