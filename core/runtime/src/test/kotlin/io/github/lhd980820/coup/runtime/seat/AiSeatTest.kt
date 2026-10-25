package io.github.lhd980820.coup.runtime.seat

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.Viewer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** AI 좌석: 계산과 연출용 대기 시간을 겹치고, 에이전트가 실패해도 좌석이 망가지지 않는다. */
class AiSeatTest {
    private val engine = GameEngines.create()
    private val me = PlayerId("p0")
    private val view: PlayerView = engine.view(
        engine.newGame(GameSetup("seat", listOf(me, PlayerId("p1")), BuiltinRules.classicConfig(), 1, me)),
        Viewer.Player(me),
    )
    private val request = checkNotNull(view.myDecision)

    /** 좌석의 일은 backgroundScope에서 돈다(advanceUntilIdle은 기다리지 않는다): 시간을 넉넉히 흘린다. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `생각하는 척 기다리는 동안 이미 계산을 시작하고, 대기가 끝난 뒤 제출한다`() = runTest {
        var decidedAt = -1L
        val agent = object : AiAgent {
            private val inner = RandomAgent(1)
            override fun decide(view: PlayerView, request: DecisionRequest): Command {
                decidedAt = testScheduler.currentTime
                return inner.decide(view, request)
            }
        }
        var submittedAt = -1L
        val seat = AiSeat(me, agent, backgroundScope, StandardTestDispatcher(testScheduler), thinkTime = 1.seconds..1.seconds)
        seat.onDecisionRequired(view, request) { submittedAt = testScheduler.currentTime; ApplyResult.Rejected(Rejection.STALE_VERSION) }
        settle()
        assertThat(decidedAt).isEqualTo(0L) // 대기를 기다리지 않고 바로 계산한다
        assertThat(submittedAt).isEqualTo(1_000L) // 제출은 연출 시간이 지난 뒤
    }

    @Test
    fun `에이전트가 예외를 던지면 아무것도 제출하지 않고, 좌석과 범위는 그대로 살아 있다`() = runTest {
        var fail = true
        val agent = object : AiAgent {
            private val inner = RandomAgent(2)
            override fun decide(view: PlayerView, request: DecisionRequest): Command {
                if (fail) error("boom")
                return inner.decide(view, request)
            }
        }
        val submitted = mutableListOf<Command>()
        val seat = AiSeat(me, agent, backgroundScope, StandardTestDispatcher(testScheduler))
        seat.onDecisionRequired(view, request) { submitted += it; ApplyResult.Rejected(Rejection.STALE_VERSION) }
        settle()
        assertThat(submitted.size).isEqualTo(0)
        assertThat(backgroundScope.isActive).isTrue()
        fail = false
        seat.onDecisionRequired(view, request) { submitted += it; ApplyResult.Rejected(Rejection.STALE_VERSION) }
        settle()
        assertThat(submitted.size).isEqualTo(1)
    }
}
