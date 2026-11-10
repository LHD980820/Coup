package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardPurpose
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.pressBack
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

/** 카드 확대(백로그 2): 내 카드와 상대의 공개된 카드를 크게 본다. 고르는 중에는 탭이 선택이라 길게 눌러 확대한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class CardZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private val ai = PlayerId("ai")
    private val now = 1_000_000L
    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private val hand = listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), true))

    private fun state(
        revealed: List<String> = listOf("assassin"),
        hidden: Int = 1,
        decision: DecisionUi? = null,
        deadline: Long? = null,
        busy: Boolean = false,
        ruleSet: io.github.lhd980820.coup.engine.rules.RuleSetConfig? = null,
        myHand: List<CardUi> = hand,
    ) = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(OpponentUi(ai, "AI 1", 2, hidden, revealed.map(::RoleId), true, OpponentStatus.IDLE, emptySet(), null, false)),
        me = MyUi(PlayerId("me"), "나", 2, myHand, true, OpponentStatus.IDLE),
        deckSize = 11, decision = decision, myDeadline = deadline, connection = ConnectionState.CONNECTED, result = null, busy = busy, log = emptyList(),
        ruleSet = ruleSet,
    )

    private class Recorder : GameActions {
        val selected = mutableListOf<CardId>()
        val toggled = mutableListOf<CardId>()
        override fun chooseAction(id: ActionId) = Unit
        override fun chooseTarget(id: PlayerId) = Unit
        override fun cancelTarget() = Unit
        override fun pass() = Unit
        override fun challenge() = Unit
        override fun block(role: RoleId) = Unit
        override fun selectCard(id: CardId) { selected += id }
        override fun confirmCard() = Unit
        override fun toggleExchange(id: CardId) { toggled += id }
        override fun confirmExchange() = Unit
        override fun concede() = Unit
    }

    private fun show(state: GameUiState, actions: GameActions = Recorder()) {
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }) {
                CoupTheme { GameScreen(state, emptyFlow(), actions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    private fun pick(cards: List<CardUi> = hand) = DecisionUi.PickCard(CardPurpose.LOSE, cards, emptySet(), null, null)

    // ---- 내 카드 ----

    @Test
    fun `내 카드를 탭하면 큰 카드와 이름, 능력, 내 카드라는 표시가 나온다`() {
        show(state())
        assertTrue(!exists(GameTags.CARD_ZOOM))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.game_role_ability_duke)).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.CARD_ZOOM_OWNER).assertTextContains(string(R.string.game_zoom_mine))
    }

    @Test
    fun `이미 잃어서 공개된 내 카드는 그렇게 표시된다`() {
        show(state())
        compose.onNodeWithTag(GameTags.card(2)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM_OWNER).assertTextContains(string(R.string.game_zoom_mine_lost))
        compose.onNodeWithText(string(R.string.game_role_ability_contessa)).assertIsDisplayed()
    }

    @Test
    fun `확대한 카드 그림 자체가 작은 카드보다 훨씬 크고 충분히 크다`() {
        show(state())
        val small = compose.onNodeWithTag(GameTags.card(1)).fetchSemanticsNode().size
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        val name = string(R.string.game_role_duke)
        // 창 전체가 아니라 확대 창 안의 카드 그림 노드만 잰다(이름·설명을 빼도 커야 한다)
        val image = compose.onNode(hasContentDescription(name) and hasAnyAncestor(hasTestTag(GameTags.CARD_ZOOM)), useUnmergedTree = true)
        image.assertHeightIsAtLeast(200.dp)
        val zoom = image.fetchSemanticsNode().size
        assertTrue("확대 그림 ${zoom.height}px vs 작은 카드 ${small.height}px", zoom.height > small.height * 2)
    }

    @Test
    fun `카드를 고르는 중에는 탭이 선택이고 확대는 뜨지 않는다`() {
        val rec = Recorder()
        show(state(decision = pick()), rec)
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertEquals(listOf(CardId(1)), rec.selected)
        assertTrue(!exists(GameTags.CARD_ZOOM))
    }

    @Test
    fun `카드를 고르는 중에도 길게 누르면 확대하고 선택은 바뀌지 않는다`() {
        val rec = Recorder()
        show(state(decision = pick()), rec)
        compose.onNodeWithTag(GameTags.card(1)).performTouchInput { longClick() }
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        assertTrue("길게 누르기는 선택이 아니다: ${rec.selected}", rec.selected.isEmpty())
    }

    @Test
    fun `교환 카드를 고르는 중에도 탭은 선택이고 길게 누르면 확대`() {
        val rec = Recorder()
        val exchange = DecisionUi.PickExchange(hand, 1, 0)
        show(state(decision = exchange), rec)
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertEquals(listOf(CardId(1)), rec.toggled)
        assertTrue(!exists(GameTags.CARD_ZOOM))
        compose.onNodeWithTag(GameTags.card(2)).performTouchInput { longClick() }
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        assertEquals(listOf(CardId(1)), rec.toggled)
    }

    @Test
    fun `명령을 보내는 중에도 확대는 볼 수 있고, 고르는 중이면 선택만 잠긴다`() {
        show(state(busy = true))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
    }

    @Test
    fun `처리 중에 고르는 카드를 탭해도 선택되지 않는다`() {
        val rec = Recorder()
        show(state(decision = pick(), busy = true), rec)
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertTrue(rec.selected.isEmpty())
        assertTrue(!exists(GameTags.CARD_ZOOM))
    }

    // ---- 상대의 공개된 카드 ----

    @Test
    fun `상대의 공개된 카드를 탭하면 그 사람 이름과 함께 확대된다`() {
        show(state(revealed = listOf("assassin")))
        compose.onNodeWithTag(GameTags.flip("ai", 0)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.CARD_ZOOM_OWNER).assertTextContains(string(R.string.game_zoom_opponent_lost, "AI 1"))
        compose.onNodeWithText(string(R.string.game_role_ability_assassin)).assertIsDisplayed()
    }

    @Test
    fun `공개된 카드가 둘이면 각각 자기 역할이 확대된다`() {
        show(state(revealed = listOf("assassin", "captain"), hidden = 0))
        compose.onNodeWithTag(GameTags.flip("ai", 1)).performClick()
        compose.onNodeWithText(string(R.string.game_role_ability_captain)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(string(R.string.game_role_ability_assassin)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `상대의 공개된 카드를 누르는 영역은 48dp 이상이다`() {
        show(state(revealed = listOf("assassin")))
        compose.onNodeWithTag(GameTags.flip("ai", 0)).assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `가려진 상대 카드는 무엇인지 모르므로 확대할 수 없다`() {
        show(state(revealed = emptyList(), hidden = 2))
        compose.onNodeWithTag(GameTags.hidden("ai", 0)).assertHasNoClickAction()
        compose.onNodeWithTag(GameTags.hidden("ai", 1)).assertHasNoClickAction()
        assertTrue(!exists(GameTags.CARD_ZOOM))
    }

    // ---- 닫기와 부가 정보 ----

    @Test
    fun `닫기 버튼으로 닫는다`() {
        show(state())
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM_CLOSE).performClick()
        compose.waitUntil(5_000) { !exists(GameTags.CARD_ZOOM) }
    }

    @Test
    fun `뒤로 가기로도 닫는다`() {
        show(state())
        compose.onNodeWithTag(GameTags.flip("ai", 0)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        compose.pressBack()
        compose.waitUntil(5_000) { !exists(GameTags.CARD_ZOOM) }
    }

    @Test
    fun `내 결정의 시간이 흐르는 동안에는 확대 창에도 남은 시간이 보인다`() {
        show(state(decision = DecisionUi.Actions(emptyList()), deadline = now + 20_000))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.onNodeWithTag(GameTags.TIMER_ZOOM).assertIsDisplayed().assertTextContains(string(R.string.game_timer_seconds, 20))
    }

    @Test
    fun `결정할 것이 없거나 제한이 없으면 확대 창에 시간이 없다`() {
        show(state(decision = null, deadline = now + 20_000))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertTrue(!exists(GameTags.TIMER_ZOOM))
        compose.onNodeWithTag(GameTags.CARD_ZOOM_CLOSE).performClick()
        compose.waitUntil(5_000) { !exists(GameTags.CARD_ZOOM) }
    }

    @Test
    fun `제한이 없는 결정에서도 확대 창에 시간은 없다`() {
        show(state(decision = DecisionUi.Actions(emptyList()), deadline = null))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        assertTrue(!exists(GameTags.TIMER_ZOOM))
    }

    @Test
    fun `규칙이 바뀐 판의 확대 창에는 설명이 다를 수 있다는 안내가 나온다`() {
        val custom = BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"))
        show(state(ruleSet = custom))
        compose.onNodeWithTag(GameTags.flip("ai", 0)).performClick()
        compose.onNodeWithText(string(R.string.game_ability_note)).assertIsDisplayed()
    }

    @Test
    fun `표준 규칙의 판에는 설명이 다를 수 있다는 안내가 나오지 않는다`() {
        show(state(ruleSet = BuiltinRules.classicConfig()))
        compose.onNodeWithTag(GameTags.flip("ai", 0)).performClick()
        compose.onNodeWithTag(GameTags.CARD_ZOOM).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(string(R.string.game_ability_note)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `모르는 역할도 확대 창이 깨지지 않는다`() {
        show(state(revealed = listOf("future_role")))
        compose.onNodeWithTag(GameTags.flip("ai", 0)).performClick()
        compose.onNodeWithText(string(R.string.game_role_ability_unknown)).assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w320dp-h568dp", fontScale = 1.5f)
    @Test
    fun `작은 화면 큰 글씨에서도 확대 창의 닫기 버튼에 닿을 수 있다`() {
        show(state(decision = DecisionUi.Actions(emptyList()), deadline = now + 20_000, ruleSet = BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"))))
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        // 창이 화면보다 길면 안에서 스크롤된다: 닫기 버튼으로 스크롤해 눌러 닫는다
        compose.onNodeWithTag(GameTags.CARD_ZOOM_CLOSE).performScrollTo().performClick()
        compose.waitUntil(5_000) { !exists(GameTags.CARD_ZOOM) }
    }
}

