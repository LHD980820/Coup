package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.TurnInfo
import io.github.lhd980820.coup.engine.rng.DeterministicRng
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.serialization.ENGINE_SCHEMA_VERSION

internal class DefaultGameEngine(private val registry: RuleSetRegistry) : GameEngine {

    override fun newGame(setup: GameSetup): GameState {
        val rules = registry.build(setup.ruleSetConfig)
        val params = rules.params
        val seats = setup.seats

        require(seats.size in params.minPlayers..params.maxPlayers) {
            "player count ${seats.size} is outside ${params.minPlayers}..${params.maxPlayers}"
        }
        require(seats.toSet().size == seats.size) { "duplicate seats: $seats" }
        require(setup.firstPlayer == null || setup.firstPlayer in seats) { "first player is not seated: ${setup.firstPlayer}" }

        // 덱 생성: 역할 정의 순서대로 CardId를 0부터 부여한 뒤 셔플한다.
        var nextId = 0
        val ordered = rules.roles.flatMap { role -> List(role.copies) { Card(CardId(nextId++), role.id) } }
        var rng = DeterministicRng.ofSeed(setup.seed)
        val (shuffled, afterShuffle) = rng.shuffled(ordered)
        rng = afterShuffle

        // 분배: 좌석 순서대로 덱 위에서 handSize장씩.
        var deck = shuffled
        val players = seats.associateWith { id ->
            val hand = deck.take(params.handSize)
            deck = deck.drop(params.handSize)
            PlayerState(id, coins = params.startingCoins, influences = hand.map { Influence(it) })
        }

        val first = setup.firstPlayer ?: rng.nextInt(seats.size).let { (index, next) ->
            rng = next
            seats[index]
        }

        return GameState(
            schemaVersion = ENGINE_SCHEMA_VERSION,
            gameId = setup.gameId,
            version = 0,
            ruleSetConfig = setup.ruleSetConfig,
            seats = seats,
            turn = TurnInfo(number = 1, activePlayer = first),
            eliminationOrder = emptyList(),
            players = players,
            deck = deck,
            phase = Phase.AwaitingAction(first),
            currentAction = null,
            stack = emptyList(),
            rng = rng,
        )
    }
}
