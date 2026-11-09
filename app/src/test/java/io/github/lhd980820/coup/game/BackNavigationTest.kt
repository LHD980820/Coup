package io.github.lhd980820.coup.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.lhd980820.coup.pressBack
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 뒤로 가기(백로그 3): 앱은 Activity 하나라 화면마다 어디로 돌아갈지 직접 정한다. 기기와 같은 순서(대화상자 먼저)로 눌러 본다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class BackNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000L
    private var exits = 0
    private var hints = 0

    @After
    fun tearDown() = scope.cancel()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.waitUntil(15_000) { exists(tag) }

    private fun open(settings: AppSettings? = AppSettings(InMemorySettingsStore())): GameViewModel {
        val vm = GameViewModel(scope = scope)
        compose.setContent {
            CoupTheme { AppFlow(vm, onExit = { exits++ }, settings = settings, onExitHint = { hints++ }, now = { clock }) }
        }
        return vm
    }

    private fun assertHome() {
        compose.onNodeWithTag(ShellTags.HOME_SINGLE).assertIsDisplayed()
    }

    // ---- 홈: 두 번 눌러야 종료 ----

    @Test
    fun `홈에서 뒤로 가기는 처음에는 안내만 하고, 간격 안에 한 번 더 누르면 앱이 끝난다`() {
        open()
        compose.pressBack()
        assertEquals(0, exits)
        assertEquals(1, hints)
        clock += 1_500
        compose.pressBack()
        assertEquals(1, exits)
        assertEquals(1, hints)
    }

    @Test
    fun `두 번 누르는 간격이 지나면 다시 안내부터 한다`() {
        open()
        compose.pressBack()
        clock += DoubleBackExit.DEFAULT_WINDOW_MILLIS + 1
        compose.pressBack()
        assertEquals(0, exits)
        assertEquals(2, hints)
    }

    @Test
    fun `종료를 확정한 직후 다시 누르면 새로 안내부터 한다`() {
        open()
        compose.pressBack()
        clock += 500
        compose.pressBack() // 종료
        clock += 500
        compose.pressBack() // 앱이 그대로 떠 있다면 이것은 새 첫 번째다
        assertEquals(1, exits)
        assertEquals(2, hints)
    }

    @Test
    fun `다른 화면에 다녀온 뒤 홈에서 누른 첫 뒤로 가기는 종료가 아니다`() {
        open()
        compose.onNodeWithTag(ShellTags.HOME_TUTORIAL).performClick()
        compose.pressBack() // 홈으로
        assertHome()
        assertEquals(0, exits)
        assertEquals(0, hints) // 화면 이동의 뒤로 가기는 종료 안내를 세지 않는다
        compose.pressBack() // 홈에서 첫 번째
        assertEquals(0, exits)
        assertEquals(1, hints)
    }

    // ---- 홈이 아닌 화면은 홈으로 ----

    @Test
    fun `싱글 설정, 튜토리얼 목록, 설정에서 뒤로 가기는 홈으로 간다`() {
        open()
        listOf(ShellTags.HOME_SINGLE to SetupTags.START, ShellTags.HOME_TUTORIAL to ShellTags.TUTORIAL_BACK, ShellTags.HOME_SETTINGS to ShellTags.SETTINGS_BACK).forEach { (entry, inside) ->
            compose.onNodeWithTag(entry).performClick()
            waitFor(inside)
            compose.pressBack()
            waitFor(ShellTags.HOME_SINGLE)
            assertHome()
        }
        assertEquals(0, exits)
        assertEquals(0, hints)
    }

    // ---- 싱글플레이 게임 ----

    private fun startSinglePlayer(vm: GameViewModel) {
        compose.onNodeWithTag(ShellTags.HOME_SINGLE).performClick()
        // AI 1명: 사람이 기권하면 판이 바로 끝난다(AI끼리 남으면 연출 시간 때문에 결과가 한참 뒤에 나온다)
        compose.onNodeWithTag(SetupTags.opponents(1)).performScrollTo().performClick()
        compose.onNodeWithTag(SetupTags.START).performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.controller != null }
        waitFor(GameTags.BANNER)
    }

    @Test
    fun `게임 중 뒤로 가기는 바로 나가지 않고 묻고, 계속하면 판이 그대로다`() {
        val vm = open()
        startSinglePlayer(vm)
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        assertNotNull(vm.controller)
        // 대화상자가 떠 있을 때 한 번 더 누르면 "계속하기"
        compose.pressBack()
        compose.waitUntil(5_000) { !exists(BackTags.DIALOG) }
        assertNotNull(vm.controller)
        assertTrue(exists(GameTags.BANNER))
        assertEquals(0, exits)
    }

    @Test
    fun `게임 중 뒤로 가기 후 나가기를 고르면 판을 닫고 설정 화면으로 간다`() {
        val vm = open()
        startSinglePlayer(vm)
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithTag(BackTags.CONFIRM).performClick()
        compose.waitUntil(5_000) { vm.controller == null }
        waitFor(SetupTags.START)
        assertFalse(exists(GameTags.BANNER))
        assertEquals(0, exits)
    }

    private fun concedeToResult(vm: GameViewModel) {
        startSinglePlayer(vm)
        compose.onNodeWithTag(GameTags.CONCEDE).performClick()
        compose.onNodeWithTag(GameTags.CONCEDE_CONFIRM).performClick()
        waitFor(GameTags.RESULT)
    }

    @Test
    fun `결과 대화상자에서 뒤로 가기는 설정 화면으로(다시 하기와 같은 길), 앱을 끄지 않는다`() {
        val vm = open()
        concedeToResult(vm)
        compose.pressBack()
        compose.waitUntil(5_000) { vm.controller == null }
        waitFor(SetupTags.START)
        assertEquals(0, exits)
        assertEquals(0, hints)
    }

    @Test
    fun `결과의 나가기는 앱을 끄지 않고 홈으로 간다`() {
        val vm = open()
        concedeToResult(vm)
        compose.onNodeWithTag(GameTags.RESULT_EXIT).performClick()
        compose.waitUntil(5_000) { vm.controller == null }
        waitFor(ShellTags.HOME_SINGLE)
        assertEquals(0, exits)
    }

    @Test
    fun `기권 확인 대화상자에서 뒤로 가기는 확인만 닫는다`() {
        val vm = open()
        startSinglePlayer(vm)
        compose.onNodeWithTag(GameTags.CONCEDE).performClick()
        waitFor(GameTags.CONCEDE_CONFIRM)
        compose.pressBack()
        compose.waitUntil(5_000) { !exists(GameTags.CONCEDE_CONFIRM) }
        assertFalse(exists(BackTags.DIALOG)) // 나가기 확인이 대신 뜨지 않는다
        assertNotNull(vm.controller)
        assertNull(vm.controller?.state?.value?.result)
    }

    // ---- 튜토리얼 ----

    private fun startTutorial(vm: GameViewModel, index: Int = 0) {
        compose.onNodeWithTag(ShellTags.HOME_TUTORIAL).performClick()
        compose.onNodeWithTag(ShellTags.lesson(TutorialLessons.all[index].id)).performClick()
        compose.waitUntil(10_000) { vm.tutorial != null }
        waitFor(GameTags.COACH)
    }

    @Test
    fun `튜토리얼 중 뒤로 가기는 묻고, 그만하면 튜토리얼 목록으로, 계속하면 그대로`() {
        val vm = open()
        startTutorial(vm)
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithTag(BackTags.STAY).performClick()
        compose.waitUntil(5_000) { !exists(BackTags.DIALOG) }
        assertNotNull(vm.tutorial)
        assertTrue(exists(GameTags.COACH))

        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithTag(BackTags.CONFIRM).performClick()
        compose.waitUntil(5_000) { vm.tutorial == null }
        waitFor(ShellTags.TUTORIAL_BACK) // 목록
        assertEquals(0, exits)
        // 목록에서 한 번 더 → 홈
        compose.pressBack()
        waitFor(ShellTags.HOME_SINGLE)
    }

    @Test
    fun `튜토리얼 완료 대화상자에서 뒤로 가기는 목록으로`() {
        val vm = open()
        startTutorial(vm)
        // 과정을 안내대로 끝까지 누른다(TutorialScreenTest와 같은 방식)
        val lesson = TutorialLessons.all[0]
        lesson.steps.forEachIndexed { i, step ->
            val tag = when (val e = step.expect) {
                is io.github.lhd980820.coup.presentation.tutorial.TutorialExpect.Action -> GameTags.action(e.action.value)
                is io.github.lhd980820.coup.presentation.tutorial.TutorialExpect.Pass -> GameTags.PASS
                is io.github.lhd980820.coup.presentation.tutorial.TutorialExpect.Challenge -> GameTags.CHALLENGE
                is io.github.lhd980820.coup.presentation.tutorial.TutorialExpect.Block -> GameTags.block(e.role.value)
                else -> error("unexpected step $e")
            }
            compose.waitUntil(10_000) { exists(tag) }
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10_000) { vm.tutorial?.progress?.value.let { it == null || it.completed || it.stepIndex > i } }
        }
        waitFor(ShellTags.COACH_DONE)
        compose.pressBack()
        compose.waitUntil(5_000) { vm.tutorial == null }
        waitFor(ShellTags.TUTORIAL_BACK)
        assertEquals(0, exits)
    }

    // ---- 모든 화면에 규칙이 있다 ----

    @Test
    fun `화면이 늘면 뒤로 가기 규칙도 정해야 한다 - 모든 화면의 목적지를 적어 둔다`() {
        // 새 Screen을 더하면 이 표를 고치게 되고, 그때 BACKLOG의 뒤로 가기 표와 AppFlow의 규칙도 함께 본다
        val destinations = mapOf(
            Screen.HOME to "두 번 눌러 종료",
            Screen.SETUP to "홈",
            Screen.TUTORIALS to "홈",
            Screen.SETTINGS to "홈",
            Screen.RANKING to "홈",
            Screen.HISTORY to "홈",
            Screen.MULTI to "멀티플레이 안쪽 화면부터, 마지막은 홈",
        )
        assertEquals(Screen.entries.toSet(), destinations.keys)
    }
}
