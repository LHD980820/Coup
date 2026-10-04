package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.allCardIds
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.hiddenRoles
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.only
import io.github.lhd980820.coup.engine.testing.reject
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.api.Test

class ExchangeTest {

    private fun GameState.pass(vararg players: String): GameState =
        players.fold(this) { s, p -> s.accept(Command.Pass(id(p))).state }

    private fun GameState.exchangePhase() = phase as Phase.AwaitingExchange

    private fun GameState.keep(player: String, vararg ids: Int) =
        accept(Command.ChooseExchange(id(player), ids.map { CardId(it) }))

    private val table = scenario {
        player("a", "ambassador", "duke")
        player("b", "contessa", "assassin")
        player("c", "captain", "duke")
        deckTop("captain", "assassin")
    }

    private fun awaiting(): GameState = table.declare("a", "exchange").state.pass("b", "c")

    @Test
    fun `전원 통과하면 손패와 덱 위 2장이 후보가 되고 남길 장수는 미공개 카드 수다`() {
        val state = awaiting()
        val phase = state.exchangePhase()
        assertThat(phase.player).isEqualTo(id("a"))
        assertThat(phase.keepCount).isEqualTo(2)
        assertThat(phase.candidates.map { it.role.value }).containsExactly("ambassador", "duke", "captain", "assassin")
        assertThat(phase.candidates.drop(2)).isEqualTo(state.deck.take(2))
        assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("a")))
        val decision = testEngine.legalOptions(state, id("a")) as DecisionRequest.ChooseExchange
        assertThat(decision.candidates).hasSize(4)
        assertThat(decision.keepCount).isEqualTo(2)
        assertThat(testEngine.legalOptions(state, id("b"))).isNull()
    }

    @Test
    fun `선택 중에도 뽑은 카드는 덱에 남아 있어 카드 총량이 보존된다`() {
        val state = awaiting()
        assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
    }

    @Test
    fun `고른 카드가 손패가 되고 나머지는 덱으로 돌아가며 턴이 넘어간다`() {
        val before = awaiting()
        val cands = before.exchangePhase().candidates
        val deckSize = before.deck.size
        val (state, events) = before.keep("a", cands[2].id.value, cands[3].id.value) // captain, assassin을 남김
        assertThat(state.hiddenRoles("a")).containsExactly("captain", "assassin")
        assertThat(state.deck).hasSize(deckSize)
        assertThat(state.deck.map { it.id }).containsExactlyInAnyOrder(
            *(before.deck.drop(2).map { it.id } + cands[0].id + cands[1].id).toTypedArray(),
        )
        assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
        assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        assertThat(events.only<GameEvent.ExchangeCompleted>()).hasSize(1)
    }

    @Test
    fun `원래 손패를 그대로 남길 수도 있다`() {
        val before = awaiting()
        val cands = before.exchangePhase().candidates
        val state = before.keep("a", cands[0].id.value, cands[1].id.value).state
        assertThat(state.hiddenRoles("a")).containsExactly("ambassador", "duke")
        assertThat(state.deck.map { it.id }).containsExactlyInAnyOrder(*before.deck.map { it.id }.toTypedArray())
    }

    @Test
    fun `공개된 카드는 손패에서 그대로 유지되고 미공개 1장만 교환한다`() {
        val s = scenario {
            player("a", "ambassador", "duke", revealed = setOf(1))
            player("b", "contessa", "assassin")
            deckTop("captain", "assassin")
        }
        val state = s.declare("a", "exchange").state.pass("b")
        assertThat(state.exchangePhase().keepCount).isEqualTo(1)
        assertThat(state.exchangePhase().candidates.map { it.role.value }).containsExactly("ambassador", "captain", "assassin")
        val picked = state.exchangePhase().candidates[2].id
        val after = state.keep("a", picked.value).state
        assertThat(after.hiddenRoles("a")).containsExactly("assassin")
        assertThat(after.players.getValue(id("a")).influences.map { it.revealed }).containsExactly(false, true)
        assertThat(after.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
    }

    @Test
    fun `잘못된 선택은 거절된다 - 장수, 중복, 후보 밖 카드`() {
        val state = awaiting()
        val cands = state.exchangePhase().candidates
        assertThat(state.reject(Command.ChooseExchange(id("a"), listOf(cands[0].id)))).isEqualTo(Rejection.INVALID_EXCHANGE_SELECTION)
        assertThat(state.reject(Command.ChooseExchange(id("a"), cands.take(3).map { it.id })))
            .isEqualTo(Rejection.INVALID_EXCHANGE_SELECTION)
        assertThat(state.reject(Command.ChooseExchange(id("a"), listOf(cands[0].id, cands[0].id))))
            .isEqualTo(Rejection.INVALID_EXCHANGE_SELECTION)
        val outsider = state.players.getValue(id("b")).hiddenCards.first().id
        assertThat(state.reject(Command.ChooseExchange(id("a"), listOf(cands[0].id, outsider))))
            .isEqualTo(Rejection.INVALID_EXCHANGE_SELECTION)
        val deepDeck = state.deck.last().id
        assertThat(state.reject(Command.ChooseExchange(id("a"), listOf(cands[0].id, deepDeck))))
            .isEqualTo(Rejection.INVALID_EXCHANGE_SELECTION)
    }

    @Test
    fun `다른 사람의 교환 선택이나 엉뚱한 단계의 선택은 거절된다`() {
        val state = awaiting()
        val cands = state.exchangePhase().candidates
        assertThat(state.reject(Command.ChooseExchange(id("b"), cands.take(2).map { it.id }))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        assertThat(state.reject(Command.Pass(id("a")))).isEqualTo(Rejection.WRONG_PHASE)
        assertThat(table.reject(Command.ChooseExchange(id("a"), emptyList()))).isEqualTo(Rejection.WRONG_PHASE)
    }

    @Test
    fun `돌려보낸 카드를 섞지 않는 룰이면 덱 맨 아래에 후보 순서대로 쌓인다`() {
        val config = BuiltinRules.classicConfig().copy(paramOverrides = mapOf("exchangeReturnShuffles" to "false"))
        val s = scenario(config) {
            player("a", "ambassador", "duke")
            player("b", "contessa", "assassin")
            deckTop("captain", "assassin")
        }
        val before = s.declare("a", "exchange").state.pass("b")
        val cands = before.exchangePhase().candidates
        val state = before.keep("a", cands[2].id.value, cands[3].id.value).state
        assertThat(state.deck.takeLast(2).map { it.id }).containsExactly(cands[0].id, cands[1].id)
        assertThat(state.deck.dropLast(2)).isEqualTo(before.deck.drop(2))
    }

    @Test
    fun `교환 후 덱 순서는 셔플된다`() {
        val before = awaiting()
        val cands = before.exchangePhase().candidates
        val state = before.keep("a", cands[2].id.value, cands[3].id.value).state
        assertThat(state.deck).isNotEqualTo(before.deck.drop(2) + cands[0] + cands[1])
    }

    @Test
    fun `덱이 비어 있으면 교환 없이 턴이 넘어간다`() {
        val s = scenario {
            player("a", "ambassador", "duke")
            player("b", "contessa", "assassin")
        }.let { it.copy(deck = emptyList()) }
        val state = s.declare("a", "exchange").state.pass("b")
        assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
        assertThat(state.hiddenRoles("a")).containsExactly("ambassador", "duke")
    }

    @Test
    fun `덱이 1장뿐이면 가능한 만큼만 뽑는다`() {
        val s = scenario {
            player("a", "ambassador", "duke")
            player("b", "contessa", "assassin")
            deckTop("captain")
        }.let { it.copy(deck = it.deck.take(1)) }
        val state = s.declare("a", "exchange").state.pass("b")
        assertThat(state.exchangePhase().candidates).hasSize(3)
        assertThat(state.exchangePhase().keepCount).isEqualTo(2)
    }

    @Test
    fun `교환을 도전받아 블러핑이 발각되면 교환하지 않고 덱도 그대로다`() {
        val s = scenario {
            player("a", "duke", "captain")
            player("b", "contessa", "assassin")
            deckTop("captain", "assassin")
        }
        val declared = s.declare("a", "exchange").state
        val deckBefore = declared.deck
        val afterChallenge = declared.accept(Command.Challenge(id("b"))).state
        val cardId = afterChallenge.players.getValue(id("a")).hiddenCards.first().id
        val (state, events) = afterChallenge.accept(Command.RevealCard(id("a"), cardId))
        assertThat(state.deck).isEqualTo(deckBefore)
        assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.FAILED)
        assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
    }

    @Test
    fun `교환을 도전받아 증명하면 도전자가 잃은 뒤 교환이 진행된다`() {
        val s = scenario {
            player("a", "ambassador", "duke")
            player("b", "contessa", "assassin")
            player("c", "captain", "duke")
            deckTop("captain", "assassin")
        }
        val challenged = s.declare("a", "exchange").state.accept(Command.Challenge(id("b"))).state
        val ambassador = challenged.players.getValue(id("a")).hiddenCards.first { it.role.value == "ambassador" }.id
        val afterReveal = challenged.accept(Command.RevealCard(id("a"), ambassador)).state
        val lost = afterReveal.players.getValue(id("b")).hiddenCards.first().id
        val state = afterReveal.accept(Command.LoseInfluence(id("b"), lost)).state
        // 교환은 막을 수 없으므로 막기 창 없이 바로 교환 단계
        assertThat(state.phase is Phase.AwaitingExchange).isTrue()
        assertThat(state.allCardIds().map { it.value }.sorted()).isEqualTo((0 until 15).toList())
    }
}
