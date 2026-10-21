package io.github.lhd980820.coup.runtime.session

import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.RatingPolicy
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.AuthorityListener
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.GameAuthority
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.seat.AiSeat
import io.github.lhd980820.coup.runtime.seat.LocalHumanSeat
import io.github.lhd980820.coup.runtime.seat.RemoteSeat
import io.github.lhd980820.coup.runtime.seat.SeatController
import io.github.lhd980820.coup.runtime.transport.AckResult
import io.github.lhd980820.coup.runtime.transport.GameResultRecord
import io.github.lhd980820.coup.runtime.transport.GameTransport
import io.github.lhd980820.coup.runtime.transport.Publication
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.runtime.transport.ViewEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 방장 기기의 좌석 구성. 멀티플레이에서 [Remote]의 [PlayerId]는 보통 사용자 uid다. */
public sealed interface HostSeat {
    public val id: PlayerId
    public val displayName: String

    /** 방장 본인(이 기기의 사람). 정확히 한 명이어야 한다. */
    public data class Local(override val id: PlayerId, override val displayName: String) : HostSeat

    /** 다른 기기의 사람. [uid]는 전송 계층이 인증한 발신자와 대조하는 값이다. */
    public data class Remote(override val id: PlayerId, override val displayName: String, val uid: String = id.value) : HostSeat

    public data class Bot(override val id: PlayerId, override val displayName: String, val agent: AiAgent) : HostSeat
}

/**
 * 방장 기기 권위 모델의 호스트(설계 §8.1): [GameAuthority] + 방장 본인 좌석 + 원격 사람 좌석 + 봇.
 * 상태가 바뀔 때마다 좌석별로 투영한 뷰만 [GameTransport.Host]로 내보내고, 게스트 명령은 [GameAuthority.submitFrom]으로 처리한다.
 *
 * 보안 규칙: 명령의 행위자는 발신자(uid)에 대응하는 원격 좌석이어야 한다. 발신자가 다른 좌석의 명령을 보내거나
 * 봇/방장의 명령을 보내면 거절한다.
 *
 * @param initialState 새 게임([GameEngine.newGame]) 또는 백업 복구([EngineJson.decodeState])로 만든 상태
 */
public class HostGameSession(
    private val engine: GameEngine,
    initialState: GameState,
    seats: List<HostSeat>,
    private val transport: GameTransport.Host,
    timeoutPolicy: TimeoutPolicy,
    clock: Clock,
    private val scope: CoroutineScope,
    private val ratingPolicy: RatingPolicy = TableRatingPolicy,
    aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    aiThinkTime: ClosedRange<Duration> = Duration.ZERO..Duration.ZERO,
    heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    private val retryInterval: Duration = DEFAULT_RETRY_INTERVAL,
) : GameSession {
    private val local: HostSeat.Local = seats.filterIsInstance<HostSeat.Local>().singleOrNull()
        ?: throw IllegalArgumentException("exactly one local (host) seat is required")
    private val remoteByUid: Map<String, HostSeat.Remote> = seats.filterIsInstance<HostSeat.Remote>().associateBy { it.uid }
    private val hostViewer = Viewer.Player(local.id)

    private val seatInfo = seats.associate { s ->
        s.id to SeatInfo(
            s.displayName,
            when (s) {
                is HostSeat.Local -> SeatKind.LOCAL_HUMAN
                is HostSeat.Remote -> SeatKind.REMOTE_HUMAN
                is HostSeat.Bot -> SeatKind.AI
            },
        )
    }

    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    private val _events = MutableSharedFlow<VisibleEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val authority: GameAuthority
    private var intake: Job? = null
    private var heartbeat: Job? = null
    private var republish: Job? = null
    private val publishLock = Mutex()

    @Volatile
    private var latest: Delivery? = null

    /** 아직 게시하지 못한 명령 기록. 게시가 성공하면 그 버전까지 지운다(실패하면 다음 게시가 함께 보낸다 — 기록에 빈틈이 생기지 않게). */
    private val unpublishedLog = mutableListOf<io.github.lhd980820.coup.runtime.AppliedCommand>()
    private var finished = false

    override val gameId: String = initialState.gameId
    override val me: PlayerId = local.id
    override val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()
    override val events: SharedFlow<VisibleEvent> = _events.asSharedFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** 레이팅 반영 대상인가(D5): 봇, 하우스룰, 파라미터 변경이 모두 없을 때만. */
    private val rated: Boolean = seats.none { it is HostSeat.Bot } &&
        initialState.ruleSetConfig.houseRules.isEmpty() && initialState.ruleSetConfig.paramOverrides.isEmpty()

    init {
        require(seats.map { it.id } == initialState.seats) { "seats must match the state's seats in order" }
        val controllers: Map<PlayerId, SeatController> = seats.associate { s ->
            s.id to when (s) {
                is HostSeat.Local -> LocalHumanSeat(s.id)
                is HostSeat.Remote -> RemoteSeat(s.id)
                is HostSeat.Bot -> AiSeat(s.id, s.agent, scope, aiDispatcher, aiThinkTime, seed = s.id.value.hashCode().toLong())
            }
        }
        authority = GameAuthority(engine, initialState, controllers, timeoutPolicy, clock, scope)
        authority.addRecorder { record -> synchronized(unpublishedLog) { unpublishedLog += record } }
        authority.addListener(AuthorityListener { state, events, deadlines -> onStateChanged(state, events, deadlines) })
        authority.start()
        heartbeat = scope.launch {
            while (true) {
                try {
                    transport.heartbeat(gameId)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // 다음 주기에 다시 시도한다. 게임 진행에는 영향 없다.
                }
                delay(heartbeatInterval)
            }
        }
        // 명령 수신은 전송 오류로 멈추면 안 된다 — 멈추면 이후 모든 게스트 명령이 영영 처리되지 않는다. 오류가 나면 잠시 뒤 다시 구독한다.
        intake = scope.launch {
            while (true) {
                try {
                    transport.incomingCommands(gameId).collect { incoming ->
                        val ack = handleRemoteCommand(incoming.senderUid, incoming.command, incoming.commandId)
                        acknowledge(incoming.commandId, ack)
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: TransportException) {
                    delay(retryInterval)
                }
            }
        }
    }

    /**
     * 처리 결과를 게스트에게 돌려준다. 쓰기가 실패해도 몇 번 다시 시도한다. 끝내 못 쓰면 포기한다 —
     * 명령은 이미 적용됐고, 게스트는 응답 시간 초과로 [SubmitResult.NetworkError]를 받은 뒤 최신 뷰를 보고 다시 판단한다.
     */
    private suspend fun acknowledge(commandId: String, ack: AckResult) {
        repeat(ACK_ATTEMPTS) { attempt ->
            try {
                transport.acknowledge(gameId, commandId, ack)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: TransportException) {
                if (attempt < ACK_ATTEMPTS - 1) delay(retryInterval)
            }
        }
    }

    private suspend fun handleRemoteCommand(senderUid: String, command: Command, commandId: String): AckResult {
        val seat = remoteByUid[senderUid] ?: return AckResult.Rejected(Rejection.NOT_YOUR_DECISION)
        return when (val result = authority.submitFrom(seat.id, command, ref = commandId)) {
            is ApplyResult.Accepted -> AckResult.Accepted(result.state.version)
            is ApplyResult.Rejected -> AckResult.Rejected(result.reason)
        }
    }

    private suspend fun onStateChanged(state: GameState, events: List<io.github.lhd980820.coup.engine.event.GameEvent>, deadlines: Map<PlayerId, Long>) {
        _snapshot.value = SessionSnapshot(
            view = engine.view(state, hostViewer),
            myDeadline = deadlines[me],
            othersDeadlines = deadlines - me,
            seatInfo = seatInfo,
        )
        engine.projectEvents(events, hostViewer).forEach { _events.emit(it) }

        fun envelope(viewer: Viewer) = ViewEnvelope(
            version = state.version,
            view = engine.view(state, viewer),
            events = engine.projectEvents(events, viewer),
            deadlines = deadlines,
        )
        val publication = Publication(
            version = state.version,
            views = remoteByUid.values.associate { it.id to envelope(Viewer.Player(it.id)) },
            spectator = envelope(Viewer.Spectator),
            authorityBackup = EngineJson.encodeState(state),
            log = synchronized(unpublishedLog) { unpublishedLog.filter { it.version <= state.version } },
        )
        val delivery = Delivery(publication, if (state.isOver) resultOf(state) else null)
        republish?.cancel()
        latest = delivery
        if (deliver(delivery)) {
            if (_connection.value == ConnectionState.RECONNECTING) _connection.value = ConnectionState.CONNECTED
            return
        }
        // 게시하지 못했다. 다음 상태 변경을 기다리면 안 된다 — 모두가 이 상태를 보고 움직여야 하는 경우(게스트 차례) 영영 멈춘다.
        // 최신 상태를 성공할 때까지 다시 게시한다. 그사이 새 상태가 오면 그것이 이 일을 넘겨받는다.
        _connection.value = ConnectionState.RECONNECTING
        republish = scope.launch {
            while (latest === delivery) {
                delay(retryInterval)
                if (latest === delivery && deliver(delivery)) {
                    _connection.value = ConnectionState.CONNECTED
                    return@launch
                }
            }
        }
    }

    /** 게시 한 건(+ 끝난 게임이면 결과). 한 번에 하나씩만 보낸다 — 옛 게시가 새 게시 뒤에 도착해 뷰를 되돌리지 않게. */
    private class Delivery(val publication: Publication, val result: GameResultRecord?)

    private suspend fun deliver(delivery: Delivery): Boolean = publishLock.withLock {
        if (latest !== delivery) return@withLock true // 이미 더 새 상태가 있다(그쪽이 보낸다)
        try {
            transport.publish(gameId, delivery.publication)
            synchronized(unpublishedLog) { unpublishedLog.removeAll { it.version <= delivery.publication.version } }
            if (delivery.result != null && !finished) {
                transport.finish(gameId, delivery.result)
                finished = true // 결과를 실제로 쓴 뒤에만(실패하면 다시 게시할 때 다시 쓴다)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
    }

    private fun resultOf(state: GameState): GameResultRecord {
        val ranking = checkNotNull(engine.view(state, Viewer.Spectator).result).ranking
        return GameResultRecord(
            ranking = ranking,
            ruleSetConfig = state.ruleSetConfig,
            rated = rated,
            ratingDeltas = if (rated) ratingPolicy.ratingDeltas(ranking) else emptyMap(),
        )
    }

    override suspend fun submit(command: Command): SubmitResult {
        require(command.actor == me) { "the host session can only submit commands for ${me.value}" }
        return when (val result = authority.submitFrom(me, command)) {
            is ApplyResult.Accepted -> SubmitResult.Ok
            is ApplyResult.Rejected -> SubmitResult.Rejected(result.reason)
        }
    }

    override suspend fun concede(): SubmitResult = submit(Command.Concede(me))

    override fun close() {
        intake?.cancel()
        heartbeat?.cancel()
        republish?.cancel()
        authority.stop()
        _connection.value = ConnectionState.CLOSED
    }
}

/** 방장 생존 신호 주기(설계 §8.2: 10초). */
public val DEFAULT_HEARTBEAT_INTERVAL: Duration = 10.seconds

/** 명령 확인 쓰기·명령 구독이 실패했을 때 다시 시도하기까지의 간격. */
public val DEFAULT_RETRY_INTERVAL: Duration = 1.seconds

private const val ACK_ATTEMPTS = 5
