package io.github.lhd980820.coup.engine.view

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.randomCommand
import io.github.lhd980820.coup.engine.testing.randomGame
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.testing.trueAssignment
import io.github.lhd980820.coup.engine.testing.viewersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/** 설계 §12.4: 정보 은닉 테스트. */
class InformationHidingTest {

    private fun viewJson(view: PlayerView): JsonElement = EngineJson.json.encodeToJsonElement(PlayerView.serializer(), view)

    /** JSON 트리에서 카드 객체({id, role})의 id를 모두 모은다. */
    private fun cardIdsIn(json: JsonElement): Set<Int> = when (json) {
        is JsonObject -> {
            val self = if (json.keys.containsAll(setOf("id", "role")) && json["id"] is JsonPrimitive) {
                setOf((json["id"] as JsonPrimitive).int)
            } else emptySet()
            self + json.values.flatMap { cardIdsIn(it) }
        }
        is JsonArray -> json.flatMap { cardIdsIn(it) }.toSet()
        else -> emptySet()
    }

    private fun keysIn(json: JsonElement): Set<String> = when (json) {
        is JsonObject -> json.keys + json.values.flatMap { keysIn(it) }
        is JsonArray -> json.flatMap { keysIn(it) }.toSet()
        else -> emptySet()
    }

    /** [viewer]가 정당하게 알 수 있는 카드 ID: 내 손패 + 내가 교환 중일 때의 후보. */
    private fun knowable(state: GameState, viewer: Viewer): Set<Int> {
        val me = (viewer as? Viewer.Player)?.id ?: return emptySet()
        val hand = state.players.getValue(me).influences.map { it.card.id.value }
        val exchange = (state.phase as? Phase.AwaitingExchange)?.takeIf { it.player == me }?.candidates?.map { it.id.value }.orEmpty()
        return (hand + exchange).toSet()
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 6])
    fun `무작위 게임의 모든 상태와 모든 시점에서 뷰는 알 수 없는 카드를 담지 않는다`(players: Int) {
        repeat(15) { game ->
            randomGame(seed = players * 100L + game, players = players) { state ->
                viewersOf(state).forEach { viewer ->
                    val json = viewJson(testEngine.view(state, viewer))
                    val leaked = cardIdsIn(json) - knowable(state, viewer)
                    check(leaked.isEmpty()) { "view for $viewer leaks cards $leaked at version ${state.version}" }
                    check("deck" !in keysIn(json) && "rng" !in keysIn(json)) { "view exposes deck/rng" }
                }
            }
        }
    }

    @Test
    fun `누출 검사기 자체 검증 - 전체 상태 JSON에서는 상대 카드와 덱 카드를 찾아낸다`() {
        val state = scenario {
            player("a", "duke", "captain")
            player("b", "contessa", "assassin")
        }
        val full = EngineJson.json.encodeToJsonElement(GameState.serializer(), state)
        val leaked = cardIdsIn(full) - knowable(state, Viewer.Player(id("a")))
        val bHidden = state.players.getValue(id("b")).hiddenCards.map { it.id.value }.toSet()
        assertThat(leaked.containsAll(bHidden + state.deck.map { it.id.value })).isEqualTo(true)
        assertThat("deck" in keysIn(full)).isEqualTo(true)
    }

    @Test
    fun `비공개 정보만 다른 두 상태는 같은 뷰를 만든다`() {
        val a = scenario {
            player("me", "duke", "captain")
            player("x", "contessa", "assassin")
            player("y", "ambassador", "duke")
        }
        val b = scenario {
            player("me", "duke", "captain")
            player("x", "ambassador", "ambassador")
            player("y", "contessa", "captain")
        }
        assertThat(testEngine.view(a, Viewer.Player(id("me")))).isEqualTo(testEngine.view(b, Viewer.Player(id("me"))))
        assertThat(testEngine.view(a, Viewer.Spectator)).isEqualTo(testEngine.view(b, Viewer.Spectator))
        // x 시점에서는 x의 손패가 다르므로 다르다
        assertThat(testEngine.view(a, Viewer.Player(id("x"))) == testEngine.view(b, Viewer.Player(id("x")))).isEqualTo(false)
    }

    @Nested
    inner class 뷰_내용 {
        private val table = scenario {
            player("a", "duke", "captain", coins = 3)
            player("b", "contessa", "assassin", revealed = setOf(1))
            player("c", "ambassador", "duke")
        }

        @Test
        fun `내 카드는 역할까지, 상대는 장수와 공개 역할만 보인다`() {
            val view = testEngine.view(table, Viewer.Player(id("a")))
            assertThat(view.me?.hand?.map { it.card.role.value }).isEqualTo(listOf("duke", "captain"))
            val b = checkNotNull(view.opponent(id("b")))
            assertThat(b.hiddenCount).isEqualTo(1)
            assertThat(b.revealed.map { it.value }).containsExactly("assassin")
            assertThat(view.opponents.map { it.id }).containsExactly(id("b"), id("c"))
            assertThat(view.deckSize).isEqualTo(9)
            assertThat(view.myDecision).isNotNull().isInstanceOf(DecisionRequest.ChooseAction::class)
            assertThat(view.ruleSet.totalCards).isEqualTo(15)
        }

        @Test
        fun `관전자는 아무의 카드도 보지 못하고 결정도 없다`() {
            val view = testEngine.view(table, Viewer.Spectator)
            assertThat(view.me).isNull()
            assertThat(view.myDecision).isNull()
            assertThat(view.opponents.map { it.id }).containsExactly(id("a"), id("b"), id("c"))
        }

        @Test
        fun `결정권이 없는 플레이어의 뷰에는 결정이 없다`() {
            assertThat(testEngine.view(table, Viewer.Player(id("c"))).myDecision).isNull()
        }

        @Test
        fun `상대의 교환 중에는 후보 장수만 보이고 당사자는 후보를 결정으로 받는다`() {
            val exchanging = table.declare("a", "exchange").state
                .accept(Command.Pass(id("b"))).state.accept(Command.Pass(id("c"))).state
            val other = testEngine.view(exchanging, Viewer.Player(id("b")))
            assertThat(other.phase).isEqualTo(PublicPhase.AwaitingExchange(id("a"), candidateCount = 4, keepCount = 2))
            val mine = testEngine.view(exchanging, Viewer.Player(id("a")))
            assertThat((mine.myDecision as DecisionRequest.ChooseExchange).candidates.size).isEqualTo(4)
        }

        @Test
        fun `종료된 게임은 결과를 담는다`() {
            val over = scenario {
                player("a", "duke", "captain")
                player("b", "contessa", "assassin")
            }.accept(Command.Concede(id("b"))).state
            assertThat(testEngine.view(over, Viewer.Spectator).result?.winner).isEqualTo(id("a"))
        }
    }

    @Nested
    inner class 이벤트_투영 {
        private val table = scenario {
            player("a", "duke", "captain")
            player("b", "contessa", "assassin")
            player("c", "ambassador", "duke")
        }

        @Test
        fun `증명 후 새로 받은 카드는 당사자에게만 보인다`() {
            val challenged = table.declare("a", "tax").state.accept(Command.Challenge(id("b"))).state
            val duke = challenged.players.getValue(id("a")).hiddenCards.first { it.role.value == "duke" }.id
            val events = challenged.accept(Command.RevealCard(id("a"), duke)).events
            val original = events.filterIsInstance<GameEvent.CardReplaced>().single()

            val own = testEngine.projectEvents(events, Viewer.Player(id("a"))).map { it.event }
            assertThat(own.filterIsInstance<GameEvent.CardReplaced>().single()).isEqualTo(original)

            listOf(Viewer.Player(id("b")), Viewer.Spectator).forEach { viewer ->
                val projected = testEngine.projectEvents(events, viewer).map { it.event }
                assertThat(projected.filterIsInstance<GameEvent.CardReplaced>()).isEmpty()
                assertThat(projected.filterIsInstance<GameEvent.CardReplacedHidden>().single())
                    .isEqualTo(GameEvent.CardReplacedHidden(id("a"), original.returned))
            }
        }

        @Test
        fun `교환으로 엿본 카드는 당사자에게만, 타인에게는 장수만`() {
            val events = table.declare("a", "exchange").state.accept(Command.Pass(id("b"))).state
                .accept(Command.Pass(id("c"))).events
            val original = events.filterIsInstance<GameEvent.ExchangeDrawn>().single()
            assertThat(testEngine.projectEvents(events, Viewer.Player(id("a"))).map { it.event }).isEqualTo(events)
            val other = testEngine.projectEvents(events, Viewer.Player(id("c"))).map { it.event }
            assertThat(other.filterIsInstance<GameEvent.ExchangeDrawnHidden>().single())
                .isEqualTo(GameEvent.ExchangeDrawnHidden(id("a"), original.cards.size))
            assertThat(other.filterIsInstance<GameEvent.ExchangeDrawn>()).isEmpty()
        }

        @Test
        fun `공개 이벤트는 그대로 전달된다`() {
            val events = table.declare("a", "income").events
            assertThat(testEngine.projectEvents(events, Viewer.Spectator).map { it.event }).isEqualTo(events)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [5, 6, 7, 8, 9])
    fun `무작위 게임 전반에서 비공개 이벤트는 당사자 외에게 넘어가지 않는다`(seed: Long) {
        var state = testEngine.newGame(GameSetup("ev-$seed", List(4) { id("p$it") }, BuiltinRules.classicConfig(), seed))
        val rnd = Random(seed)
        while (!state.isOver) {
            val who = testEngine.pendingDeciders(state).random(rnd)
            val result = testEngine.apply(state, randomCommand(state, who, rnd)) as ApplyResult.Accepted
            viewersOf(state).forEach { viewer ->
                val me = (viewer as? Viewer.Player)?.id
                testEngine.projectEvents(result.events, viewer).forEach { visible ->
                    when (val e = visible.event) {
                        is GameEvent.CardReplaced -> check(e.player == me) { "CardReplaced leaked to $viewer" }
                        is GameEvent.ExchangeDrawn -> check(e.player == me) { "ExchangeDrawn leaked to $viewer" }
                        else -> Unit
                    }
                }
            }
            state = result.state
        }
    }
}
