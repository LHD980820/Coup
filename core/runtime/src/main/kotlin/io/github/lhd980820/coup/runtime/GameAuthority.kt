package io.github.lhd980820.coup.runtime

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.runtime.seat.SeatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 상태 변경 통지. 퍼블리셔(로컬 UI, Firestore 등)가 구현한다. 이 안에서 [GameAuthority.submit]을 직접 호출하면 안 된다. */
public fun interface AuthorityListener {
    public suspend fun onStateChanged(state: GameState, events: List<GameEvent>, deadlines: Map<PlayerId, Long>)
}

/**
 * 한 게임의 진짜 상태를 보유하고 엔진을 실행하는 권한자(설계 §6.1). 싱글플레이와 멀티플레이 호스트가 공유한다.
 *
 * - 명령은 [Mutex]로 직렬화된다. 동시에 들어온 응답 중 먼저 처리된 것만 유효하고, 나머지는 엔진이 거절한다.
 * - 상태가 바뀌면 결정권자 좌석에 결정을 요청하고, [TimeoutPolicy]에 따라 마감 시각을 정한다.
 *   마감이 지나면 엔진의 기본 명령([GameEngine.timeoutCommand])을 대신 제출한다.
 * - 같은 결정(같은 턴의 같은 결정 요청)이 이어지는 동안은 마감을 다시 잡지 않는다. 예: 다른 사람이 통과해도 내 응답 마감은 그대로.
 * - 좌석·리스너 통지는 하나의 전달 코루틴에서 상태 변경 순서대로 이루어진다.
 * - 좌석이 낸 명령이 버전 차이(STALE_VERSION)로만 거절됐고 그 좌석의 결정 내용이 그대로라면 버전 조건 없이 다시 적용한다.
 *   (예: AI 응답자 두 명이 같은 뷰로 동시에 결정 — 다른 사람의 통과로 버전만 올라갔을 뿐 결정은 여전히 유효하다.)
 */
public class GameAuthority(
    private val engine: GameEngine,
    initial: GameState,
    private val seats: Map<PlayerId, SeatController>,
    private val timeoutPolicy: TimeoutPolicy,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private class Update(val state: GameState, val events: List<GameEvent>, val deadlines: Map<PlayerId, Long>)
    private class Timer(val signature: Any, val deadline: Long, val job: Job)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initial)
    private val _deadlines = MutableStateFlow<Map<PlayerId, Long>>(emptyMap())
    private val listeners = mutableListOf<AuthorityListener>()
    private val updates = Channel<Update>(Channel.UNLIMITED)
    private val timers = mutableMapOf<PlayerId, Timer>()
    private val notified = mutableMapOf<PlayerId, Any>() // 전달 코루틴 전용
    private var dispatcher: Job? = null

    public val state: StateFlow<GameState> = _state.asStateFlow()

    /** 결정권자별 마감 시각(epoch millis). 제한 없는 결정은 빠진다. */
    public val deadlines: StateFlow<Map<PlayerId, Long>> = _deadlines.asStateFlow()

    /** [start] 전에 등록한다. */
    public fun addListener(listener: AuthorityListener) {
        check(dispatcher == null) { "add listeners before start()" }
        listeners += listener
    }

    public fun start() {
        check(dispatcher == null) { "already started" }
        dispatcher = scope.launch {
            val first = _state.value
            seats.forEach { (id, seat) -> seat.onStart(engine.view(first, Viewer.Player(id))) }
            for (update in updates) deliver(update)
        }
        scope.launch {
            mutex.withLock {
                refreshTimers(_state.value)
                updates.trySend(Update(_state.value, emptyList(), _deadlines.value))
            }
        }
    }

    public fun stop() {
        timers.values.forEach { it.job.cancel() }
        timers.clear()
        updates.close()
        dispatcher?.cancel()
    }

    /** 명령 제출. 수락되면 상태가 바뀌고 좌석·리스너에 순서대로 통지된다. */
    public suspend fun submit(command: Command): ApplyResult = mutex.withLock { applyLocked(command) }

    /** 좌석이 [signature] 결정에 대해 낸 명령. */
    private suspend fun submitFromSeat(player: PlayerId, signature: Any, command: Command): ApplyResult = mutex.withLock {
        val result = applyLocked(command)
        val stale = result is ApplyResult.Rejected && result.reason == Rejection.STALE_VERSION
        if (!stale) return@withLock result
        val state = _state.value
        val current = engine.legalOptions(state, player) ?: return@withLock result
        if (signatureOf(state, current) != signature) return@withLock result
        applyLocked(command.withoutExpectedVersion())
    }

    private fun applyLocked(command: Command): ApplyResult {
        val result = engine.apply(_state.value, command)
        if (result is ApplyResult.Accepted) {
            _state.value = result.state
            refreshTimers(result.state)
            updates.trySend(Update(result.state, result.events, _deadlines.value))
        }
        return result
    }

    /** 결정의 동일성 판단 기준: 같은 턴의 같은 결정 요청이면 같은 결정이다. */
    private fun signatureOf(state: GameState, request: DecisionRequest): Any = state.turn.number to request

    private fun refreshTimers(state: GameState) {
        val deciders = engine.pendingDeciders(state)
        val requests = deciders.associateWith { checkNotNull(engine.legalOptions(state, it)) }

        timers.entries.removeAll { (player, timer) ->
            val keep = player in deciders && timer.signature == signatureOf(state, requests.getValue(player))
            if (!keep) timer.job.cancel()
            !keep
        }
        for ((player, request) in requests) {
            if (player in timers) continue
            val seatKind = seats[player]?.kind ?: SeatKind.REMOTE_HUMAN
            val duration = timeoutPolicy.durationFor(request, seatKind) ?: continue
            val signature = signatureOf(state, request)
            val job = scope.launch {
                delay(duration)
                fireTimeout(player, signature)
            }
            timers[player] = Timer(signature, clock.nowMillis() + duration.inWholeMilliseconds, job)
        }
        _deadlines.value = timers.mapValues { it.value.deadline }
    }

    private suspend fun fireTimeout(player: PlayerId, signature: Any) = mutex.withLock {
        val timer = timers[player] ?: return@withLock
        if (timer.signature != signature) return@withLock
        timers.remove(player) // 자기 자신을 취소하지 않도록 먼저 뺀다
        val state = _state.value
        if (player !in engine.pendingDeciders(state)) return@withLock
        applyLocked(engine.timeoutCommand(state, player))
    }

    private suspend fun deliver(update: Update) {
        listeners.forEach { it.onStateChanged(update.state, update.events, update.deadlines) }
        for ((id, seat) in seats) {
            val viewer = Viewer.Player(id)
            val view = engine.view(update.state, viewer)
            seat.onEvents(view, engine.projectEvents(update.events, viewer))
            val request = view.myDecision
            if (request == null) {
                notified.remove(id)
                continue
            }
            val signature = signatureOf(update.state, request)
            if (notified[id] != signature) {
                notified[id] = signature
                seat.onDecisionRequired(view, request) { command -> submitFromSeat(id, signature, command) }
            }
        }
    }
}

/** 버전 조건을 뗀 같은 명령. */
internal fun Command.withoutExpectedVersion(): Command = when (this) {
    is Command.DeclareAction -> copy(expectedVersion = null)
    is Command.Pass -> copy(expectedVersion = null)
    is Command.Challenge -> copy(expectedVersion = null)
    is Command.Block -> copy(expectedVersion = null)
    is Command.RevealCard -> copy(expectedVersion = null)
    is Command.LoseInfluence -> copy(expectedVersion = null)
    is Command.ChooseExchange -> copy(expectedVersion = null)
    is Command.Concede -> copy(expectedVersion = null)
}
