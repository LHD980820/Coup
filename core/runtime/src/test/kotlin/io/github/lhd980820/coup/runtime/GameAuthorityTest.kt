package io.github.lhd980820.coup.runtime

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
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.runtime.seat.AiSeat
import io.github.lhd980820.coup.runtime.seat.LocalHumanSeat
import io.github.lhd980820.coup.runtime.seat.SeatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class GameAuthorityTest {
    private val engine = GameEngines.create()
    private fun ids(n: Int) = List(n) { PlayerId("p$it") }

    private fun newGame(n: Int, seed: Long = 1, first: PlayerId? = PlayerId("p0")): GameState =
        engine.newGame(GameSetup("g", ids(n), BuiltinRules.classicConfig(), seed, first))

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private fun TestScope.authority(
        state: GameState,
        seats: Map<PlayerId, SeatController>,
        policy: TimeoutPolicy = TimeoutPolicy.standard(),
        scope: CoroutineScope = backgroundScope,
    ) = GameAuthority(engine, state, seats, policy, clock(), scope)

    private fun humans(n: Int) = ids(n).associateWith { LocalHumanSeat(it) as SeatController }

    @Test
    fun `AI 6명은 끝까지 게임을 마치고 리스너는 버전 순서대로 모든 변경을 받는다`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seats = ids(6).associateWith { AiSeat(it, RandomAgent(it.value.hashCode().toLong()), backgroundScope, dispatcher) as SeatController }
        val a = authority(newGame(6), seats)
        val versions = mutableListOf<Long>()
        a.addListener { state, _, _ -> versions += state.version }
        a.start()
        runUntil { a.state.value.isOver }
        runCurrent() // 마지막 통지 전달
        assertThat(versions).isEqualTo((0L..a.state.value.version).toList())
    }

    @Test
    fun `아무도 입력하지 않으면 타임아웃 기본 수로 게임이 끝까지 진행된다`() = runTest {
        val a = authority(newGame(3), humans(3))
        a.start()
        runUntil { a.state.value.isOver }
        assertThat(testScheduler.currentTime > 0).isTrue()
    }

    @Test
    fun `행동 마감은 30초이고 정확히 그 시각에 기본 수가 제출된다`() = runTest {
        val a = authority(newGame(3), humans(3))
        a.start()
        runCurrent()
        assertThat(a.deadlines.value).isEqualTo(mapOf(PlayerId("p0") to 30_000L))
        advanceTimeBy(29_999)
        runCurrent()
        assertThat(a.state.value.version).isEqualTo(0L)
        advanceTimeBy(1)
        runCurrent()
        assertThat(a.state.value.version).isEqualTo(1L) // 수입이 대신 선언됨
    }

    @Test
    fun `다른 사람이 통과해도 내 응답 마감은 다시 잡히지 않는다`() = runTest {
        val a = authority(newGame(3), humans(3))
        a.start()
        runCurrent()
        a.submit(Command.DeclareAction(PlayerId("p0"), ActionId("tax")))
        runCurrent()
        assertThat(a.deadlines.value).isEqualTo(mapOf(PlayerId("p1") to 15_000L, PlayerId("p2") to 15_000L))

        advanceTimeBy(10_000)
        a.submit(Command.Pass(PlayerId("p1")))
        runCurrent()
        assertThat(a.deadlines.value).isEqualTo(mapOf(PlayerId("p2") to 15_000L))

        advanceTimeBy(5_000)
        runCurrent()
        // p2의 마감(15초)에 통과 처리 → 세금 해결 → p1의 행동 선택(마감 15+30=45초)
        assertThat(a.deadlines.value).isEqualTo(mapOf(PlayerId("p1") to 45_000L))
    }

    @Test
    fun `마감 직전 사람이 제출하면 사람 명령이 처리되고 기본 수는 무효가 된다`() = runTest {
        val a = authority(newGame(2), humans(2))
        a.start()
        runCurrent()
        advanceTimeBy(29_000)
        val r = a.submit(Command.DeclareAction(PlayerId("p0"), ActionId("foreign_aid")))
        assertThat(r is ApplyResult.Accepted).isTrue()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(a.state.value.version).isEqualTo(1L) // 30초 시점의 기본 수(수입)는 제출되지 않음
    }

    @Test
    fun `동시에 들어온 응답은 직렬화되어 하나만 수락된다`() = runTest {
        val a = authority(newGame(4), humans(4), TimeoutPolicy.None)
        a.start()
        runCurrent()
        a.submit(Command.DeclareAction(PlayerId("p0"), ActionId("tax")))
        val v = a.state.value.version
        val results = mutableListOf<ApplyResult>()
        listOf("p1", "p2", "p3").forEach { p ->
            launch { results += a.submit(Command.Challenge(PlayerId(p), expectedVersion = v)) }
        }
        advanceUntilIdle()
        assertThat(results.count { it is ApplyResult.Accepted }).isEqualTo(1)
        assertThat(results.filterIsInstance<ApplyResult.Rejected>().map { it.reason }.toSet())
            .isEqualTo(setOf(Rejection.STALE_VERSION))
    }

    @Test
    fun `시간 제한 없는 정책이면 마감이 없다`() = runTest {
        val a = authority(newGame(2), humans(2), TimeoutPolicy.standard(unlimitedLocalHuman = true))
        a.start()
        runCurrent()
        assertThat(a.deadlines.value).isEqualTo(emptyMap())
        advanceTimeBy(3_600_000)
        runCurrent()
        assertThat(a.state.value.version).isEqualTo(0L)
    }

    @Test
    fun `AI가 예외를 던지면 타임아웃 기본 수로 진행된다`() = runTest {
        val broken = object : AiAgent {
            override fun decide(view: PlayerView, request: DecisionRequest): Command = error("boom")
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seats = mapOf<PlayerId, SeatController>(
            PlayerId("p0") to AiSeat(PlayerId("p0"), broken, backgroundScope, dispatcher),
            PlayerId("p1") to AiSeat(PlayerId("p1"), broken, backgroundScope, dispatcher),
        )
        val a = authority(newGame(2), seats)
        a.start()
        runUntil { a.state.value.isOver }
    }

    @Test
    fun `AI는 자기 시점의 뷰만 받는다`() = runTest {
        val seen = mutableListOf<PlayerView>()
        val spy = object : AiAgent {
            private val inner = RandomAgent(3)
            override fun decide(view: PlayerView, request: DecisionRequest): Command {
                seen += view
                return inner.decide(view, request)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seats = mapOf<PlayerId, SeatController>(
            PlayerId("p0") to AiSeat(PlayerId("p0"), spy, backgroundScope, dispatcher),
            PlayerId("p1") to AiSeat(PlayerId("p1"), RandomAgent(4), backgroundScope, dispatcher),
        )
        val a = authority(newGame(2), seats)
        a.start()
        runUntil { a.state.value.isOver }
        assertThat(seen.isNotEmpty()).isTrue()
        assertThat(seen.all { it.me?.id == PlayerId("p0") && it.opponents.single().id == PlayerId("p1") }).isTrue()
    }

    @Test
    fun `AI 응답자들이 같은 뷰로 동시에 결정해도 기다림 없이 모두 반영된다`() = runTest {
        val passer = object : AiAgent {
            override fun decide(view: PlayerView, request: DecisionRequest): Command =
                Command.Pass(checkNotNull(view.me).id, view.version)
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seats = mapOf<PlayerId, SeatController>(
            PlayerId("p0") to LocalHumanSeat(PlayerId("p0")),
            PlayerId("p1") to AiSeat(PlayerId("p1"), passer, backgroundScope, dispatcher),
            PlayerId("p2") to AiSeat(PlayerId("p2"), passer, backgroundScope, dispatcher),
            PlayerId("p3") to AiSeat(PlayerId("p3"), passer, backgroundScope, dispatcher),
        )
        val a = authority(newGame(4), seats)
        a.start()
        runCurrent()
        a.submit(Command.DeclareAction(PlayerId("p0"), ActionId("tax")))
        runCurrent() // 가상 시간은 흐르지 않는다
        assertThat(engine.pendingDeciders(a.state.value)).isEqualTo(setOf(PlayerId("p1")))
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `정지하면 더 이상 기본 수가 제출되지 않는다`() = runTest {
        val a = authority(newGame(2), humans(2))
        a.start()
        runCurrent()
        a.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(a.state.value.version).isEqualTo(0L)
    }
}
