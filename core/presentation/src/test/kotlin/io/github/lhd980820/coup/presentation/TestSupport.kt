package io.github.lhd980820.coup.presentation

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.session.SessionSnapshot
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.engine.view.VisibleEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

val engine = GameEngines.create()

fun p(name: String) = PlayerId(name)

/**
 * 내(`me`) 손패가 [myHand]이고 상대 손패가 [others]인 게임 상태를 만든다. 엔진의 공개 API만 쓴다:
 * 원하는 손패가 나올 때까지 seed를 찾고, 상대 패는 [GameEngine.determinize]로 정한다.
 */
fun crafted(
    myHand: List<String>,
    others: Map<String, List<String>>,
    coins: Map<String, Int> = emptyMap(),
    first: String = "me",
): GameState {
    val ids = listOf(p("me")) + others.keys.map { p(it) }
    val config = BuiltinRules.classicConfig()
    val seed = (0L..50_000L).first { s ->
        val st = engine.newGame(GameSetup("ui", ids, config, s, p(first)))
        engine.view(st, Viewer.Player(p("me"))).me!!.hand.map { it.card.role.value }.sorted() == myHand.sorted()
    }
    val base = engine.newGame(GameSetup("ui", ids, config, seed, p(first)))
    val view = engine.view(base, Viewer.Player(p("me")))
    val remaining = view.ruleSet.roles.flatMap { r -> List(r.copies) { r.id } }.toMutableList()
    view.me!!.hand.forEach { remaining.remove(it.card.role) }
    val hands = others.mapValues { (_, roles) -> roles.map(::RoleId).onEach { check(remaining.remove(it)) } }
        .mapKeys { p(it.key) }
    check(coins.isEmpty()) { "coins override is not supported by crafted(); play commands instead" }
    return engine.determinize(view, HiddenAssignment(hands, remaining), seed = 1)
}

fun GameState.after(vararg commands: Command): GameState =
    commands.fold(this) { s, c ->
        (engine.apply(s, c) as io.github.lhd980820.coup.engine.command.ApplyResult.Accepted).state
    }

fun snapshotOf(state: GameState, viewer: String = "me", deadline: Long? = null): SessionSnapshot {
    val view = engine.view(state, Viewer.Player(p(viewer)))
    val info = view.seats.associateWith { SeatInfo("이름-${it.value}", SeatKind.AI) }
    return SessionSnapshot(view, deadline, emptyMap(), info)
}

/** 명령을 기록만 하는 가짜 세션. */
class FakeSession(initial: SessionSnapshot, override val me: PlayerId = p("me")) : GameSession {
    override val gameId: String = initial.view.gameId
    override val snapshot: MutableStateFlow<SessionSnapshot?> = MutableStateFlow(initial)
    override val events: MutableSharedFlow<VisibleEvent> = MutableSharedFlow(replay = 64, extraBufferCapacity = 64)
    override val connection: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)

    val submitted = mutableListOf<Command>()
    var nextResult: SubmitResult = SubmitResult.Ok
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var closed = false

    override suspend fun submit(command: Command): SubmitResult {
        gate?.await()
        submitted += command
        return nextResult
    }

    override suspend fun concede(): SubmitResult = submit(Command.Concede(me))
    override fun close() {
        closed = true
    }
}
