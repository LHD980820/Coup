package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.BlockButtonUi
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 게임 중 "이 판의 규칙 보기": 표준이면 숨기고, 바뀐 규칙이 있으면 배지 -> 목록. 역할 설명의 안내도 규칙이 바뀐 판에서만 나온다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class RulesInGameTest {
    @get:Rule
    val compose = createComposeRule()

    private val ai = PlayerId("ai")
    private val classic = BuiltinRules.classicConfig()
    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun state(ruleSet: RuleSetConfig?, decision: DecisionUi? = null) = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(OpponentUi(ai, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false)),
        me = MyUi(PlayerId("me"), "나", 2, listOf(CardUi(CardId(1), RoleId("duke"), false)), true, OpponentStatus.IDLE),
        deckSize = 11, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
        ruleSet = ruleSet,
    )

    private object NoActions : GameActions {
        override fun chooseAction(id: ActionId) = Unit
        override fun chooseTarget(id: PlayerId) = Unit
        override fun cancelTarget() = Unit
        override fun pass() = Unit
        override fun challenge() = Unit
        override fun block(role: RoleId) = Unit
        override fun selectCard(id: CardId) = Unit
        override fun confirmCard() = Unit
        override fun toggleExchange(id: CardId) = Unit
        override fun confirmExchange() = Unit
        override fun concede() = Unit
    }

    private fun show(state: GameUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion.Off) {
                CoupTheme { GameScreen(state, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    private val custom get() = classic.copy(houseRules = setOf("last_stand"), paramOverrides = mapOf("startingCoins" to "4"))

    @Test
    fun `표준 규칙의 판이나 규칙을 모르는 상태에서는 규칙 배지가 없다`() {
        show(state(classic))
        assertTrue(!exists(RulesViewTags.BADGE))
    }

    @Test
    fun `규칙 정보가 없는 상태에서는 배지가 없다`() {
        show(state(null))
        assertTrue(!exists(RulesViewTags.BADGE))
    }

    @Test
    fun `바뀐 규칙이 있으면 개수가 적힌 배지가 보이고, 탭하면 바뀐 규칙과 기본값이 나온다`() {
        show(state(custom))
        compose.onNodeWithTag(RulesViewTags.BADGE).assertIsDisplayed().assertTextContains(string(R.string.game_rules_badge, 2))
        compose.onNodeWithTag(RulesViewTags.BADGE).performClick()
        compose.onNodeWithTag(RulesViewTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(RulesViewTags.line("last_stand")).assertIsDisplayed()
        compose.onNodeWithTag(RulesViewTags.line("startingCoins")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.rules_changed_value, "4", "2"), substring = true).assertIsDisplayed()
        compose.onNodeWithTag(RulesViewTags.DIALOG_CLOSE).performClick()
        compose.waitUntil(5_000) { !exists(RulesViewTags.DIALOG) }
    }

    @Test
    fun `켜고 끄는 항목은 켬 끔으로 보이고 바뀌지 않은 항목은 나오지 않는다`() {
        show(state(classic.copy(paramOverrides = mapOf("refundCostWhenActionChallengeLost" to "false"))))
        compose.onNodeWithTag(RulesViewTags.BADGE).performClick()
        compose.onNodeWithText(string(R.string.rules_changed_value, string(R.string.rules_off), string(R.string.rules_on)), substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag(RulesViewTags.line("startingCoins")).assertCountEquals(0)
        compose.onAllNodesWithTag(RulesViewTags.line("last_stand")).assertCountEquals(0)
    }

    @Test
    fun `이 버전이 모르는 하우스룰이면 알 수 없다고 알린다`() {
        show(state(classic.copy(houseRules = setOf("future_rule"))))
        compose.onNodeWithTag(RulesViewTags.BADGE).assertIsDisplayed()
        compose.onNodeWithTag(RulesViewTags.BADGE).performClick()
        compose.onNodeWithText(string(R.string.rules_unknown)).assertIsDisplayed()
    }

    @Test
    fun `역할 능력 시트의 안내는 규칙이 바뀐 판에서만 나온다`() {
        show(state(classic))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithText(string(R.string.game_role_ability_duke)).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.game_ability_note)).assertCountEquals(0)
    }

    @Test
    fun `규칙이 바뀐 판의 역할 능력 시트에는 설명이 다를 수 있다는 안내가 나온다`() {
        show(state(custom))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithText(string(R.string.game_ability_note)).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h568dp", fontScale = 1.3f)
    fun `작은 화면 큰 글씨에서도 배지와 기권과 응답 시트가 모두 보인다`() {
        val respond = DecisionUi.Respond(WindowKind.ACTION, ActionId("tax"), ai, null, ai, setOf(RoleId("duke")), true, listOf(BlockButtonUi(RoleId("captain"), true)))
        show(state(custom, respond))
        listOf(RulesViewTags.BADGE, GameTags.CONCEDE, GameTags.PASS, GameTags.CHALLENGE, GameTags.block("captain")).forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
    }

    @Test
    fun `표준 규칙인지는 규칙 정보의 하우스룰과 바꾼 값으로만 정한다`() {
        assertEquals(false, classic.isCustom)
        assertEquals(false, (null as RuleSetConfig?).isCustom)
        assertEquals(true, classic.copy(houseRules = setOf("last_stand")).isCustom)
        assertEquals(true, classic.copy(paramOverrides = mapOf("startingCoins" to "3")).isCustom)
    }
}
