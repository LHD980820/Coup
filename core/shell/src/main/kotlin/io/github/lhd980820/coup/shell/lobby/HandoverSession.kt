package io.github.lhd980820.coup.shell.lobby

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.EVENT_REPLAY
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.SessionSnapshot
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** [HandoverSession]이 방장 자리를 옮길 때 쓰는 일(실행기가 채운다). */
internal interface HandoverOps {
    /** 방장 이어받기를 시도한다(규칙이 "방장이 충분히 오래 조용함"을 확인한다). 이어받았으면 true. */
    suspend fun takeOver(): Boolean

    /** 이어받은 뒤: 권한자 백업에서 방장 세션을 연다. 판이 이미 끝났으면 null. */
    suspend fun openHost(): GameSession?

    /** 다른 사람이 이어받은 뒤: 게스트 세션으로 다시 붙는다. */
    suspend fun openGuest(): GameSession

    /** 이어받기 순번(0부터): 방장을 뺀 사람 좌석 중 내 자리. 동시에 몰리지 않게 순번만큼 늦게 시도한다. */
    suspend fun rank(): Int
}

/** 이어받기 시각. 게스트는 방장 끊김을 [after] 동안 본 뒤(+ 순번 × [stagger]) 시도하고, 거부되면 [retry]마다 다시 시도한다. */
internal class HandoverTiming(val after: Duration, val stagger: Duration, val retry: Duration)

/**
 * 방장 이어받기(ADR 0017)를 품은 게임 세션. 화면은 이 세션 하나만 보고, 안에서 게스트 세션과 방장 세션이 바뀐다.
 * - 게스트: 방장과 연결이 끊긴 채([ConnectionState.HOST_LOST]) 기다리면 방장을 이어받고, 마지막 게시 상태(권한자 백업)에서 방장 세션을 연다.
 *   방장이 그사이 돌아오면 그만둔다. 여럿이 동시에 시도해도 규칙(트랜잭션)이 한 명만 받아들인다.
 * - 방장: 끊긴 사이 다른 사람이 이어받았으면([ConnectionState.HOST_MOVED]) 게스트 세션으로 다시 붙는다.
 * 바뀌는 동안 화면에는 "다시 연결 중"으로 보인다. 같은 좌석(내 [me])으로 계속 둔다.
 */
internal class HandoverSession(
    initial: GameSession,
    private val ops: HandoverOps,
    private val timing: HandoverTiming,
    parent: CoroutineScope,
) : GameSession {
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    private val current = MutableStateFlow(initial)

    private val _snapshot = MutableStateFlow(initial.snapshot.value)
    private val _events = MutableSharedFlow<VisibleEvent>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    private val _connection = MutableStateFlow(initial.connection.value)

    override val gameId: String = initial.gameId
    override val me: PlayerId? = initial.me
    override val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()
    override val events: SharedFlow<VisibleEvent> = _events.asSharedFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** 지금 이 기기가 방장 세션을 돌리는가. */
    val isHosting: Boolean get() = current.value is HostGameSession

    init {
        scope.launch { current.collectLatest { s -> s.snapshot.collect { if (it != null) _snapshot.value = it } } }
        scope.launch { current.collectLatest { s -> s.events.collect { _events.emit(it) } } }
        scope.launch {
            current.collectLatest { s ->
                // 방장 자리가 옮겨 가는 중에는 "다시 연결 중"으로 보인다(옛 세션의 멈춤·닫힘은 화면에 보이지 않는다)
                s.connection.collect { _connection.value = if (it == ConnectionState.HOST_MOVED) ConnectionState.RECONNECTING else it }
            }
        }
        scope.launch {
            current.collectLatest { s -> if (s is HostGameSession) watchDemotion(s) else watchForTakeover(s) }
        }
    }

    private suspend fun watchDemotion(s: GameSession) {
        s.connection.first { it == ConnectionState.HOST_MOVED }
        while (true) {
            val guest = attempt { ops.openGuest() }
            if (guest != null) {
                swap(guest)
                return
            }
            delay(timing.retry)
        }
    }

    private suspend fun watchForTakeover(s: GameSession) {
        while (true) {
            s.connection.first { it == ConnectionState.HOST_LOST }
            val wait = timing.after + timing.stagger * (attempt { ops.rank() } ?: 0)
            // 기다리는 동안 방장이 돌아오면(끊김이 풀리면) 처음부터 다시 지켜본다
            if (withTimeoutOrNull(wait) { s.connection.first { it != ConnectionState.HOST_LOST } } != null) continue
            while (s.connection.value == ConnectionState.HOST_LOST) {
                if (attempt { ops.takeOver() } == true) {
                    // 이어받았다: 백업에서 방장 세션을 연다(판이 이미 끝났으면 그대로 둔다)
                    while (true) {
                        val opened = try {
                            ops.openHost()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: TransportException) {
                            delay(timing.retry)
                            continue
                        }
                        if (opened != null) swap(opened)
                        return
                    }
                }
                delay(timing.retry)
            }
        }
    }

    private fun swap(next: GameSession) {
        val old = current.value
        current.value = next
        old.close()
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        null
    }

    override suspend fun submit(command: Command): SubmitResult = current.value.submit(command)
    override suspend fun concede(): SubmitResult = current.value.concede()

    override fun close() {
        scope.cancel()
        current.value.close()
        _connection.value = ConnectionState.CLOSED
    }
}
