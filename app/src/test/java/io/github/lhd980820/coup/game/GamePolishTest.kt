package io.github.lhd980820.coup.game

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 게임 화면 폴리시(Phase 6): 남은 시간 링, 역할 능력 시트, 큰 화면 두 단 배치. 상태를 직접 만들어 화면만 확인한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class GamePolishTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val ai = PlayerId("ai")
    private val now = 1_000_000L

    private fun state(
        myDeadline: Long? = null,
        opponentDeadline: Long? = null,
        decision: DecisionUi? = null,
        opponentStatus: OpponentStatus = OpponentStatus.THINKING,
    ) = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(OpponentUi(ai, "AI 1", 2, 2, emptyList(), true, opponentStatus, emptySet(), opponentDeadline, false)),
        me = MyUi(me, "나", 2, listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), false)), true, OpponentStatus.IDLE),
        deckSize = 11, decision = decision, myDeadline = myDeadline, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
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

    private fun show(state: GameUiState, clock: () -> Long = { now }) {
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides clock) {
                CoupTheme { GameScreen(state, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(id, *args)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // ---- 남은 시간 ----

    @Test
    fun `내가 결정할 차례면 남은 시간이 초 단위로 보인다`() {
        show(state(myDeadline = now + 20_000, decision = DecisionUi.Actions(emptyList())))
        compose.onNodeWithTag(GameTags.TIMER_MINE).assertIsDisplayed().assertTextContains(string(R.string.game_timer_seconds, 20))
    }

    @Test
    fun `마감이 1밀리초라도 남았으면 1초로 올림해 보이고, 지나면 0초`() {
        show(state(myDeadline = now + 1, decision = DecisionUi.Actions(emptyList())))
        compose.onNodeWithTag(GameTags.TIMER_MINE).assertTextContains(string(R.string.game_timer_seconds, 1))
    }

    @Test
    fun `이미 지난 마감은 0초로 보이고 음수가 되지 않는다`() {
        show(state(myDeadline = now - 5_000, decision = DecisionUi.Actions(emptyList())))
        compose.mainClock.advanceTimeBy(1_000) // 갱신 루프가 한 번 이상 돈 뒤에도
        compose.onNodeWithTag(GameTags.TIMER_MINE).assertTextContains(string(R.string.game_timer_seconds, 0))
    }

    @Test
    fun `결정할 게 없거나 제한이 없으면 내 남은 시간은 숨긴다`() {
        show(state(myDeadline = now + 20_000, decision = null))
        assertTrue(!exists(GameTags.TIMER_MINE))
    }

    @Test
    fun `제한이 없으면 남은 시간이 없다`() {
        show(state(myDeadline = null, decision = DecisionUi.Actions(emptyList())))
        assertTrue(!exists(GameTags.TIMER_MINE))
    }

    @Test
    fun `생각 중인 상대에게는 그 상대의 남은 시간이 붙고, 가만히 있는 상대에게는 붙지 않는다`() {
        show(state(opponentDeadline = now + 12_000, opponentStatus = OpponentStatus.THINKING))
        compose.onNodeWithTag(GameTags.timer("ai")).assertIsDisplayed().assertTextContains(string(R.string.game_timer_seconds, 12))
    }

    @Test
    fun `응답을 마친 상대의 마감은 보이지 않는다`() {
        show(state(opponentDeadline = now + 12_000, opponentStatus = OpponentStatus.RESPONDED))
        assertTrue(!exists(GameTags.timer("ai")))
    }

    @Test
    fun `시계가 흐르면 남은 시간이 줄어든다`() {
        var t = now
        show(state(myDeadline = now + 10_000, decision = DecisionUi.Actions(emptyList())), clock = { t })
        compose.onNodeWithTag(GameTags.TIMER_MINE).assertTextContains(string(R.string.game_timer_seconds, 10))
        t += 4_000
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(GameTags.TIMER_MINE).assertTextContains(string(R.string.game_timer_seconds, 6)) }.isSuccess
        }
    }

    // ---- 역할 능력 시트 ----

    @Test
    fun `내 카드를 탭하면 그 역할의 능력 시트가 뜨고 닫을 수 있다`() {
        show(state())
        assertTrue(!exists(GameTags.ABILITY_SHEET))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithTag(GameTags.ABILITY_SHEET).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.game_role_ability_duke)).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.ABILITY_CLOSE).performClick()
        compose.waitUntil(5_000) { !exists(GameTags.ABILITY_SHEET) }
    }

    @Test
    fun `다른 카드를 탭하면 그 카드의 능력이 보인다`() {
        show(state())
        compose.onNodeWithTag(GameTags.card(2)).performClick()
        compose.onNodeWithText(string(R.string.game_role_ability_contessa)).assertIsDisplayed()
    }

    @Test
    fun `카드를 고르는 결정 중에는 탭이 선택이고 능력 시트는 뜨지 않는다`() {
        var picked: CardId? = null
        val actions = object : GameActions by NoActions {
            override fun selectCard(id: CardId) { picked = id }
        }
        val hand = state().me!!.hand
        compose.setContent {
            CoupTheme { GameScreen(state(decision = DecisionUi.PickCard(io.github.lhd980820.coup.presentation.CardPurpose.LOSE, hand, emptySet(), null, null)), emptyFlow(), actions, onExit = {}, onPlayAgain = {}) }
        }
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertEquals(CardId(1), picked)
        assertTrue(!exists(GameTags.ABILITY_SHEET))
    }

    @Test
    fun `모든 역할의 능력 설명이 있고 서로 다르며, 모르는 역할도 설명이 있다`() {
        val roles = listOf("duke", "assassin", "captain", "ambassador", "contessa").map { RoleUiCatalog.of(RoleId(it)).ability }
        assertEquals(roles.size, roles.toSet().size)
        assertNotEquals(roles.first(), RoleUiCatalog.of(RoleId("future_role")).ability)
        roles.forEach { assertTrue(string(it).isNotBlank()) }
    }

    // ---- 큰 화면 ----

    @Test
    @Config(sdk = [35], qualifiers = "w900dp-h600dp")
    fun `큰 화면에서는 테이블 옆에 기록이 따로 보이고, 결정 컨트롤은 그대로 보인다`() {
        show(state(decision = DecisionUi.Actions(emptyList())))
        compose.onNodeWithTag(GameTags.LOG_SIDE).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.card(1)).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.CONCEDE).assertIsDisplayed()
    }

    @Test
    fun `좁은 화면에서는 기록 단이 따로 없다`() {
        show(state(decision = DecisionUi.Actions(emptyList())))
        assertTrue(!exists(GameTags.LOG_SIDE))
        compose.onNodeWithTag(GameTags.BANNER).assertIsDisplayed()
    }
}
