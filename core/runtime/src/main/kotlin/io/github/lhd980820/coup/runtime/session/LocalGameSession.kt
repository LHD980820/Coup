package io.github.lhd980820.coup.runtime.session

import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.AuthorityListener
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.GameAuthority
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.seat.AiSeat
import io.github.lhd980820.coup.runtime.seat.LocalHumanSeat
import io.github.lhd980820.coup.runtime.seat.SeatController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/** 싱글플레이 좌석 구성: 사람 1명 + AI들. */
public data class LocalPlayer(val id: PlayerId, val displayName: String, val agent: AiAgent? = null)

/**
 * 싱글플레이 / 튜토리얼 세션(설계 §6.3): 이 기기의 [GameAuthority] + 사람 좌석 1개 + AI 좌석들.
 * UI에는 사람 시점의 뷰와 이벤트만 노출한다.
 */
public class LocalGameSession(
    private val engine: GameEngine,
    setup: GameSetup,
    players: List<LocalPlayer>,
    timeoutPolicy: TimeoutPolicy,
    clock: Clock,
    scope: CoroutineScope,
    aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    aiThinkTime: ClosedRange<Duration> = Duration.ZERO..Duration.ZERO,
) : GameSession {
    private val human: LocalPlayer = players.singleOrNull { it.agent == null }
        ?: throw IllegalArgumentException("exactly one human (agent == null) is required")
    private val viewer = Viewer.Player(human.id)
    private val seatInfo = players.associate { p ->
        p.id to SeatInfo(p.displayName, if (p.agent == null) SeatKind.LOCAL_HUMAN else SeatKind.AI)
    }

    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    private val _events = MutableSharedFlow<VisibleEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)

    private val authority: GameAuthority

    override val gameId: String = setup.gameId
    override val me: PlayerId = human.id
    override val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()
    override val events: SharedFlow<VisibleEvent> = _events.asSharedFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    init {
        require(players.map { it.id } == setup.seats) { "players must match the setup seats in order" }
        val seats: Map<PlayerId, SeatController> = players.associate { p ->
            p.id to (
                p.agent?.let { agent ->
                    AiSeat(p.id, agent, scope, aiDispatcher, aiThinkTime, seed = p.id.value.hashCode().toLong())
                } ?: LocalHumanSeat(p.id)
                )
        }
        authority = GameAuthority(engine, engine.newGame(setup), seats, timeoutPolicy, clock, scope)
        authority.addListener(
            AuthorityListener { state, events, deadlines ->
                _snapshot.value = SessionSnapshot(
                    view = engine.view(state, viewer),
                    myDeadline = deadlines[me],
                    othersDeadlines = deadlines - me,
                    seatInfo = seatInfo,
                )
                engine.projectEvents(events, viewer).forEach { _events.emit(it) }
            },
        )
        authority.start()
    }

    override suspend fun submit(command: Command): SubmitResult {
        require(command.actor == me) { "a local session can only submit commands for ${me.value}" }
        return when (val result = authority.submitFrom(me, command)) {
            is ApplyResult.Accepted -> SubmitResult.Ok
            is ApplyResult.Rejected -> SubmitResult.Rejected(result.reason)
        }
    }

    override suspend fun concede(): SubmitResult = submit(Command.Concede(me))

    override fun close() {
        authority.stop()
        _connection.value = ConnectionState.CLOSED
    }
}
