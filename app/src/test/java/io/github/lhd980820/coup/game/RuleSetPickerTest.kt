package io.github.lhd980820.coup.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.rules.ParamSpec
import io.github.lhd980820.coup.presentation.rules.RuleSelection
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig
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

/** 룰셋·하우스룰 선택 화면: 코어의 [RuleSetEditor]가 주는 목록을 그리고, 선택이 실제 게임 설정까지 간다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w360dp-h740dp")
class RuleSetPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteraction.tap() = performScrollTo().assertIsDisplayed().performClick()

    private fun summary() = compose.onNodeWithTag(RuleTags.SUMMARY)

    private fun showSetup(onStart: (SinglePlayerConfig) -> Unit = {}) {
        compose.setContent { CoupTheme { SinglePlayerSetupScreen(onStart = onStart) } }
    }

    @Test
    fun `처음에는 표준 규칙이고 접혀 있다`() {
        showSetup()
        summary().assertTextEquals("표준 규칙")
        assertFalse(exists(RuleTags.house("last_stand")))
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        // 펼치면 코어가 등록한 하우스룰과 조절 항목이 모두 나온다
        compose.onNodeWithTag(RuleTags.house("no_steal_from_broke")).assertIsDisplayed()
        compose.onNodeWithTag(RuleTags.house("last_stand")).assertIsDisplayed()
        listOf("startingCoins", "forcedActionThreshold").forEach { compose.onNodeWithTag(RuleTags.value(it)).assertExists() }
        listOf("refundCostWhenActionChallengeLost", "exchangeReturnShuffles").forEach { compose.onNodeWithTag(RuleTags.switch(it)).assertExists() }
        compose.onNodeWithTag(RuleTags.value("startingCoins")).assertTextEquals("2")
        compose.onNodeWithTag(RuleTags.value("forcedActionThreshold")).assertTextEquals("10")
        compose.onNodeWithTag(RuleTags.RESET).assertIsNotEnabled() // 표준이면 되돌릴 것이 없다
    }

    @Test
    fun `하우스룰과 세부 설정을 바꾸면 요약과 시작 설정이 따라간다`() {
        var started: SinglePlayerConfig? = null
        showSetup { started = it }
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        compose.onNodeWithTag(RuleTags.house("last_stand")).tap()
        summary().assertTextEquals("바꾼 규칙 1개")
        compose.onNodeWithTag(RuleTags.plus("startingCoins")).tap()
        compose.onNodeWithTag(RuleTags.minus("forcedActionThreshold")).tap()
        compose.onNodeWithTag(RuleTags.switch("exchangeReturnShuffles")).tap()
        summary().assertTextEquals("바꾼 규칙 4개")
        compose.onNodeWithTag(RuleTags.value("startingCoins")).assertTextEquals("3")
        compose.onNodeWithTag(RuleTags.value("forcedActionThreshold")).assertTextEquals("9")

        compose.onNodeWithTag(SetupTags.START).tap()
        val config = requireNotNull(started).ruleSet
        assertEquals(setOf("last_stand"), config.houseRules)
        assertEquals(mapOf("startingCoins" to "3", "forcedActionThreshold" to "9", "exchangeReturnShuffles" to "false"), config.paramOverrides)
    }

    @Test
    fun `기본값으로 되돌리면 표준 규칙이 되고 되돌리기 버튼으로 한 번에 돌아간다`() {
        var started: SinglePlayerConfig? = null
        showSetup { started = it }
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        compose.onNodeWithTag(RuleTags.plus("startingCoins")).tap()
        summary().assertTextEquals("바꾼 규칙 1개")
        compose.onNodeWithTag(RuleTags.minus("startingCoins")).tap() // 다시 기본값 2
        summary().assertTextEquals("표준 규칙")

        compose.onNodeWithTag(RuleTags.house("no_steal_from_broke")).tap()
        compose.onNodeWithTag(RuleTags.minus("startingCoins")).tap()
        compose.onNodeWithTag(RuleTags.RESET).assertIsEnabled().tap()
        summary().assertTextEquals("표준 규칙")
        compose.onNodeWithTag(SetupTags.START).tap()
        assertEquals(BuiltinRules.classicConfig(), requireNotNull(started).ruleSet)
    }

    @Test
    fun `값은 범위를 넘지 못하고 버튼이 잠긴다`() {
        showSetup()
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        val spec = RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()).params.filterIsInstance<ParamSpec.IntParam>().first { it.key == "startingCoins" }
        repeat(spec.max - spec.default) { compose.onNodeWithTag(RuleTags.plus("startingCoins")).tap() }
        compose.onNodeWithTag(RuleTags.value("startingCoins")).assertTextEquals(spec.max.toString())
        compose.onNodeWithTag(RuleTags.plus("startingCoins")).assertIsNotEnabled()
        repeat(spec.max - spec.min) { compose.onNodeWithTag(RuleTags.minus("startingCoins")).tap() }
        compose.onNodeWithTag(RuleTags.value("startingCoins")).assertTextEquals(spec.min.toString())
        compose.onNodeWithTag(RuleTags.minus("startingCoins")).assertIsNotEnabled()
    }

    @Test
    fun `고른 규칙이 실제 게임에 적용된다 - 시작 코인`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { SinglePlayerFlow(vm, onExit = {}) } }
        compose.onNodeWithTag(ShellTags.HOME_SINGLE).assertIsDisplayed().performClick()
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        repeat(2) { compose.onNodeWithTag(RuleTags.plus("startingCoins")).tap() }
        compose.onNodeWithTag(SetupTags.START).tap()
        compose.waitUntil(10_000) { vm.controller?.state?.value != null }
        val state = vm.controller!!.state.value!!
        assertEquals(4, state.me!!.coins)
        assertTrue(state.opponents.all { it.coins == 4 })
        scope.cancel()
    }

    @Test
    fun `방 설정 용도에서는 레이팅 제외 안내가 규칙을 바꿀 때만 보인다`() {
        val editor = RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig())
        var selection by mutableStateOf(RuleSelection())
        compose.setContent { CoupTheme { RuleSetPicker(editor, selection, onChange = { selection = it }, unratedNote = true) } }
        val note = "레이팅에 반영되지 않아요"
        fun noteShown() = compose.onAllNodes(androidx.compose.ui.test.hasText(note, substring = true)).fetchSemanticsNodes().isNotEmpty()
        assertFalse(noteShown())
        selection = editor.toggleHouseRule(selection, "last_stand")
        compose.waitForIdle()
        assertTrue(noteShown())
        selection = editor.standard
        compose.waitForIdle()
        assertFalse(noteShown())
    }

    @Test
    fun `선택은 화면이 다시 만들어져도 유지된다`() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent { CoupTheme { SinglePlayerSetupScreen(onStart = {}) } }
        compose.onNodeWithTag(RuleTags.TOGGLE).tap()
        compose.onNodeWithTag(RuleTags.house("last_stand")).tap()
        compose.onNodeWithTag(RuleTags.plus("startingCoins")).tap()
        tester.emulateSavedInstanceStateRestore()
        summary().assertTextEquals("바꾼 규칙 2개")
    }

    @Test
    fun `저장 형식은 왕복해도 같다`() {
        val sel = RuleSelection(setOf("last_stand", "no_steal_from_broke"), mapOf("startingCoins" to "3", "exchangeReturnShuffles" to "false"))
        val saved = with(RuleSelectionSaver) { SaverScope { true }.save(sel) }!!
        assertEquals(sel, RuleSelectionSaver.restore(saved))
        assertEquals(RuleSelection(), RuleSelectionSaver.restore(with(RuleSelectionSaver) { SaverScope { true }.save(RuleSelection()) }!!))
    }
}
