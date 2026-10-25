package io.github.lhd980820.coup.game

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 게임 화면 폴리시(Phase 6): 남은 시간 링, 역할 능력 시트, 응답 시트, 큰 화면 두 단 배치. 상태를 직접 만들어 화면만 확인한다. */
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

    // ---- 응답 시트 ----

    private fun respond(canChallenge: Boolean = true, blocks: List<BlockButtonUi> = emptyList(), roles: Set<RoleId> = setOf(RoleId("duke"))) =
        DecisionUi.Respond(WindowKind.ACTION, ActionId("tax"), ai, null, ai, roles, canChallenge, blocks)

    private class Recorder : GameActions by NoActions {
        val calls = mutableListOf<String>()
        override fun pass() { calls += "pass" }
        override fun challenge() { calls += "challenge" }
        override fun block(role: RoleId) { calls += "block:${role.value}" }
    }

    private fun showWith(state: GameUiState, actions: GameActions) {
        compose.setContent { CompositionLocalProvider(LocalNowMillis provides { now }) { CoupTheme { GameScreen(state, emptyFlow(), actions, onExit = {}, onPlayAgain = {}) } } }
    }

    @Test
    fun `응답할 차례면 아래에 응답 시트가 붙고 허용·도전·막기가 모두 들어 있다`() {
        show(state(decision = respond(blocks = listOf(BlockButtonUi(RoleId("contessa"), bluff = true)))))
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.PASS).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.CHALLENGE).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.block("contessa")).assertIsDisplayed()
        // 누가 무엇을 주장하는지도 시트에 보인다
        compose.onNodeWithText(string(R.string.game_claiming, string(R.string.game_role_duke)), substring = true).assertIsDisplayed()
    }

    @Test
    fun `주장이 없는 행동에는 주장 줄이 없다`() {
        show(state(decision = respond(roles = emptySet())))
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.game_claiming, ""), substring = true).assertCountEquals(0)
    }

    @Test
    fun `응답 시트의 버튼이 각각 맞는 의도를 보낸다`() {
        val rec = Recorder()
        showWith(state(decision = respond(blocks = listOf(BlockButtonUi(RoleId("contessa"), bluff = false)))), rec)
        compose.onNodeWithTag(GameTags.PASS).performClick()
        compose.onNodeWithTag(GameTags.CHALLENGE).performClick()
        compose.onNodeWithTag(GameTags.block("contessa")).performClick()
        assertEquals(listOf("pass", "challenge", "block:contessa"), rec.calls)
    }

    @Test
    fun `도전할 수 없으면 도전 버튼이 없고, 막을 수 없으면 막기 버튼이 없다`() {
        show(state(decision = respond(canChallenge = false)))
        compose.onNodeWithTag(GameTags.PASS).assertIsDisplayed()
        assertTrue(!exists(GameTags.CHALLENGE))
        assertTrue(!exists(GameTags.block("contessa")))
    }

    @Test
    fun `블러핑 막기는 글자로도 표시된다`() {
        show(state(decision = respond(blocks = listOf(BlockButtonUi(RoleId("contessa"), bluff = true)))))
        compose.onNodeWithTag(GameTags.block("contessa")).assertTextContains(string(R.string.game_bluff), substring = true)
    }

    @Test
    fun `응답 시트는 나타날 때 스크린리더가 읽을 이름을 가진다`() {
        show(state(decision = respond()))
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET)
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, string(R.string.game_respond_pane)))
    }

    @Test
    fun `응답 시트의 버튼은 터치 타깃 48dp 이상이다`() {
        show(state(decision = respond(blocks = listOf(BlockButtonUi(RoleId("contessa"), bluff = false)))))
        listOf(GameTags.PASS, GameTags.CHALLENGE, GameTags.block("contessa")).forEach {
            compose.onNodeWithTag(it).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `응답 중 남은 시간은 시트에 한 번만 보인다`() {
        show(state(myDeadline = now + 15_000, decision = respond()))
        compose.onAllNodesWithTag(GameTags.TIMER_MINE).assertCountEquals(1)
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.TIMER_MINE).assertTextContains(string(R.string.game_timer_seconds, 15))
    }

    @Test
    fun `응답 차례가 아니면 응답 시트가 없다`() {
        show(state(decision = DecisionUi.Actions(emptyList())))
        assertTrue(!exists(GameTags.RESPONSE_SHEET))
    }

    @Test
    fun `명령을 보내는 중이면 응답 버튼이 잠긴다`() {
        show(state(decision = respond()).copy(busy = true))
        compose.onNodeWithTag(GameTags.PASS).assertIsNotEnabled()
        compose.onNodeWithTag(GameTags.CHALLENGE).assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h568dp")
    fun `작은 화면에서도 막기가 둘이어도 응답 시트가 잘리지 않는다`() {
        show(state(decision = respond(blocks = listOf(BlockButtonUi(RoleId("captain"), bluff = true), BlockButtonUi(RoleId("ambassador"), bluff = true)))))
        listOf(GameTags.PASS, GameTags.CHALLENGE, GameTags.block("captain"), GameTags.block("ambassador"), GameTags.card(1), GameTags.CONCEDE).forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
    }
}
