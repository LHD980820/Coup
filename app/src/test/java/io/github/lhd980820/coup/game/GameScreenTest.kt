package io.github.lhd980820.coup.game

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.singleplayer.OpponentSpec
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig
import io.github.lhd980820.coup.ui.theme.CoupTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.ZERO

/**
 * 실제 Compose 화면을 JVM(Robolectric)에서 띄워 **버튼만 눌러** AI와 한 판을 끝까지 둔다.
 * 어떤 버튼을 눌러야 하는지는 코어의 상태([GameUiState.decision])에서 알아내고, 그 버튼이 화면에 실제로 있어야만 누른다
 * (= 화면이 결정을 빠짐없이 그린다는 검증).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class GameScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun config(opponents: Int, seed: Long) = SinglePlayerConfig(
        opponents = List(opponents) { OpponentSpec(AiDifficulty.EASY, name = "AI ${it + 1}") },
        humanName = "나",
        aiThinkTime = ZERO..ZERO,
        seed = seed,
    )

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * 누른다. 눌러야 하는 컨트롤은 화면에 실제로 보여야 한다(작은 화면에서 잘리면 실패).
     * 단, 판단한 뒤 AI가 먼저 움직여 결정이 이미 사라졌다면(예: 다른 사람이 먼저 도전해 응답 창이 닫힘) 건너뛴다 — 그 경우 false.
     */
    private fun click(vm: GameViewModel, tag: String, decidedOn: GameUiState?): Boolean {
        try {
            compose.waitUntil(timeoutMillis = 5_000) { exists(tag) || vm.controller?.state?.value != decidedOn }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val tags = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag") { true })
                .fetchSemanticsNodes().mapNotNull { n -> n.config.firstOrNull { it.key == androidx.compose.ui.semantics.SemanticsProperties.TestTag }?.value as? String }
            error("'$tag'가 화면에 없다. 현재 결정=${vm.controller?.state?.value?.decision}, busy=${vm.controller?.state?.value?.busy}, 화면 태그=$tags")
        }
        if (!exists(tag)) return false
        return try {
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            true
        } catch (e: AssertionError) {
            // 확인과 누르기 사이에 AI가 움직여 컨트롤이 사라졌다(결정이 바뀌었다)면 건너뛴다. 그대로인데 못 누른 것은 진짜 실패다.
            if (!exists(tag) && vm.controller?.state?.value != decidedOn) false else throw e
        }
    }

    private fun waitForChange(vm: GameViewModel, before: GameUiState?) {
        compose.waitUntil(timeoutMillis = 20_000) {
            val now = vm.controller?.state?.value
            now == null || now != before
        }
    }

    /** 누르고, 그 결과로 게임 상태가 바뀔 때까지 기다린다(누르자마자 다음 판단을 하면 아직 안 바뀐 옛 상태를 보게 된다). */
    private fun press(vm: GameViewModel, tag: String): Boolean {
        val before = vm.controller!!.state.value
        if (!click(vm, tag, before)) return false
        waitForChange(vm, before)
        return true
    }

    private fun playOneStep(vm: GameViewModel, random: Random): Boolean {
        compose.waitUntil(timeoutMillis = 10_000) { vm.controller?.state?.value != null }
        val state = vm.controller!!.state.value!!
        if (state.result != null) return true
        if (state.busy) {
            compose.waitUntil(timeoutMillis = 10_000) { vm.controller?.state?.value?.busy != true }
            return false
        }
        when (val d = state.decision) {
            null -> waitForChange(vm, state)
            is DecisionUi.Actions -> {
                val button = d.buttons.filter { it.enabled }.let { list -> list.filter { it.forced }.ifEmpty { list }.random(random) }
                if (press(vm, GameTags.action(button.actionId.value)) && button.needsTarget) {
                    // 누른 뒤 처음 바뀐 상태가 대상 고르기가 아닐 수 있다(다른 갱신이 먼저 온다) — 대상 고르기가 될 때까지(또는 판이 넘어갈 때까지) 기다린다
                    compose.waitUntil(timeoutMillis = 10_000) { vm.controller?.state?.value?.decision !is DecisionUi.Actions }
                    val pick = vm.controller!!.state.value!!.decision as? DecisionUi.PickTarget ?: return false
                    press(vm, GameTags.target(pick.targets.random(random).value))
                }
            }
            is DecisionUi.PickTarget -> press(vm, GameTags.target(d.targets.first().value))
            is DecisionUi.Respond -> when {
                d.canChallenge && random.nextInt(4) == 0 -> press(vm, GameTags.CHALLENGE)
                d.blocks.isNotEmpty() && random.nextInt(3) == 0 -> press(vm, GameTags.block(d.blocks.random(random).role.value))
                else -> press(vm, GameTags.PASS)
            }
            is DecisionUi.PickCard -> {
                if (press(vm, GameTags.card(d.cards.first().id.value))) press(vm, GameTags.CONFIRM)
            }
            is DecisionUi.PickExchange -> {
                if (d.candidates.take(d.keepCount).all { press(vm, GameTags.card(it.id.value)) }) press(vm, GameTags.CONFIRM)
            }
        }
        return false
    }

    private fun playToTheEnd(opponents: Int, seed: Long): GameUiState {
        // 게임은 메인 루퍼와 무관한 범위에서 돌린다(Robolectric의 일시정지 루퍼에 막히지 않게)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { SinglePlayerFlow(vm, onExit = {}) } }
        // 홈 -> 설정 화면을 실제로 조작해 시작한다
        compose.onNodeWithTag(ShellTags.HOME_SINGLE).assertIsDisplayed().performClick()
        compose.onNodeWithTag(SetupTags.opponents(opponents)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(SetupTags.START).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { vm.controller != null }
        // 설정 화면의 seed는 무작위이므로, 재현 가능한 판을 위해 같은 설정으로 다시 시작한다
        vm.start(config(opponents, seed))
        val random = Random(seed)
        var steps = 0
        while (steps++ < 2_000) {
            if (playOneStep(vm, random)) break
        }
        compose.waitUntil(10_000) { vm.controller?.state?.value?.result != null }
        compose.onNodeWithTag(GameTags.RESULT).assertExists("결과 화면이 떠야 한다")
        return vm.controller!!.state.value!!.also { scope.cancel() }
    }

    @Test
    fun `설정 화면에서 시작해 AI 2명과 버튼만으로 끝까지 둔다`() {
        val end = playToTheEnd(opponents = 2, seed = 11)
        assertTrue(end.result!!.ranking.size == 3)
    }

    @Test
    fun `AI 1명과 둔다`() {
        val end = playToTheEnd(opponents = 1, seed = 5)
        assertTrue(end.result!!.ranking.size == 2)
    }

    private fun fiveOpponents(seed: Long) {
        val end = playToTheEnd(opponents = 5, seed = seed)
        assertTrue(end.result!!.ranking.size == 6)
        compose.onNodeWithTag(GameTags.RESULT_EXIT).assertExists()
    }

    // 한 테스트에서 setContent는 한 번만 부를 수 있으므로 판마다 테스트를 나눈다
    @Test
    fun `AI 5명과 둔다 1`() = fiveOpponents(1)

    @Test
    fun `AI 5명과 둔다 2`() = fiveOpponents(2)

    @Test
    fun `AI 5명과 둔다 3`() = fiveOpponents(3)

    // 작은 화면(예: 320x568dp)에서도 결정 컨트롤이 잘리지 않고 눌러야 하는 것이 모두 보여야 한다
    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h568dp")
    fun `작은 화면에서도 AI 3명과 끝까지 둘 수 있다`() {
        val end = playToTheEnd(opponents = 3, seed = 21)
        assertTrue(end.result!!.ranking.size == 4)
    }

    // 큰 글씨(접근성)에서도
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h740dp", fontScale = 1.6f)
    fun `큰 글씨에서도 AI 2명과 끝까지 둘 수 있다`() {
        val end = playToTheEnd(opponents = 2, seed = 33)
        assertTrue(end.result!!.ranking.size == 3)
    }
}
