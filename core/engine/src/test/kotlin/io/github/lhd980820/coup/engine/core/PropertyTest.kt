package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.testing.TestRules
import io.github.lhd980820.coup.engine.testing.playRandom
import io.github.lhd980820.coup.engine.testing.testEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 설계 §12.3: 불변식 / 속성 기반 테스트. 룰셋 조합 x 인원수 x 다수의 seed. */
class PropertyTest {

    private fun assertInvariants(state: GameState, expectedCards: Int) {
        val all = state.deck + state.players.values.flatMap { p -> p.influences.map { it.card } }
        assertThat(all).hasSize(expectedCards)
        assertThat(all.map { it.id }.toSet()).hasSize(expectedCards)
        state.players.values.forEach { p ->
            assertThat(p.coins).isGreaterThanOrEqualTo(0)
            check(p.isAlive == (p.id !in state.eliminationOrder)) { "elimination bookkeeping broken for ${p.id}" }
        }
        if (!state.isOver) {
            val deciders = testEngine.pendingDeciders(state)
            check(deciders.isNotEmpty()) { "no pending decider in a running game (v${state.version})" }
            deciders.forEach { check(testEngine.legalOptions(state, it) != null) { "decider $it has no legal options" } }
        } else {
            check(testEngine.pendingDeciders(state).isEmpty())
            check(state.stack.isEmpty()) { "finished game has leftover resolution steps" }
        }
    }

    private fun check(configs: List<RuleSetConfig>, players: Int, gamesPerConfig: Int, seedBase: Long) {
        configs.forEachIndexed { ci, config ->
            repeat(gamesPerConfig) { g ->
                val cards = TestRules.registry().build(config).totalCards
                playRandom(seedBase + ci * 10_000L + players * 100L + g, players, config) { assertInvariants(it, cards) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 5, 6])
    fun `모든 하우스룰 조합에서 무작위 게임은 불변식을 지키며 종료한다`(players: Int) {
        check(TestRules.combos, players, gamesPerConfig = 60, seedBase = 1_000_000)
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 4, 6])
    fun `같은 seed와 같은 명령 로그를 재생하면 같은 최종 상태다(리플레이 결정성)`(players: Int) {
        TestRules.combos.forEach { config ->
            repeat(15) { g ->
                val log = playRandom(seed = 7_000L + players * 50 + g, players = players, config = config)
                var replay = testEngine.newGame(log.setup)
                log.commands.forEach { replay = (testEngine.apply(replay, it) as ApplyResult.Accepted).state }
                assertThat(replay).isEqualTo(log.final)
                assertThat(EngineJson.encodeState(replay)).isEqualTo(EngineJson.encodeState(log.final))
            }
        }
    }

    @Test
    fun `진행 중 상태를 JSON으로 백업·복원해도 이어서 같은 결과가 나온다`() {
        TestRules.combos.forEachIndexed { ci, config ->
            repeat(10) { g ->
                val log = playRandom(seed = 31_000L + ci * 100 + g, players = 4, config = config)
                val cut = log.commands.size / 2
                var live = testEngine.newGame(log.setup)
                log.commands.take(cut).forEach { live = (testEngine.apply(live, it) as ApplyResult.Accepted).state }

                var restored = EngineJson.decodeState(EngineJson.encodeState(live))
                assertThat(restored).isEqualTo(live)
                log.commands.drop(cut).forEach {
                    live = (testEngine.apply(live, it) as ApplyResult.Accepted).state
                    restored = (testEngine.apply(restored, it) as ApplyResult.Accepted).state
                }
                assertThat(restored).isEqualTo(live)
                assertThat(restored).isEqualTo(log.final)
            }
        }
    }

    @Test
    fun `불법 명령은 거절되고 상태를 바꾸지 않는다`() {
        val log = playRandom(seed = 99, players = 4)
        var state = testEngine.newGame(log.setup)
        val rnd = kotlin.random.Random(99)
        log.commands.forEach { command ->
            // 결정권자가 아닌 사람의 행동 선언은 항상 거절되어야 한다
            val outsider = state.seats.filter { it !in testEngine.pendingDeciders(state) && state.players.getValue(it).isAlive }
            if (outsider.isNotEmpty()) {
                val bad = io.github.lhd980820.coup.engine.command.Command.DeclareAction(
                    outsider.random(rnd), io.github.lhd980820.coup.engine.model.ActionId("income"),
                )
                assertThat(testEngine.apply(state, bad) is ApplyResult.Rejected).isEqualTo(true)
            }
            state = (testEngine.apply(state, command) as ApplyResult.Accepted).state
        }
    }
}
