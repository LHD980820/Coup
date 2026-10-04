package io.github.lhd980820.coup.runtime.seat

import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.SeatKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * AI 좌석(설계 §6.2). 에이전트에게 **자기 시점의 뷰와 이벤트만** 전달한다.
 * 결정은 [decisionDispatcher]에서 계산하고, 연출용으로 [thinkTime] 범위만큼 기다린 뒤 제출한다(테스트에서는 0).
 * 에이전트가 예외를 던지면 아무것도 제출하지 않는다 — 권한자의 타임아웃 기본 수가 대신한다.
 */
public class AiSeat(
    override val playerId: PlayerId,
    private val agent: AiAgent,
    private val scope: CoroutineScope,
    private val decisionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val thinkTime: ClosedRange<Duration> = Duration.ZERO..Duration.ZERO,
    seed: Long = 0,
) : SeatController {
    override val kind: SeatKind = SeatKind.AI

    private val agentLock = Mutex() // observe와 decide가 동시에 에이전트 상태를 건드리지 않도록
    private val random = Random(seed)

    override suspend fun onStart(view: PlayerView) {
        agentLock.withLock { agent.onGameStart(view) }
    }

    override suspend fun onEvents(view: PlayerView, events: List<VisibleEvent>) {
        agentLock.withLock { agent.observe(events, view) }
    }

    override suspend fun onDecisionRequired(view: PlayerView, request: DecisionRequest, submit: CommandSubmitter) {
        scope.launch {
            val wait = pickThinkTime()
            if (wait > Duration.ZERO) delay(wait)
            val command = try {
                agentLock.withLock { withContext(decisionDispatcher) { agent.decide(view, request) } }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return@launch // 타임아웃 기본 수에 맡긴다
            }
            submit(command) // 그사이 상태가 바뀌었으면 STALE_VERSION으로 거절되고 다음 결정 요청을 기다린다
        }
    }

    private fun pickThinkTime(): Duration {
        val min = thinkTime.start.inWholeMilliseconds
        val max = thinkTime.endInclusive.inWholeMilliseconds
        return if (max <= min) thinkTime.start else random.nextLong(min, max + 1).milliseconds
    }
}
