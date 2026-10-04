package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.ChallengeContext
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
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

class ChallengeTest {

    private fun GameState.challenge(by: String) = accept(Command.Challenge(id(by)))

    private fun GameState.revealRole(player: String, role: String) =
        accept(Command.RevealCard(id(player), cardOf(player, role)))

    private fun GameState.lose(player: String, role: String) =
        accept(Command.LoseInfluence(id(player), cardOf(player, role)))

    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }

    private fun GameState.cardOf(player: String, role: String): CardId =
        players.getValue(id(player)).hiddenCards.first { it.role.value == role }.id

    private val table = scenario {
        player("a", "duke", "captain", coins = 3)
        player("b", "contessa", "assassin", coins = 4)
        player("c", "ambassador", "duke", coins = 1)
    }

    @Nested
    inner class 도전_개시 {
        @Test
        fun `도전하면 응답 창이 닫히고 행위자가 공개할 카드를 고른다`() {
            val (state, events) = table.declare("a", "tax").state.challenge("b")
            assertThat(state.phase).isEqualTo(Phase.AwaitingReveal(id("a"), id("b"), setOf(RoleId("duke")), ChallengeContext.ACTION))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("a")))
            val decision = testEngine.legalOptions(state, id("a")) as DecisionRequest.ChooseRevealCard
            assertThat(decision.cards).hasSize(2)
            assertThat(decision.claimedRoles).isEqualTo(setOf(RoleId("duke")))
            assertThat(events.only<GameEvent.ChallengeIssued>()).hasSize(1)
        }

        @Test
        fun `첫 도전 후에는 다른 사람의 도전이나 통과는 거절된다`() {
            val state = table.declare("a", "tax").state.challenge("b").state
            assertThat(state.reject(Command.Challenge(id("c")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
            assertThat(state.reject(Command.Pass(id("c")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `이미 통과한 사람은 도전할 수 없다`() {
            val state = table.declare("a", "tax").state.pass("b")
            assertThat(state.reject(Command.Challenge(id("b")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `주장 없는 행동(해외원조)은 도전할 수 없다`() {
            val state = table.declare("a", "foreign_aid").state
            assertThat(state.reject(Command.Challenge(id("b")))).isEqualTo(Rejection.CHALLENGE_NOT_ALLOWED)
        }

        @Test
        fun `공개는 도전받은 사람만, 자기 미공개 카드로만 할 수 있다`() {
            val state = table.declare("a", "tax").state.challenge("b").state
            assertThat(state.reject(Command.RevealCard(id("b"), state.cardOf("b", "contessa")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
            assertThat(state.reject(Command.RevealCard(id("a"), state.cardOf("b", "contessa")))).isEqualTo(Rejection.CARD_NOT_OWNED)
        }

        @Test
        fun `도전받은 사람의 미공개 카드가 1장이면 자동으로 공개된다`() {
            val s = scenario {
                player("a", "contessa", "duke", revealed = setOf(0))
                player("b", "captain", "assassin")
                player("c", "ambassador", "duke")
            }
            val (state, events) = s.declare("a", "tax").state.challenge("b")
            assertThat(events.only<GameEvent.CardRevealed>().single().proven).isTrue()
            // 증명 성공 → 도전자 b가 잃을 카드를 고르는 단계
            assertThat(state.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ChallengeLost))
        }
    }

    @Nested
    inner class 증명_성공 {
        @Test
        fun `도전자가 영향력을 잃고 행동이 해결된다`() {
            val afterReveal = table.declare("a", "tax").state.challenge("b").state.revealRole("a", "duke").state
            assertThat(afterReveal.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ChallengeLost))
            val (state, events) = afterReveal.lose("b", "assassin")
            assertThat(state.revealedRoles("b")).containsExactly("assassin")
            assertThat(state.coinsOf("a")).isEqualTo(6)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.SUCCESS)
        }

        @Test
        fun `증명한 카드는 덱으로 돌아가고 새 카드로 교체된다 - 카드 총량과 덱 장수 보존`() {
            val before = table.declare("a", "tax").state.challenge("b").state
            val deckSize = before.deck.size
            val proven = before.cardOf("a", "duke")
            val (state, events) = before.revealRole("a", "duke")
            val replaced = events.only<GameEvent.CardReplaced>().single()
            assertThat(replaced.returned.id).isEqualTo(proven)
            assertThat(state.players.getValue(id("a")).hiddenCards).hasSize(2)
            assertThat(state.players.getValue(id("a")).hiddenCards.map { it.id }).contains(replaced.newCard.id)
            assertThat(state.deck).hasSize(deckSize)
            assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
            // 반환된 카드는 덱에 있거나(대부분), 셔플 결과 다시 뽑혔다면 손패에 있다.
            val inDeck = state.deck.any { it.id == proven }
            val redrawn = replaced.newCard.id == proven
            assertThat(inDeck || redrawn).isTrue()
        }

        @Test
        fun `교체는 도전자의 상실보다 먼저 일어난다`() {
            val events = table.declare("a", "tax").state.challenge("b").state.revealRole("a", "duke").events
            val replacedAt = events.indexOfFirst { it is GameEvent.CardReplaced }
            assertThat(replacedAt >= 0).isTrue()
            assertThat(events.only<GameEvent.InfluenceLost>()).isEmpty() // b는 아직 고르는 중
        }

        @Test
        fun `도전자의 카드가 1장이면 자동으로 잃고 탈락, 2인전이면 게임 종료`() {
            val s = scenario {
                player("a", "duke", "captain")
                player("b", "contessa", "assassin", revealed = setOf(0))
            }
            val state = s.declare("a", "tax").state.challenge("b").state.revealRole("a", "duke").state
            assertThat(state.isOver).isTrue()
            assertThat(state.phase).isInstanceOf(Phase.GameOver::class)
            assertThat(state.stack).isEmpty()
        }

        @Test
        fun `막을 수 있는 행동(강탈)은 도전 실패 후 대상에게 막기 기회가 남는다`() {
            val afterLoss = table.declare("a", "steal", "b").state
                .challenge("b").state
                .revealRole("a", "captain").state
                .lose("b", "assassin").state
            val window = (afterLoss.phase as Phase.AwaitingResponses).window
            assertThat(window.kind).isEqualTo(WindowKind.BLOCK_ONLY)
            assertThat(window.eligible).isEqualTo(setOf(id("b")))
            val respond = testEngine.legalOptions(afterLoss, id("b")) as DecisionRequest.Respond
            assertThat(respond.canChallenge).isFalse()
            assertThat(respond.blockOptions.map { it.role.value }).containsExactly("captain", "ambassador")
            assertThat(afterLoss.reject(Command.Challenge(id("b")))).isEqualTo(Rejection.CHALLENGE_NOT_ALLOWED)

            val done = afterLoss.pass("b")
            assertThat(done.coinsOf("a")).isEqualTo(5)
            assertThat(done.coinsOf("b")).isEqualTo(2)
        }

        @Test
        fun `행동 도전에 실패한 제3자는 막기 창에 들어가지 않는다`() {
            val afterLoss = table.declare("a", "steal", "b").state
                .challenge("c").state
                .revealRole("a", "captain").state
                .lose("c", "duke").state
            assertThat((afterLoss.phase as Phase.AwaitingResponses).window.eligible).isEqualTo(setOf(id("b")))
        }
    }

    @Nested
    inner class 암살_연쇄 {
        @Test
        fun `암살 대상이 도전했다가 실패하면 막기 기회를 거쳐 총 2장을 잃는다`() {
            val s = scenario {
                player("a", "assassin", "captain", coins = 3)
                player("b", "contessa", "duke")
                player("c", "ambassador", "duke")
            }
            val state1 = s.declare("a", "assassinate", "b").state.challenge("b").state
                .revealRole("a", "assassin").state
                .lose("b", "duke").state
            // 대상 b는 아직 살아 있고 귀부인으로 막을 수 있다(막기 창)
            assertThat((state1.phase as Phase.AwaitingResponses).window.kind).isEqualTo(WindowKind.BLOCK_ONLY)
            // 막지 않으면 남은 1장도 잃고 탈락
            val end = state1.pass("b")
            assertThat(end.players.getValue(id("b")).isAlive).isFalse()
            assertThat(end.revealedRoles("b")).containsExactlyInAnyOrder("duke", "contessa")
            assertThat(end.coinsOf("a")).isEqualTo(0) // 행동이 성공했으므로 환불 없음
        }

        @Test
        fun `카드 1장인 암살 대상이 도전에 실패하면 탈락하고 암살은 무효(fizzle)`() {
            val s = scenario {
                player("a", "assassin", "captain", coins = 3)
                player("b", "contessa", "duke", revealed = setOf(1))
                player("c", "ambassador", "duke")
            }
            val (state, events) = s.declare("a", "assassinate", "b").state.challenge("b").state.revealRole("a", "assassin")
            assertThat(state.players.getValue(id("b")).isAlive).isFalse()
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.FIZZLED)
            assertThat(state.revealedRoles("b")).containsExactlyInAnyOrder("duke", "contessa")
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("c")))
        }
    }

    @Nested
    inner class 블러핑_발각 {
        @Test
        fun `주장 역할이 아닌 카드를 공개하면 그 카드를 잃고 행동은 실패, 턴 종료`() {
            val (state, events) = table.declare("a", "assassinate", "b").state.challenge("c").state.revealRole("a", "captain")
            assertThat(state.revealedRoles("a")).containsExactly("captain")
            assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.FAILED)
            assertThat(events.only<GameEvent.InfluenceLost>().single().reason).isEqualTo(LossReason.BluffExposed)
            assertThat(state.revealedRoles("b")).isEmpty()
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.currentAction == null).isTrue()
        }

        @Test
        fun `기본 룰에서는 블러핑 발각 시 비용을 환불한다`() {
            val state = table.declare("a", "assassinate", "b").state.challenge("c").state.revealRole("a", "captain").state
            assertThat(state.coinsOf("a")).isEqualTo(3)
        }

        @Test
        fun `환불 파라미터를 끄면 비용을 돌려받지 못한다`() {
            val noRefund = BuiltinRules.classicConfig().copy(paramOverrides = mapOf("refundCostWhenActionChallengeLost" to "false"))
            val s = scenario(noRefund) {
                player("a", "duke", "captain", coins = 3)
                player("b", "contessa", "assassin")
                player("c", "ambassador", "duke")
            }
            val state = s.declare("a", "assassinate", "b").state.challenge("c").state.revealRole("a", "captain").state
            assertThat(state.coinsOf("a")).isEqualTo(0)
        }

        @Test
        fun `역할을 가지고 있어도 다른 카드를 공개하면 그 카드를 잃는다(선택 존중)`() {
            val state = table.declare("a", "tax").state.challenge("b").state.revealRole("a", "captain").state
            assertThat(state.revealedRoles("a")).containsExactly("captain")
            assertThat(state.hiddenRoles("a")).containsExactly("duke")
            assertThat(state.coinsOf("a")).isEqualTo(3)
        }

        @Test
        fun `행위자가 마지막 카드로 블러핑하다 발각되면 탈락하고 다음 생존자 턴`() {
            val s = scenario {
                player("a", "duke", "captain", revealed = setOf(0))
                player("b", "contessa", "assassin")
                player("c", "ambassador", "duke")
            }
            val (state, events) = s.declare("a", "tax").state.challenge("c")
            assertThat(state.players.getValue(id("a")).isAlive).isFalse()
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(events.only<GameEvent.PlayerEliminated>()).hasSize(1)
        }

        @Test
        fun `블러핑 발각으로 2인전이 끝나면 즉시 종료되고 스택이 비워진다`() {
            val s = scenario {
                player("a", "duke", "captain", revealed = setOf(0))
                player("b", "contessa", "assassin")
            }
            val state = s.declare("a", "tax").state.challenge("b").state
            assertThat(state.isOver).isTrue()
            assertThat(state.stack).isEmpty()
            assertThat((state.phase as Phase.GameOver).winner).isEqualTo(id("b"))
        }
    }

    @Test
    fun `도전 이후 해결 전체에서 카드 총량이 보존된다`() {
        val state = table.declare("a", "tax").state.challenge("b").state.revealRole("a", "duke").state.lose("b", "contessa").state
        assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
        assertThat(state.reject(Command.DeclareAction(id("a"), ActionId("income")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
    }
}
