package io.github.lhd980820.coup.presentation.tutorial

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.PublicPhase
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.presentation.engine
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TutorialTest {
    private fun TestScope.open(lesson: TutorialLesson): GuidedSession =
        TutorialSessionFactory.create(lesson, engine, backgroundScope, Clock { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))

    private fun TestScope.untilHuman(session: GuidedSession) {
        runCurrent()
        repeat(200) {
            if (session.snapshot.value?.view?.myDecision != null || session.progress.value.completed) return
            testScheduler.advanceTimeBy(100)
            runCurrent()
        }
        error("no human decision")
    }

    @Test
    fun `모든 과정은 약속된 손패와 선 플레이어로 시작한다`() {
        TutorialLessons.all.forEach { lesson ->
            val state = engine.newGame(
                GameSetup("t", listOf(TutorialLesson.HUMAN, TutorialLesson.TUTOR), BuiltinRules.classicConfig(), lesson.seed, lesson.firstPlayer),
            )
            fun hand(p: io.github.lhd980820.coup.engine.model.PlayerId) = engine.view(state, Viewer.Player(p)).me!!.hand.map { it.card.role }
            if (lesson.humanHand.isNotEmpty()) assertThat(hand(TutorialLesson.HUMAN), lesson.id).containsExactlyInAnyOrder(*lesson.humanHand.toTypedArray())
            if (lesson.tutorHand.isNotEmpty()) assertThat(hand(TutorialLesson.TUTOR), lesson.id).containsExactlyInAnyOrder(*lesson.tutorHand.toTypedArray())
            val phase = engine.view(state, Viewer.Player(TutorialLesson.HUMAN)).phase as PublicPhase.AwaitingAction
            assertThat(phase.actor, lesson.id).isEqualTo(lesson.firstPlayer)
        }
    }

    @Test
    fun `모든 과정은 안내대로 두면 완료된다`() = runTest {
        TutorialLessons.all.forEach { lesson ->
            val session = open(lesson)
            lesson.steps.forEachIndexed { i, step ->
                untilHuman(session)
                assertThat(session.progress.value.messageKey, lesson.id).isEqualTo(step.messageKey)
                val view = session.snapshot.value!!.view
                val command = commandFor(step.expect, view)
                assertThat(session.submit(command), "${lesson.id}#$i").isEqualTo(SubmitResult.Ok)
                runCurrent()
            }
            assertThat(session.progress.value.completed, lesson.id).isTrue()
            assertThat(session.progress.value.messageKey).isEqualTo(lesson.completionKey)
            session.close()
        }
    }

    private fun commandFor(expect: TutorialExpect, view: io.github.lhd980820.coup.engine.view.PlayerView): Command {
        val me = view.me!!.id
        val v = view.version
        return when (expect) {
            is TutorialExpect.Action -> {
                val option = (view.myDecision as io.github.lhd980820.coup.engine.view.DecisionRequest.ChooseAction).options.first { it.actionId == expect.action }
                Command.DeclareAction(me, expect.action, option.validTargets?.firstOrNull(), v)
            }
            TutorialExpect.Pass -> Command.Pass(me, v)
            TutorialExpect.Challenge -> Command.Challenge(me, v)
            is TutorialExpect.Block -> Command.Block(me, expect.role, v)
        }
    }

    @Test
    fun `도전 과정 - 선생은 거짓 주장을 하고 사용자가 도전하면 선생이 영향력을 잃는다`() = runTest {
        val session = open(TutorialLessons.CHALLENGE)
        untilHuman(session)
        val view = session.snapshot.value!!.view
        assertThat(view.currentAction).isNotNull()
        assertThat(view.currentAction!!.actionId).isEqualTo(ActionId("tax"))
        session.submit(Command.Challenge(view.me!!.id, view.version))
        runCurrent()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        val after = session.snapshot.value!!.view
        assertThat(after.opponent(TutorialLesson.TUTOR)!!.revealed.size).isEqualTo(1)
        session.close()
    }

    @Test
    fun `지시와 다른 수는 거절되고 진행은 그대로이며 횟수가 기록된다`() = runTest {
        val session = open(TutorialLessons.BASICS)
        untilHuman(session)
        val view = session.snapshot.value!!.view
        val result = session.submit(Command.DeclareAction(view.me!!.id, ActionId("foreign_aid"), null, view.version))
        assertThat(result).isEqualTo(SubmitResult.Rejected(Rejection.NOT_YOUR_DECISION))
        assertThat(session.progress.value.stepIndex).isEqualTo(0)
        assertThat(session.progress.value.offScript).isEqualTo(1)
        // 게임 상태는 바뀌지 않았다
        assertThat(session.snapshot.value!!.view.version).isEqualTo(view.version)
        session.close()
    }

    @Test
    fun `기권은 언제나 허용된다`() = runTest {
        val session = open(TutorialLessons.BASICS)
        untilHuman(session)
        assertThat(session.concede()).isEqualTo(SubmitResult.Ok)
        session.close()
    }

    @Test
    fun `컨트롤러로 버튼만 눌러 과정을 끝낸다`() = runTest {
        val session = open(TutorialLessons.BLOCK)
        val controller = GameController(session, backgroundScope)
        untilHuman(session)
        runCurrent()
        val decision = controller.state.value!!.decision
        assertThat(decision).isNotNull().isInstanceOf(DecisionUi.Respond::class)
        controller.pass() // 지시와 다름 -> 거절
        assertThat(session.progress.value.completed).isEqualTo(false)
        controller.block(io.github.lhd980820.coup.engine.model.RoleId("ambassador"))
        runCurrent()
        assertThat(session.progress.value.completed).isTrue()
        controller.close()
    }

    @Test
    fun `대본이 어긋나도 선생은 합법적인 수만 둔다`() = runTest {
        // 대본에 맞지 않는 상황(비어 있는 대본)에서도 끝까지 진행되어야 한다
        val lesson = TutorialLessons.BASICS.copy(tutorScript = emptyList())
        val session = open(lesson)
        untilHuman(session)
        var view = session.snapshot.value!!.view
        session.submit(Command.DeclareAction(view.me!!.id, ActionId("income"), null, view.version))
        untilHuman(session)
        view = session.snapshot.value!!.view
        assertThat(view.phase).isInstanceOf(PublicPhase.AwaitingAction::class)
        session.close()
    }
}
