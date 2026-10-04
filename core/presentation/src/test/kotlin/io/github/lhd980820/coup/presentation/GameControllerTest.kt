package io.github.lhd980820.coup.presentation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GameControllerTest {
    private val table = crafted(
        myHand = listOf("duke", "captain"),
        others = mapOf("a" to listOf("contessa", "assassin"), "b" to listOf("ambassador", "duke")),
    )

    private class Rig(val session: FakeSession, val controller: GameController)

    private fun TestScope.rig(state: GameState = table): Rig {
        val session = FakeSession(snapshotOf(state))
        val controller = GameController(session, backgroundScope)
        runCurrent()
        return Rig(session, controller)
    }

    private fun Rig.show(state: GameState, deadline: Long? = null) {
        session.snapshot.value = snapshotOf(state, deadline = deadline)
    }

    private fun Rig.ui() = checkNotNull(controller.state.value)

    @Test
    fun `첫 스냅샷이 오면 상태가 만들어진다`() = runTest {
        val r = rig()
        assertThat(r.ui().decision).isNotNull().isInstanceOf(DecisionUi.Actions::class)
        assertThat(r.controller.state.value?.connection).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `대상 없는 행동은 바로 명령으로 간다 - 현재 버전 포함`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("income"))
        assertThat(r.session.submitted).containsExactly(Command.DeclareAction(p("me"), ActionId("income"), null, 0L))
    }

    @Test
    fun `대상이 필요한 행동은 대상 선택 단계를 거친다`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("steal"))
        runCurrent()
        assertThat(r.session.submitted).isEmpty()
        val pick = r.ui().decision as DecisionUi.PickTarget
        assertThat(pick.targets.map { it.value }).containsExactly("a", "b")

        r.controller.chooseTarget(p("b"))
        assertThat(r.session.submitted).containsExactly(Command.DeclareAction(p("me"), ActionId("steal"), p("b"), 0L))
    }

    @Test
    fun `대상 선택을 취소하면 행동 목록으로 돌아간다`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("steal"))
        r.controller.cancelTarget()
        assertThat(r.ui().decision).isNotNull().isInstanceOf(DecisionUi.Actions::class)
    }

    @Test
    fun `사용할 수 없는 행동이나 잘못된 대상은 무시된다`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("coup")) // 코인 부족
        r.controller.chooseAction(ActionId("nonexistent"))
        r.controller.chooseTarget(p("a")) // 대상 선택 단계가 아님
        r.controller.pass()
        r.controller.challenge()
        assertThat(r.session.submitted).isEmpty()
        r.controller.chooseAction(ActionId("steal"))
        r.controller.chooseTarget(p("me")) // 자기 자신은 대상이 아님
        assertThat(r.session.submitted).isEmpty()
    }

    @Test
    fun `응답 - 허용 도전 막기`() = runTest {
        val aid = table.after(Command.DeclareAction(p("me"), ActionId("income")), Command.DeclareAction(p("a"), ActionId("foreign_aid")))
        val r = rig(aid)
        r.controller.block(RoleId("contessa")) // 막을 수 없는 역할
        r.controller.challenge() // 주장 없는 행동은 도전 불가
        assertThat(r.session.submitted).isEmpty()
        r.controller.block(RoleId("duke"))
        assertThat(r.session.submitted).containsExactly(Command.Block(p("me"), RoleId("duke"), aid.version))
        val r1 = rig(aid)
        r1.controller.pass()
        assertThat(r1.session.submitted).containsExactly(Command.Pass(p("me"), aid.version))

        val tax = table.after(Command.DeclareAction(p("me"), ActionId("income")), Command.DeclareAction(p("a"), ActionId("tax")))
        val r2 = rig(tax)
        r2.controller.challenge()
        assertThat(r2.session.submitted).containsExactly(Command.Challenge(p("me"), tax.version))
    }

    @Test
    fun `카드 선택 후 확정해야 공개된다 - 다시 누르면 해제`() = runTest {
        val challenged = table.after(Command.DeclareAction(p("me"), ActionId("tax")), Command.Challenge(p("a")))
        val r = rig(challenged)
        val duke = (r.ui().decision as DecisionUi.PickCard).cards.first { it.role == RoleId("duke") }.id

        r.controller.confirmCard() // 아직 선택 없음
        assertThat(r.session.submitted).isEmpty()

        r.controller.selectCard(duke)
        assertThat((r.ui().decision as DecisionUi.PickCard).selected).isEqualTo(duke)
        r.controller.selectCard(duke)
        assertThat((r.ui().decision as DecisionUi.PickCard).selected).isNull()

        r.controller.selectCard(duke)
        r.controller.confirmCard()
        assertThat(r.session.submitted).containsExactly(Command.RevealCard(p("me"), duke, challenged.version))
    }

    @Test
    fun `교환은 정해진 장수만 고를 수 있고 채워야 확정된다`() = runTest {
        val exchanging = table.after(Command.DeclareAction(p("me"), ActionId("exchange")), Command.Pass(p("a")), Command.Pass(p("b")))
        val r = rig(exchanging)
        val ids = (r.ui().decision as DecisionUi.PickExchange).candidates.map { it.id }

        r.controller.toggleExchange(ids[0])
        r.controller.confirmExchange()
        assertThat(r.session.submitted).isEmpty() // 1장만 고름

        r.controller.toggleExchange(ids[1])
        r.controller.toggleExchange(ids[2]) // 정원 초과 — 무시
        assertThat((r.ui().decision as DecisionUi.PickExchange).selectedCount).isEqualTo(2)
        r.controller.toggleExchange(ids[1]) // 해제
        r.controller.toggleExchange(ids[2])
        r.controller.confirmExchange()
        assertThat(r.session.submitted).containsExactly(Command.ChooseExchange(p("me"), listOf(ids[0], ids[2]), exchanging.version))
    }

    @Test
    fun `새 결정이 오면 화면 위 선택 상태가 비워진다`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("steal"))
        assertThat(r.ui().decision).isNotNull().isInstanceOf(DecisionUi.PickTarget::class)
        r.show(table.after(Command.DeclareAction(p("me"), ActionId("income")), Command.DeclareAction(p("a"), ActionId("income")), Command.DeclareAction(p("b"), ActionId("income"))))
        runCurrent()
        assertThat(r.ui().decision).isNotNull().isInstanceOf(DecisionUi.Actions::class) // 다시 내 차례 — 대상 선택은 남아 있지 않다
    }

    @Test
    fun `전송 중에는 busy이고 그동안의 중복 입력은 무시된다`() = runTest {
        val r = rig()
        r.session.gate = CompletableDeferred()
        val first = launch { r.controller.chooseAction(ActionId("income")) }
        runCurrent()
        assertThat(r.ui().busy).isTrue()
        r.controller.chooseAction(ActionId("foreign_aid")) // 중복 탭
        r.session.gate!!.complete(Unit)
        first.join()
        assertThat(r.session.submitted).hasSize(1)
    }

    @Test
    fun `수락된 뒤에도 새 상태가 도착할 때까지 잠겨 있어 옛 버전으로 두 번째 탭이 나가지 않는다`() = runTest {
        val r = rig()
        r.controller.chooseAction(ActionId("income"))
        assertThat(r.ui().busy).isTrue() // 응답은 받았지만 새 화면 상태는 아직
        r.controller.chooseAction(ActionId("foreign_aid"))
        assertThat(r.session.submitted).hasSize(1)

        r.show(table.after(Command.DeclareAction(p("me"), ActionId("income"))))
        runCurrent()
        assertThat(r.ui().busy).isFalse() // 새 상태가 오면 풀린다
    }

    @Test
    fun `새 상태가 끝내 오지 않아도 안전 시간이 지나면 잠금이 풀린다`() = runTest {
        val session = FakeSession(snapshotOf(table))
        val controller = GameController(session, backgroundScope, busyTimeoutMillis = 2_000)
        runCurrent()
        controller.chooseAction(ActionId("income"))
        assertThat(controller.state.value!!.busy).isTrue()
        testScheduler.advanceTimeBy(1_999)
        runCurrent()
        assertThat(controller.state.value!!.busy).isTrue()
        testScheduler.advanceTimeBy(2)
        runCurrent()
        assertThat(controller.state.value!!.busy).isFalse()
    }

    @Test
    fun `거절과 네트워크 오류는 메시지로 알린다`() = runTest {
        val r = rig()
        val messages = mutableListOf<UiMessage>()
        backgroundScope.launch { r.controller.messages.collect { messages += it } }
        runCurrent()

        r.session.nextResult = SubmitResult.Rejected(Rejection.STALE_VERSION)
        r.controller.chooseAction(ActionId("income"))
        assertThat(r.ui().busy).isFalse() // 거절되면 바로 다시 시도할 수 있다
        r.session.nextResult = SubmitResult.NetworkError
        r.controller.chooseAction(ActionId("income"))
        runCurrent()
        assertThat(messages).containsExactly(UiMessage.Rejected(Rejection.STALE_VERSION), UiMessage.NetworkError)
        assertThat(r.ui().busy).isFalse()
    }

    @Test
    fun `기권은 명령으로 간다`() = runTest {
        val r = rig()
        r.controller.concede()
        assertThat(r.session.submitted).containsExactly(Command.Concede(p("me"), 0L))
    }

    @Test
    fun `이벤트는 로그와 애니메이션 신호가 된다 - 허용과 코인 증감은 로그에 싣지 않는다`() = runTest {
        val r = rig()
        val effects = mutableListOf<UiEffect>()
        backgroundScope.launch { r.controller.effects.collect { effects += it } }
        runCurrent()

        val result = engine.apply(table, Command.DeclareAction(p("me"), ActionId("tax"))) as io.github.lhd980820.coup.engine.command.ApplyResult.Accepted
        engine.projectEvents(result.events, io.github.lhd980820.coup.engine.view.Viewer.Player(p("me"))).forEach { r.session.events.emit(it) }
        val passed = engine.apply(result.state, Command.Pass(p("a"))) as io.github.lhd980820.coup.engine.command.ApplyResult.Accepted
        engine.projectEvents(passed.events, io.github.lhd980820.coup.engine.view.Viewer.Player(p("me"))).forEach { r.session.events.emit(it) }
        runCurrent()

        assertThat(r.ui().log).containsExactly(
            LogEntry.ActionDeclared(p("me"), ActionId("tax"), null, setOf(RoleId("duke"))),
        )
        assertThat(effects).containsExactly(UiEffect.Passed(p("a")))
    }

    @Test
    fun `로그는 최대 길이까지만 유지된다`() = runTest {
        val session = FakeSession(snapshotOf(table))
        val controller = GameController(session, backgroundScope, maxLog = 3)
        runCurrent()
        var state = table
        val me = io.github.lhd980820.coup.engine.view.Viewer.Player(p("me"))
        listOf("me", "a", "b", "me", "a", "b").forEach { who ->
            val result = engine.apply(state, Command.DeclareAction(p(who), ActionId("income"))) as io.github.lhd980820.coup.engine.command.ApplyResult.Accepted
            engine.projectEvents(result.events, me).forEach { session.events.emit(it) }
            state = result.state
        }
        runCurrent()
        val log = controller.state.value!!.log
        assertThat(log).hasSize(3)
        assertThat(log.last()).isInstanceOf(LogEntry.Resolved::class) // 가장 최근 것이 남는다
    }

    @Test
    fun `연결 상태가 바뀌면 반영되고 close는 세션을 닫는다`() = runTest {
        val r = rig()
        r.session.connection.value = ConnectionState.HOST_LOST
        runCurrent()
        assertThat(r.ui().connection).isEqualTo(ConnectionState.HOST_LOST)
        r.controller.close()
        assertThat(r.session.closed).isTrue()
        assertThat(r.ui().decision).isNotNull()
    }
}
