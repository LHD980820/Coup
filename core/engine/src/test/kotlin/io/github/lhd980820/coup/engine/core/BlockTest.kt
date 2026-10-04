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
import io.github.lhd980820.coup.engine.model.BlockClaim
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.ChallengeContext
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
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BlockTest {

    private fun GameState.block(by: String, role: String) = accept(Command.Block(id(by), RoleId(role)))
    private fun GameState.challenge(by: String) = accept(Command.Challenge(id(by)))
    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }
    private fun GameState.cardOf(player: String, role: String): CardId =
        players.getValue(id(player)).hiddenCards.first { it.role.value == role }.id
    private fun GameState.revealRole(player: String, role: String) = accept(Command.RevealCard(id(player), cardOf(player, role)))
    private fun GameState.lose(player: String, role: String) = accept(Command.LoseInfluence(id(player), cardOf(player, role)))
    private fun GameState.window() = (phase as Phase.AwaitingResponses).window

    private val table = scenario {
        player("a", "assassin", "captain", coins = 3)
        player("b", "contessa", "duke", coins = 4)
        player("c", "ambassador", "duke", coins = 1)
    }

    @Nested
    inner class 막기_선언 {
        @Test
        fun `막으면 응답 창이 닫히고 막은 사람을 제외한 생존자 전원(행위자 포함)에게 도전 창이 열린다`() {
            val (state, events) = table.declare("a", "foreign_aid").state.block("b", "duke")
            assertThat(state.currentAction?.blockedBy).isEqualTo(BlockClaim(id("b"), RoleId("duke")))
            assertThat(state.window().kind).isEqualTo(WindowKind.BLOCK_CHALLENGE)
            assertThat(state.window().eligible).isEqualTo(setOf(id("a"), id("c")))
            val respond = testEngine.legalOptions(state, id("a")) as DecisionRequest.Respond
            assertThat(respond.canChallenge).isTrue()
            assertThat(respond.blockOptions).isEmpty()
            assertThat(respond.pending.blockedBy).isEqualTo(BlockClaim(id("b"), RoleId("duke")))
            assertThat(testEngine.legalOptions(state, id("b"))).isEqualTo(null)
            assertThat(events.only<GameEvent.BlockDeclared>()).hasSize(1)
        }

        @Test
        fun `해외원조는 여러 명이 막을 수 있지만 첫 막기만 수락된다`() {
            val blocked = table.declare("a", "foreign_aid").state.block("b", "duke").state
            assertThat(blocked.reject(Command.Block(id("c"), RoleId("duke")))).isEqualTo(Rejection.ROLE_CANNOT_BLOCK)
        }

        @Test
        fun `막을 수 없는 역할이나 대상이 아닌 사람의 막기는 거절된다`() {
            val steal = table.declare("a", "steal", "b").state
            assertThat(steal.reject(Command.Block(id("b"), RoleId("duke")))).isEqualTo(Rejection.ROLE_CANNOT_BLOCK)
            assertThat(steal.reject(Command.Block(id("c"), RoleId("captain")))).isEqualTo(Rejection.ROLE_CANNOT_BLOCK)
            val tax = table.declare("a", "tax").state
            assertThat(tax.reject(Command.Block(id("b"), RoleId("duke")))).isEqualTo(Rejection.ROLE_CANNOT_BLOCK)
        }

        @Test
        fun `역할이 없어도 막을 수 있다(블러핑 막기)`() {
            val state = table.declare("a", "steal", "c").state.block("c", "captain").state
            assertThat(state.currentAction?.blockedBy).isEqualTo(BlockClaim(id("c"), RoleId("captain")))
        }

        @Test
        fun `막기에 대한 도전 창에서는 막기를 다시 막을 수 없다`() {
            val state = table.declare("a", "foreign_aid").state.block("b", "duke").state
            assertThat(state.reject(Command.Block(id("c"), RoleId("duke")))).isEqualTo(Rejection.ROLE_CANNOT_BLOCK)
        }
    }

    @Nested
    inner class 막기_성립 {
        @Test
        fun `아무도 도전하지 않으면 행동은 막히고 턴이 넘어간다`() {
            val blocked = table.declare("a", "foreign_aid").state.block("b", "duke").state
            val afterA = blocked.accept(Command.Pass(id("a"))).state
            val (state, events) = afterA.accept(Command.Pass(id("c")))
            assertThat(state.coinsOf("a")).isEqualTo(3)
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.BLOCKED)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.currentAction).isEqualTo(null)
        }

        @Test
        fun `막힌 암살의 비용은 돌려받지 못한다`() {
            val state = table.declare("a", "assassinate", "b").state.block("b", "contessa").state.pass("a", "c")
            assertThat(state.coinsOf("a")).isEqualTo(0)
            assertThat(state.revealedRoles("b")).isEmpty()
        }
    }

    @Nested
    inner class 막기에_대한_도전 {
        @Test
        fun `도전하면 막은 사람이 공개할 카드를 고른다`() {
            val state = table.declare("a", "steal", "b").state.block("b", "captain").state.challenge("a").state
            assertThat(state.phase).isEqualTo(
                Phase.AwaitingReveal(id("b"), id("a"), setOf(RoleId("captain")), ChallengeContext.BLOCK),
            )
        }

        @Test
        fun `막기가 증명되면 도전자가 잃고 막은 사람 카드가 교체되며 행동은 막힌다`() {
            val revealed = table.declare("a", "assassinate", "b").state
                .block("b", "contessa").state
                .challenge("a").state
                .revealRole("b", "contessa")
            assertThat(revealed.events.only<GameEvent.CardReplaced>().single().player).isEqualTo(id("b"))
            assertThat(revealed.events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.BLOCKED)
            assertThat(revealed.state.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("a"), LossReason.ChallengeLost))

            val state = revealed.state.lose("a", "captain").state
            assertThat(state.revealedRoles("a")).containsExactly("captain")
            assertThat(state.revealedRoles("b")).isEmpty()
            assertThat(state.players.getValue(id("b")).hiddenCards).hasSize(2)
            assertThat(state.coinsOf("a")).isEqualTo(0) // 막혔으므로 환불 없음
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `막기 블러핑이 발각되면 막은 사람이 잃고 행동이 그대로 해결된다`() {
            val (state, events) = table.declare("a", "foreign_aid").state
                .block("c", "duke").state // c는 실제로 공작을 가졌지만
                .challenge("b").state
                .revealRole("c", "ambassador") // 다른 카드를 공개 → 블러핑 발각 처리
            assertThat(state.revealedRoles("c")).containsExactly("ambassador")
            assertThat(state.coinsOf("a")).isEqualTo(5)
            assertThat(events.only<GameEvent.InfluenceLost>().single().reason).isEqualTo(LossReason.BluffExposed)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        }

        @Test
        fun `암살 대상이 귀부인 블러핑으로 막았다가 발각되면 2장을 잃고 탈락한다`() {
            val s = scenario {
                player("a", "assassin", "captain", coins = 3)
                player("b", "duke", "ambassador")
                player("c", "ambassador", "duke")
            }
            val afterReveal = s.declare("a", "assassinate", "b").state
                .block("b", "contessa").state
                .challenge("a").state
                .revealRole("b", "duke").state
            // 블러핑 카드(duke) 상실 → 암살 효과로 남은 1장도 자동 상실
            assertThat(afterReveal.players.getValue(id("b")).isAlive).isFalse()
            assertThat(afterReveal.revealedRoles("b")).containsExactlyInAnyOrder("duke", "ambassador")
            assertThat(afterReveal.eliminationOrder).containsExactly(id("b"))
            assertThat(afterReveal.phase).isEqualTo(Phase.AwaitingAction(id("c")))
        }

        @Test
        fun `카드 1장인 대상의 막기 블러핑이 발각되면 탈락하고 암살은 무효`() {
            val s = scenario {
                player("a", "assassin", "captain", coins = 3)
                player("b", "duke", "ambassador", revealed = setOf(1))
                player("c", "ambassador", "duke")
            }
            val (state, events) = s.declare("a", "assassinate", "b").state.block("b", "contessa").state.challenge("c")
            assertThat(state.players.getValue(id("b")).isAlive).isFalse()
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.FIZZLED)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("c")))
        }

        @Test
        fun `행위자가 막기에 도전했다 마지막 카드를 잃으면 탈락, 2인전이면 막은 사람 승리`() {
            val s = scenario {
                player("a", "assassin", "captain", coins = 3, revealed = setOf(1))
                player("b", "contessa", "duke")
            }
            val state = s.declare("a", "assassinate", "b").state
                .block("b", "contessa").state
                .challenge("a").state
                .revealRole("b", "contessa").state
            assertThat(state.isOver).isTrue()
            assertThat((state.phase as Phase.GameOver).winner).isEqualTo(id("b"))
            assertThat(state.stack).isEmpty()
        }

        @Test
        fun `행동 도전 실패 후 막기 전용 창에서 막고, 그 막기에도 도전할 수 있다`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 2)
                player("b", "contessa", "assassin", coins = 4)
                player("c", "ambassador", "duke")
            }
            // a의 강탈(사령관 보유)에 b가 도전 → 실패 → b가 카드를 잃은 뒤 막기 기회
            val blockOnly = s.declare("a", "steal", "b").state
                .challenge("b").state
                .revealRole("a", "captain").state
                .lose("b", "assassin").state
            assertThat(blockOnly.window().kind).isEqualTo(WindowKind.BLOCK_ONLY)
            // b가 외교관 블러핑으로 막음 → c가 도전 → b의 마지막 카드 상실, 강탈은 무효(대상 탈락)
            val (state, events) = blockOnly.block("b", "ambassador").state.challenge("c")
            assertThat(state.players.getValue(id("b")).isAlive).isFalse()
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.FIZZLED)
            assertThat(state.coinsOf("a")).isEqualTo(2)
        }

        @Test
        fun `막기 해결 후에도 카드 총량이 보존된다`() {
            val state = table.declare("a", "assassinate", "b").state
                .block("b", "contessa").state.challenge("a").state
                .revealRole("b", "contessa").state.lose("a", "captain").state
            assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
            assertThat(state.hiddenRoles("a")).containsExactly("assassin")
        }
    }
}
