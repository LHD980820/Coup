package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.prop
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NewGameTest {
    private val engine = GameEngines.create()
    private val classic = BuiltinRules.classicConfig()

    private fun seats(n: Int) = List(n) { PlayerId("p${it + 1}") }

    private fun setup(n: Int = 4, seed: Long = 1, first: PlayerId? = null, config: RuleSetConfig = classic) =
        GameSetup("g1", seats(n), config, seed, first)

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 5, 6])
    fun `카드는 덱과 손패를 합쳐 정확히 15장이고 CardId가 중복되지 않는다`(n: Int) {
        val state = engine.newGame(setup(n))
        val all = state.deck + state.players.values.flatMap { p -> p.influences.map { it.card } }
        assertThat(all).hasSize(15)
        assertThat(all.map { it.id }.toSet()).hasSize(15)
        assertThat(state.deck).hasSize(15 - 2 * n)
    }

    @Test
    fun `역할별 장수는 정의대로 3장씩이다`() {
        val state = engine.newGame(setup(6))
        val all = state.deck + state.players.values.flatMap { p -> p.influences.map { it.card } }
        val counts = all.groupingBy { it.role }.eachCount()
        assertThat(counts.keys).containsExactlyInAnyOrder(
            RoleId("duke"), RoleId("assassin"), RoleId("captain"), RoleId("ambassador"), RoleId("contessa"),
        )
        assertThat(counts.values.toList()).each { it.isEqualTo(3) }
    }

    @Test
    fun `모든 플레이어는 미공개 카드 2장과 코인 2개로 시작한다`() {
        val state = engine.newGame(setup(5))
        assertThat(state.players.values.toList()).each {
            it.prop(PlayerState::coins).isEqualTo(2)
            it.prop(PlayerState::hiddenCards).hasSize(2)
        }
    }

    @Test
    fun `초기 상태는 선 플레이어의 행동 대기, 버전 0, 빈 스택이다`() {
        val state = engine.newGame(setup(3, first = PlayerId("p2")))
        assertThat(state.phase).isEqualTo(Phase.AwaitingAction(PlayerId("p2")))
        assertThat(state.turn.activePlayer).isEqualTo(PlayerId("p2"))
        assertThat(state.turn.number).isEqualTo(1)
        assertThat(state.version).isEqualTo(0L)
        assertThat(state.stack).isEmpty()
        assertThat(state.currentAction).isNull()
        assertThat(state.eliminationOrder).isEmpty()
        assertThat(state.isOver).isFalse()
    }

    @Test
    fun `선 플레이어를 지정하지 않으면 seed로 결정된다`() {
        val a = engine.newGame(setup(6, seed = 10))
        assertThat(a.turn.activePlayer).isIn(*seats(6).toTypedArray())
        assertThat(engine.newGame(setup(6, seed = 10)).turn.activePlayer).isEqualTo(a.turn.activePlayer)
    }

    @Test
    fun `같은 seed는 같은 게임, 다른 seed는 다른 덱을 만든다`() {
        assertThat(engine.newGame(setup(seed = 7))).isEqualTo(engine.newGame(setup(seed = 7)))
        assertThat(engine.newGame(setup(seed = 7)).deck).isNotEqualTo(engine.newGame(setup(seed = 8)).deck)
    }

    @Test
    fun `파라미터 오버라이드가 적용된다`() {
        val config = classic.copy(paramOverrides = mapOf("startingCoins" to "1"))
        val state = engine.newGame(setup(config = config))
        assertThat(state.players.values.map { it.coins }.toSet()).isEqualTo(setOf(1))
        assertThat(state.ruleSetConfig).isEqualTo(config)
    }

    @Test
    fun `잘못된 설정은 예외`() {
        assertThrows<IllegalArgumentException> { engine.newGame(setup(1)) }
        assertThrows<IllegalArgumentException> { engine.newGame(setup(7)) }
        assertThrows<IllegalArgumentException> {
            engine.newGame(GameSetup("g", listOf(PlayerId("a"), PlayerId("a")), classic, 1))
        }
        assertThrows<IllegalArgumentException> { engine.newGame(setup(3, first = PlayerId("nobody"))) }
        assertThrows<IllegalArgumentException> { engine.newGame(setup(config = RuleSetConfig("unknown", 1))) }
        assertThrows<IllegalArgumentException> { engine.newGame(setup(config = classic.copy(baseVersion = 99))) }
        assertThrows<IllegalArgumentException> { engine.newGame(setup(config = classic.copy(houseRules = setOf("x")))) }
        assertThrows<IllegalArgumentException> {
            engine.newGame(setup(config = classic.copy(paramOverrides = mapOf("bogus" to "1"))))
        }
        assertThrows<IllegalArgumentException> {
            engine.newGame(setup(config = classic.copy(paramOverrides = mapOf("startingCoins" to "many"))))
        }
    }

    @Test
    fun `상태는 JSON 왕복 후 동일하다`() {
        val state = engine.newGame(setup(6, seed = 3))
        val restored = EngineJson.decodeState(EngineJson.encodeState(state))
        assertThat(restored).isEqualTo(state)
        assertThat(restored.phase).isInstanceOf(Phase.AwaitingAction::class)
    }
}
