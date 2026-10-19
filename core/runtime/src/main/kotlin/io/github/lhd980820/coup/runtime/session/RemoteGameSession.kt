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
import kotlinx.coroutines.delay
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
    private val retryInterval: Duration = DEFAULT_RETRY_INTERVAL,
) : GameSession {
    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    private val _events = MutableSharedFlow<VisibleEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    private val _connection = MutableStateFlow(ConnectionState.RECONNECTING)
    private val jobs = mutableListOf<Job>()
    private var lastVersion = -1L

    override val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()
    override val events: SharedFlow<VisibleEvent> = _events.asSharedFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    // 연결 상태 = 전송이 알려 주는 상태(방장 생존 신호) + 내 뷰 구독이 살아 있는가. 구독이 끊긴 동안은 "다시 연결 중"이다.
    // (init에서 띄운 코루틴이 쓰므로 init보다 먼저 선언한다 — 뒤에 두면 다른 스레드에서 초기화 전의 값을 본다)
    private val stateLock = Any()
    private var transportState = ConnectionState.RECONNECTING
    private var viewBroken = false

    init {
        // 구독이 오류로 끊기면(네트워크, 일시적 거부) 잠시 뒤 다시 구독한다 — 그대로 두면 화면이 옛 상태에 멈춘다.
        jobs += scope.launch {
            resilient(onFailure = { setViewBroken(true) }) {
                transport.observeMyView(gameId, me).collect { envelope ->
                    setViewBroken(false)
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
        }
        jobs += scope.launch {
            resilient(onFailure = { setTransportState(ConnectionState.RECONNECTING) }) {
                transport.connection(gameId).collect { setTransportState(it) }
            }
        }
    }

    private fun setTransportState(state: ConnectionState) = synchronized(stateLock) {
        transportState = state
        refreshConnection()
    }

    private fun setViewBroken(broken: Boolean) = synchronized(stateLock) {
        if (viewBroken == broken) return@synchronized
        viewBroken = broken
        refreshConnection()
    }

    private fun refreshConnection() {
        if (_connection.value == ConnectionState.CLOSED) return
        _connection.value = if (viewBroken && transportState == ConnectionState.CONNECTED) ConnectionState.RECONNECTING else transportState
    }

    private suspend fun resilient(onFailure: () -> Unit, block: suspend () -> Unit) {
        while (true) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                onFailure()
                delay(retryInterval)
            }
        }
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
