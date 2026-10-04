package io.github.lhd980820.coup.runtime.session

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.transport.AckResult
import io.github.lhd980820.coup.runtime.transport.GameTransport
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 게스트 기기의 세션(설계 §6.3). **엔진이 없다** — 호스트가 내 좌석용으로 투영해 보낸 뷰를 그대로 보여주고,
 * 내 명령을 호스트에 보낸다. 그래서 게스트 기기에는 다른 사람의 비공개 정보가 물리적으로 도달하지 않는다.
 */
public class RemoteGameSession(
    override val gameId: String,
    override val me: PlayerId,
    private val transport: GameTransport.Guest,
    private val seatInfo: Map<PlayerId, SeatInfo>,
    scope: CoroutineScope,
    private val ackTimeout: Duration = 10.seconds,
) : GameSession {
    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    private val _events = MutableSharedFlow<VisibleEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    private val _connection = MutableStateFlow(ConnectionState.RECONNECTING)
    private val jobs = mutableListOf<Job>()
    private var lastVersion = -1L

    override val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()
    override val events: SharedFlow<VisibleEvent> = _events.asSharedFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    init {
        jobs += scope.launch {
            transport.observeMyView(gameId, me).collect { envelope ->
                if (envelope.version <= lastVersion) return@collect // 순서가 뒤바뀐 갱신은 버린다
                lastVersion = envelope.version
                _snapshot.value = SessionSnapshot(
                    view = envelope.view,
                    myDeadline = envelope.deadlines[me],
                    othersDeadlines = envelope.deadlines - me,
                    seatInfo = seatInfo,
                )
                envelope.events.forEach { _events.emit(it) }
            }
        }
        jobs += scope.launch { transport.connection(gameId).collect { _connection.value = it } }
    }

    override suspend fun submit(command: Command): SubmitResult {
        require(command.actor == me) { "a remote session can only submit commands for ${me.value}" }
        val ack = try {
            withTimeoutOrNull(ackTimeout) { transport.sendCommand(gameId, command) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: TransportException) {
            null
        } ?: return SubmitResult.NetworkError
        return when (ack) {
            is AckResult.Accepted -> SubmitResult.Ok
            is AckResult.Rejected -> SubmitResult.Rejected(ack.reason)
        }
    }

    override suspend fun concede(): SubmitResult = submit(Command.Concede(me))

    override fun close() {
        jobs.forEach { it.cancel() }
        _connection.value = ConnectionState.CLOSED
    }
}
