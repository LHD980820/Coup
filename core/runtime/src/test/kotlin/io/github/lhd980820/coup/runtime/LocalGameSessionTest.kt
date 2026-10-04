package io.github.lhd980820.coup.runtime

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.LocalGameSession
import io.github.lhd980820.coup.runtime.session.LocalPlayer
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalGameSessionTest {
    private val engine = GameEngines.create()
    private val me = PlayerId("me")

    private fun TestScope.session(policy: TimeoutPolicy = TimeoutPolicy.standard(unlimitedLocalHuman = true)): LocalGameSession {
        val players = listOf(LocalPlayer(me, "나")) +
            List(3) { LocalPlayer(PlayerId("ai$it"), "AI $it", RandomAgent(seed = it.toLong())) }
        return LocalGameSession(
            engine = engine,
            setup = GameSetup("single", players.map { it.id }, BuiltinRules.classicConfig(), seed = 11, firstPlayer = me),
            players = players,
            timeoutPolicy = policy,
            clock = Clock { testScheduler.currentTime },
            scope = backgroundScope,
            aiDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `스냅샷은 내 시점이고 내 결정을 담는다`() = runTest {
        val s = session()
        runCurrent()
        val snap = checkNotNull(s.snapshot.value)
        assertThat(snap.view.me?.id).isEqualTo(me)
        assertThat(snap.view.myDecision).isNotNull().isInstanceOf(DecisionRequest.ChooseAction::class)
        assertThat(snap.myDeadline).isEqualTo(null) // 싱글플레이 사람 좌석은 무제한
        assertThat(snap.seatInfo.getValue(PlayerId("ai0")).kind).isEqualTo(SeatKind.AI)
        assertThat(s.connection.value).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `내가 행동하면 AI들이 응답하고 다시 내 차례가 돌아온다`() = runTest {
        val s = session()
        runCurrent()
        assertThat(s.submit(Command.DeclareAction(me, ActionId("income")))).isEqualTo(SubmitResult.Ok)
        runUntil { s.snapshot.value?.view?.let { it.result != null || it.myDecision != null } == true }
        val view = checkNotNull(s.snapshot.value).view
        // 게임이 끝났거나, 다시 사람의 결정을 기다린다(AI 좌석은 스스로 진행한다)
        assertThat(view.result != null || view.myDecision != null).isTrue()
    }

    @Test
    fun `거절된 명령은 이유와 함께 돌아오고 다른 좌석 명령은 낼 수 없다`() = runTest {
        val s = session()
        runCurrent()
        assertThat(s.submit(Command.DeclareAction(me, ActionId("coup"), PlayerId("ai0"))))
            .isEqualTo(SubmitResult.Rejected(Rejection.INSUFFICIENT_COINS))
        assertThrows<IllegalArgumentException> { s.submit(Command.Pass(PlayerId("ai0"))) }
    }

    @Test
    fun `이벤트 스트림은 내 시점으로 투영되어 남의 비공개 카드가 오지 않는다`() = runTest {
        val s = session(TimeoutPolicy.standard())
        val received = mutableListOf<VisibleEvent>()
        backgroundScope.launch { s.events.collect { received += it } }
        runUntil { s.snapshot.value?.view?.result != null } // 사람은 아무것도 안 함 → 타임아웃 기본 수로 끝까지
        assertThat(received.isNotEmpty()).isTrue()
        received.map { it.event }.forEach { e ->
            when (e) {
                is GameEvent.CardReplaced -> assertThat(e.player).isEqualTo(me)
                is GameEvent.ExchangeDrawn -> assertThat(e.player).isEqualTo(me)
                else -> Unit
            }
        }
    }

    @Test
    fun `기권하면 내 게임이 끝나고 닫으면 연결 상태가 CLOSED`() = runTest {
        val s = session()
        runCurrent()
        assertThat(s.concede()).isEqualTo(SubmitResult.Ok)
        runUntil { s.snapshot.value?.view?.me?.hand?.all { it.revealed } == true }
        val view = checkNotNull(s.snapshot.value).view
        assertThat(view.me?.hand?.all { it.revealed } == true).isTrue()
        s.close()
        assertThat(s.connection.value).isEqualTo(ConnectionState.CLOSED)
    }

    @Test
    fun `사람이 정확히 한 명이어야 한다`() = runTest {
        assertThrows<IllegalArgumentException> {
            LocalGameSession(
                engine, GameSetup("x", listOf(PlayerId("a"), PlayerId("b")), BuiltinRules.classicConfig(), 1),
                listOf(LocalPlayer(PlayerId("a"), "a"), LocalPlayer(PlayerId("b"), "b")),
                TimeoutPolicy.None, Clock { 0 }, backgroundScope,
            )
        }
    }
}
