package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/**
 * 무작위 합법 수로 끝까지 진행하며 불변식을 검사하는 초기 안전망.
 * 응답은 Pass / (가능하면) 25% 도전 / 25% 막기. (Phase 1 11단계에서 확장)
 */
class RandomPlayoutTest {

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 5, 6])
    fun `무작위 합법 수 플레이는 불변식을 지키며 종료한다`(players: Int) {
        repeat(40) { gameIndex ->
            val seed = players * 1000L + gameIndex
            val rnd = Random(seed)
            var state = testEngine.newGame(
                GameSetup("fuzz-$seed", List(players) { io.github.lhd980820.coup.engine.model.PlayerId("p$it") }, BuiltinRules.classicConfig(), seed),
            )
            val initialCards = state.deck.size + players * 2
            var steps = 0
            while (!state.isOver) {
                check(++steps < 3000) { "game $seed did not finish" }
                val deciders = testEngine.pendingDeciders(state).toList()
                assertThat(deciders.isNotEmpty()).isTrue()
                deciders.forEach { assertThat(testEngine.legalOptions(state, it)).isNotNull() }

                val who = deciders.random(rnd)
                val command = when (val request = testEngine.legalOptions(state, who)!!) {
                    is DecisionRequest.ChooseAction -> {
                        val options = request.options.filter { it.selectable }
                        val o = options.random(rnd)
                        Command.DeclareAction(who, o.actionId, o.validTargets?.random(rnd), state.version)
                    }
                    is DecisionRequest.Respond -> when {
                        request.canChallenge && rnd.nextInt(4) == 0 -> Command.Challenge(who, state.version)
                        request.blockOptions.isNotEmpty() && rnd.nextInt(3) == 0 ->
                            Command.Block(who, request.blockOptions.random(rnd).role, state.version)
                        else -> Command.Pass(who, state.version)
                    }
                    is DecisionRequest.ChooseExchange ->
                        Command.ChooseExchange(who, request.candidates.shuffled(rnd).take(request.keepCount).map { it.id }, state.version)
                    is DecisionRequest.ChooseRevealCard -> Command.RevealCard(who, request.cards.random(rnd).id, state.version)
                    is DecisionRequest.ChooseInfluenceToLose -> Command.LoseInfluence(who, request.cards.random(rnd).id, state.version)
                }
                val result = testEngine.apply(state, command) as? ApplyResult.Accepted
                    ?: error("seed $seed: legal command $command was rejected")
                assertThat(result.state.version).isEqualTo(state.version + 1)
                state = result.state
                assertInvariants(state, initialCards)
            }
        }
    }

    private fun assertInvariants(state: GameState, expectedCards: Int) {
        val cards = state.deck + state.players.values.flatMap { p -> p.influences.map { it.card } }
        assertThat(cards).hasSize(expectedCards)
        assertThat(cards.map { it.id }.toSet()).hasSize(expectedCards)
        state.players.values.forEach { p ->
            assertThat(p.coins).isGreaterThanOrEqualTo(0)
            assertThat(p.isAlive == (p.id !in state.eliminationOrder)).isTrue()
        }
    }
}
