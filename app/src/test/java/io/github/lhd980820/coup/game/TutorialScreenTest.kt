package io.github.lhd980820.coup.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.presentation.tutorial.TutorialExpect
import io.github.lhd980820.coup.presentation.tutorial.TutorialLesson
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun TutorialExpect.tag(): String = when (this) {
    is TutorialExpect.Action -> GameTags.action(action.value)
    TutorialExpect.Pass -> GameTags.PASS
    TutorialExpect.Challenge -> GameTags.CHALLENGE
    is TutorialExpect.Block -> GameTags.block(role.value)
}

/** 튜토리얼 화면: 목록 -> 과정. 안내가 가리키는 컨트롤만 눌러 끝까지 가고, 그 밖의 컨트롤은 잠겨 있어야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w360dp-h740dp")
class TutorialScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 목록 카드는 눌림 영역이라 자식이 합쳐진다: 완료 표시는 합치지 않은 트리에서 찾는다
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(what: String, tag: String) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) { exists(tag) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            error("'$what'($tag)가 화면에 나타나지 않았다")
        }
    }

    private fun open(store: TutorialStore = InMemoryTutorialStore()): GameViewModel {
        val vm = GameViewModel(scope = scope, tutorialStore = store)
        compose.setContent { CoupTheme { SinglePlayerFlow(vm, onExit = {}) } }
        compose.onNodeWithTag(ShellTags.HOME_TUTORIAL).assertIsDisplayed().performClick()
        return vm
    }

    /** 과정의 모든 단계를 안내대로 누른다. 각 단계에서 안내 카드가 보이고, 안내 밖 컨트롤은 잠겨 있다. */
    private fun playLesson(lesson: TutorialLesson, vm: GameViewModel) {
        lesson.steps.forEachIndexed { i, step ->
            val tag = step.expect.tag()
            await("단계 ${i + 1}", tag)
            compose.onNodeWithTag(GameTags.COACH).assertIsDisplayed()
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            // 안내 밖 컨트롤은 눌러도 소용없게 잠근다
            listOf(GameTags.PASS, GameTags.CHALLENGE, GameTags.action("tax"), GameTags.action("income"), GameTags.action("foreign_aid"))
                .filter { it != tag && exists(it) }
                .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
            compose.onNodeWithTag(tag).performClick()
            // 단계가 넘어가거나 과정이 끝날 때까지
            compose.waitUntil(timeoutMillis = 10_000) {
                val p = vm.tutorial?.progress?.value
                p == null || p.completed || p.stepIndex > i
            }
        }
        await("완료 대화상자", ShellTags.COACH_DONE)
    }

    @Test
    fun `목록에 네 과정이 보이고 처음에는 완료 표시가 없다`() {
        open()
        TutorialLessons.all.forEach { l ->
            compose.onNodeWithTag(ShellTags.lesson(l.id)).assertIsDisplayed()
            assertFalse(exists(ShellTags.lessonDone(l.id)))
        }
        assertEquals(4, TutorialLessons.all.size)
    }

    private fun lessonTest(index: Int) {
        val store = InMemoryTutorialStore()
        val vm = open(store)
        val lesson = TutorialLessons.all[index]
        compose.onNodeWithTag(ShellTags.lesson(lesson.id)).assertIsDisplayed().performClick()
        playLesson(lesson, vm)
        // 완료가 기록되고, 목록으로 돌아가면 표시가 보인다
        assertTrue(lesson.id in store.completed())
        compose.onNodeWithTag(ShellTags.COACH_DONE_BACK).performClick()
        await("완료 표시", ShellTags.lessonDone(lesson.id))
        scope.cancel()
    }

    // 한 테스트에서 setContent는 한 번만 부를 수 있으므로 과정마다 테스트를 나눈다
    @Test
    fun `기본 행동 과정을 끝낸다`() = lessonTest(0)

    @Test
    fun `역할 주장 과정을 끝낸다`() = lessonTest(1)

    @Test
    fun `도전 과정을 끝낸다`() = lessonTest(2)

    @Test
    fun `막기 과정을 끝낸다`() = lessonTest(3)

    @Test
    fun `완료 대화상자의 다음 과정으로 이어진다`() {
        val vm = open()
        compose.onNodeWithTag(ShellTags.lesson(TutorialLessons.all[0].id)).performClick()
        playLesson(TutorialLessons.all[0], vm)
        compose.onNodeWithTag(ShellTags.COACH_NEXT).assertIsDisplayed().performClick()
        // 두 번째 과정(공작으로 세금)의 첫 단계가 안내된다
        await("두 번째 과정의 첫 단계", GameTags.action("tax"))
        assertEquals(TutorialLessons.all[1].id, vm.tutorialLesson?.id)
        compose.onNodeWithTag(GameTags.action("tax")).assertIsEnabled()
        compose.onNodeWithTag(GameTags.action("income")).assertIsNotEnabled()
        scope.cancel()
    }

    @Test
    fun `마지막 과정에는 다음 과정 버튼이 없다`() {
        val vm = open()
        val last = TutorialLessons.all.last()
        compose.onNodeWithTag(ShellTags.lesson(last.id)).performClick()
        playLesson(last, vm)
        assertFalse(exists(ShellTags.COACH_NEXT))
        compose.onNodeWithTag(ShellTags.COACH_DONE_BACK).assertIsDisplayed()
        scope.cancel()
    }

    @Test
    fun `이미 끝낸 과정은 저장소에서 읽어 표시한다`() {
        val store = InMemoryTutorialStore(setOf(TutorialLessons.all[2].id))
        open(store)
        compose.onNodeWithTag(ShellTags.lessonDone(TutorialLessons.all[2].id), useUnmergedTree = true).assertIsDisplayed()
        // 접근성: 카드 자체가 "완료" 상태를 알린다
        compose.onNodeWithTag(ShellTags.lesson(TutorialLessons.all[2].id)).assertStateDescription("완료")
        compose.onNodeWithTag(ShellTags.lesson(TutorialLessons.all[0].id)).assertNoStateDescription()
        assertFalse(exists(ShellTags.lessonDone(TutorialLessons.all[0].id)))
    }

    @Test
    fun `SharedPreferences 저장소는 기록을 유지한다`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = PrefsTutorialStore(ctx)
        a.markCompleted("basics")
        a.markCompleted("claim")
        a.markCompleted("basics")
        assertEquals(setOf("basics", "claim"), PrefsTutorialStore(ctx).completed())
    }
}

private fun stateDescriptionOf(n: androidx.compose.ui.semantics.SemanticsNode): String? =
    n.config.firstOrNull { it.key == SemanticsProperties.StateDescription }?.value as? String

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(text: String) =
    assertTrue("stateDescription=$text", stateDescriptionOf(fetchSemanticsNode()) == text)

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertNoStateDescription() =
    assertTrue("no stateDescription", stateDescriptionOf(fetchSemanticsNode()) == null)
