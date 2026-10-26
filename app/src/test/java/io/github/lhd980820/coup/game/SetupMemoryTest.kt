package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.presentation.rules.RuleSelection
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 싱글플레이 설정 기억: 마지막으로 시작한 선택(상대 수·난이도·규칙)을 저장하고, 설정 화면이 다시 열리면 그대로 채운다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class SetupMemoryTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val sample = SetupChoice(
        opponents = 4,
        difficulty = AiDifficulty.HARD,
        rules = RuleSelection(houseRules = setOf("last_stand"), params = mapOf("startingCoins" to "4", "exchangeReturnShuffles" to "true")),
    )

    // ---- 저장 형식 ----

    @Test
    fun `선택은 문자열로 저장했다가 그대로 읽힌다`() {
        assertEquals(sample, SetupChoice.decode(sample.encode()))
        val plain = SetupChoice(2, AiDifficulty.EASY, RuleSelection())
        assertEquals(plain, SetupChoice.decode(plain.encode()))
    }

    @Test
    fun `알아볼 수 없는 저장값은 없는 것으로 본다`() {
        assertNull(SetupChoice.decode(null))
        assertNull(SetupChoice.decode(""))
        assertNull(SetupChoice.decode("garbage"))
        assertNull(SetupChoice.decode("v2|opponents=3|difficulty=EASY")) // 모르는 버전
        assertNull(SetupChoice.decode("v1|difficulty=EASY")) // 상대 수가 없다
        assertNull(SetupChoice.decode("v1|opponents=abc|difficulty=EASY"))
    }

    @Test
    fun `범위를 벗어난 상대 수와 모르는 난이도는 안전한 값으로 맞춘다`() {
        assertEquals(5, SetupChoice.decode("v1|opponents=99|difficulty=EASY")!!.opponents)
        assertEquals(1, SetupChoice.decode("v1|opponents=0|difficulty=EASY")!!.opponents)
        assertEquals(AiDifficulty.NORMAL, SetupChoice.decode("v1|opponents=3|difficulty=NIGHTMARE")!!.difficulty)
        assertEquals(AiDifficulty.NORMAL, SetupChoice.decode("v1|opponents=3")!!.difficulty)
    }

    @Test
    fun `기기에 저장되고 다시 열어도 남아 있다`() {
        val store = PrefsSettingsStore(context)
        store.setLastSetup(sample)
        assertEquals(sample, PrefsSettingsStore(context).lastSetup())
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("single_setup", "garbage").commit()
        assertNull(PrefsSettingsStore(context).lastSetup())
    }

    @Test
    fun `앱 설정에 기억시키면 화면이 보는 값과 저장소가 함께 바뀐다`() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        assertNull(settings.lastSetup)
        settings.rememberSetup(sample)
        assertEquals(sample, settings.lastSetup)
        assertEquals(sample, store.lastSetup())
    }

    // ---- 설정 화면 ----

    private fun showSetup(remembered: SetupChoice?, onStart: (SinglePlayerConfig) -> Unit = {}, onRemember: (SetupChoice) -> Unit = {}) {
        compose.setContent { CoupTheme { SinglePlayerSetupScreen(onStart = onStart, remembered = remembered, onRemember = onRemember) } }
    }

    @Test
    fun `기억한 선택이 없으면 기본값으로 열린다`() {
        showSetup(null)
        compose.onNodeWithTag(SetupTags.opponents(2)).assertIsSelected()
        compose.onNodeWithTag(SetupTags.difficulty(AiDifficulty.NORMAL)).assertIsSelected()
        compose.onNodeWithTag(RuleTags.SUMMARY).assertTextContains(string(R.string.rules_standard))
    }

    @Test
    fun `기억한 선택으로 상대 수와 난이도와 규칙이 채워진다`() {
        showSetup(sample)
        compose.onNodeWithTag(SetupTags.opponents(4)).assertIsSelected()
        compose.onNodeWithTag(SetupTags.difficulty(AiDifficulty.HARD)).assertIsSelected()
        compose.onNodeWithTag(RuleTags.SUMMARY).assertTextContains(string(R.string.rules_changed, 3))
    }

    @Test
    fun `시작을 누르면 지금 선택이 기억되고 같은 설정으로 시작된다`() {
        var remembered: SetupChoice? = null
        var started: SinglePlayerConfig? = null
        showSetup(sample, onStart = { started = it }, onRemember = { remembered = it })
        compose.onNodeWithTag(SetupTags.opponents(3)).performClick() // 한 가지를 바꾼 뒤 시작
        compose.onNodeWithTag(SetupTags.START).performScrollTo().performClick()
        assertEquals(sample.copy(opponents = 3), remembered)
        assertEquals(3, started!!.opponents.size)
        assertEquals(AiDifficulty.HARD, started!!.opponents.first().difficulty)
        assertEquals(setOf("last_stand"), started!!.ruleSet.houseRules)
    }

    @Test
    fun `이 버전이 만들 수 없는 규칙을 기억하고 있었다면 표준으로 연다`() {
        showSetup(sample.copy(rules = RuleSelection(houseRules = setOf("removed_rule"))))
        compose.onNodeWithTag(SetupTags.opponents(4)).assertIsSelected() // 나머지 선택은 살린다
        compose.onNodeWithTag(RuleTags.SUMMARY).assertTextContains(string(R.string.rules_standard))
        compose.onNodeWithTag(SetupTags.START).performScrollTo().assertIsDisplayed()
    }

    private fun withFlow(settings: AppSettings, block: (GameViewModel) -> Unit) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { AppFlow(vm, onExit = {}, settings = settings) } }
        try {
            block(vm)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `앱 흐름 - 설정 화면에서 시작하면 그 선택이 앱 설정에 기억된다`() {
        val settings = AppSettings(InMemorySettingsStore())
        withFlow(settings) { vm ->
            compose.onNodeWithTag(ShellTags.HOME_SINGLE).performClick()
            compose.onNodeWithTag(SetupTags.opponents(4)).performScrollTo().performClick()
            compose.onNodeWithTag(SetupTags.difficulty(AiDifficulty.EASY)).performScrollTo().performClick()
            compose.onNodeWithTag(SetupTags.START).performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.controller != null }
            assertEquals(SetupChoice(4, AiDifficulty.EASY, RuleSelection()), settings.lastSetup)
        }
    }

    @Test
    fun `앱 흐름 - 앱을 다시 켠 것처럼 기억된 선택이 있으면 설정 화면이 그 값으로 열린다`() {
        val settings = AppSettings(InMemorySettingsStore(setup = sample))
        withFlow(settings) {
            compose.onNodeWithTag(ShellTags.HOME_SINGLE).performClick()
            compose.onNodeWithTag(SetupTags.opponents(4)).assertIsSelected()
            compose.onNodeWithTag(SetupTags.difficulty(AiDifficulty.HARD)).assertIsSelected()
            compose.onNodeWithTag(RuleTags.SUMMARY).assertTextContains(string(R.string.rules_changed, 3))
        }
    }
}
