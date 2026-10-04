package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.Viewer

/** 테스트용 대결장: 엔진을 직접 돌리며 각 좌석의 에이전트에게 자기 시점 정보만 준다. */
object Arena {
    val engine: GameEngine = GameEngines.create()

    class Result(val winner: PlayerId, val decisions: Int)

    fun play(
        agents: List<AiAgent>,
        seed: Long,
        config: RuleSetConfig = BuiltinRules.classicConfig(),
        onDecision: (PlayerId) -> Unit = {},
    ): Result {
        val seats = agents.indices.map { PlayerId("s$it") }
        val bySeat = seats.zip(agents).toMap()
        var state = engine.newGame(GameSetup("arena-$seed", seats, config, seed))
        seats.forEach { bySeat.getValue(it).onGameStart(engine.view(state, Viewer.Player(it))) }
        var decisions = 0
        while (!state.isOver) {
            check(decisions < 5_000) { "arena game $seed did not finish" }
            val who = engine.pendingDeciders(state).first()
            val view = engine.view(state, Viewer.Player(who))
            val command = bySeat.getValue(who).decide(view, checkNotNull(view.myDecision))
            val result = engine.apply(state, command)
            check(result is ApplyResult.Accepted) { "seed $seed: ${who.value} produced rejected $command -> $result" }
            decisions++
            onDecision(who)
            state = result.state
            seats.forEach { seat ->
                val viewer = Viewer.Player(seat)
                bySeat.getValue(seat).observe(engine.projectEvents(result.events, viewer), engine.view(state, viewer))
            }
        }
        val winner = checkNotNull(engine.view(state, Viewer.Spectator).result).winner
        return Result(winner, decisions)
    }

    /** [hero]가 0번 좌석이 되도록 좌석을 돌려가며 [games]판을 치른 승률. 다른 좌석은 [villain]. */
    fun winRate(hero: (Long) -> AiAgent, villain: (Long) -> AiAgent, players: Int, games: Int, seedBase: Long): Double {
        var wins = 0
        repeat(games) { g ->
            val heroSeat = g % players
            val agents = List(players) { i -> if (i == heroSeat) hero(seedBase + g) else villain(seedBase + g * 31L + i) }
            if (play(agents, seedBase + g).winner == PlayerId("s$heroSeat")) wins++
        }
        return wins.toDouble() / games
    }
}
