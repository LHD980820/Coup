package io.github.lhd980820.coup.engine.testing

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.Viewer
import kotlin.random.Random

/** 무작위 합법 수(가끔 도전·막기·기권)로 한 게임을 끝까지 진행하며 모든 중간 상태를 [onState]에 넘긴다. */
fun randomGame(
    seed: Long,
    players: Int,
    config: RuleSetConfig = BuiltinRules.classicConfig(),
    onState: (GameState) -> Unit = {},
): GameState = playRandom(seed, players, config, onState).final

/** 한 판의 기록: 시작 설정, 적용된 명령 전체, 최종 상태. 리플레이·백업 복원 테스트용. */
class GameLog(val setup: GameSetup, val commands: List<Command>, val final: GameState)

fun playRandom(
    seed: Long,
    players: Int,
    config: RuleSetConfig = BuiltinRules.classicConfig(),
    onState: (GameState) -> Unit = {},
): GameLog {
    val rnd = Random(seed)
    val setup = GameSetup("rand-$seed", List(players) { PlayerId("p$it") }, config, seed)
    var state = testEngine.newGame(setup)
    val commands = mutableListOf<Command>()
    onState(state)
    var steps = 0
    while (!state.isOver) {
        check(++steps < 3000) { "game $seed did not finish" }
        val command = if (rnd.nextInt(40) == 0) {
            Command.Concede(state.seats.filter { state.players.getValue(it).isAlive }.random(rnd), state.version)
        } else {
            val who = testEngine.pendingDeciders(state).random(rnd)
            randomCommand(state, who, rnd)
        }
        state = (testEngine.apply(state, command) as? ApplyResult.Accepted ?: error("seed $seed: rejected $command")).state
        commands += command
        onState(state)
    }
    return GameLog(setup, commands, state)
}

fun randomCommand(state: GameState, who: PlayerId, rnd: Random): Command =
    when (val request = checkNotNull(testEngine.legalOptions(state, who))) {
        is DecisionRequest.ChooseAction -> {
            val o = request.options.filter { it.selectable }.random(rnd)
            Command.DeclareAction(who, o.actionId, o.validTargets?.random(rnd), state.version)
        }
        is DecisionRequest.Respond -> when {
            request.canChallenge && rnd.nextInt(4) == 0 -> Command.Challenge(who, state.version)
            request.blockOptions.isNotEmpty() && rnd.nextInt(3) == 0 ->
                Command.Block(who, request.blockOptions.random(rnd).role, state.version)
            else -> Command.Pass(who, state.version)
        }
        is DecisionRequest.ChooseRevealCard -> Command.RevealCard(who, request.cards.random(rnd).id, state.version)
        is DecisionRequest.ChooseInfluenceToLose -> Command.LoseInfluence(who, request.cards.random(rnd).id, state.version)
        is DecisionRequest.ChooseExchange ->
            Command.ChooseExchange(who, request.candidates.shuffled(rnd).take(request.keepCount).map { it.id }, state.version)
    }

/** 테스트 전용: 진짜 상태에서 [viewer]가 모르는 정보를 그대로 꺼낸 "정답" 가정. */
fun trueAssignment(state: GameState, viewer: Viewer): HiddenAssignment {
    val me = (viewer as? Viewer.Player)?.id
    val myExchange = (state.phase as? Phase.AwaitingExchange)?.takeIf { it.player == me }
    val knownTop = myExchange?.let { it.candidates.size - it.keepCount } ?: 0
    return HiddenAssignment(
        hands = state.seats.filter { it != me }.associateWith { id -> state.players.getValue(id).hiddenCards.map { it.role } },
        deckOrder = state.deck.drop(knownTop).map { it.role },
    )
}

fun viewersOf(state: GameState): List<Viewer> = state.seats.map { Viewer.Player(it) } + Viewer.Spectator
