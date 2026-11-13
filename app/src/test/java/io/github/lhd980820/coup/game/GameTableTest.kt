package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.DisabledReason
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 테이블 모습으로 다시 짠 게임 화면(백로그 1, docs/DESIGN.md)의 동작: 자리 배치, 자리 눌러 대상 고르기, 가운데 무대와 응답 시트, 행동 칸, 기록 순서. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class GameTableTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val now = 1_000_000L
    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun opponent(i: Int, targetable: Boolean = false, status: OpponentStatus = OpponentStatus.IDLE, claiming: Set<RoleId> = emptySet(), alive: Boolean = true) =
        OpponentUi(PlayerId("p$i"), "AI $i", 2, if (alive) 2 else 0, emptyList(), alive, status, claiming, null, targetable)

    private fun action(id: String, cost: Int = 0, enabled: Boolean = true, bluff: Boolean = false, claim: String? = null, reason: DisabledReason? = null, forced: Boolean = false) =
        ActionButtonUi(ActionId(id), cost, enabled, reason, bluff, setOfNotNull(claim?.let(::RoleId)), needsTarget = false, validTargets = emptyList(), forced = forced)

    private fun state(
        opponents: List<OpponentUi> = listOf(opponent(1), opponent(2)),
        banner: Banner = Banner.YourTurn(false),
        decision: DecisionUi? = null,
        deadline: Long? = null,
        busy: Boolean = false,
        log: List<LogEntry> = emptyList(),
        coins: Int = 4,
    ) = GameUiState(
        gameId = "g", turnNumber = 7, banner = banner, opponents = opponents,
        me = MyUi(me, "나", coins, listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), false)), true, OpponentStatus.IDLE),
        deckSize = 9, decision = decision, myDeadline = deadline, connection = ConnectionState.CONNECTED, result = null, busy = busy, log = log,
    )

    private class Recorder : GameActions {
        val targets = mutableListOf<PlayerId>()
        val chosen = mutableListOf<ActionId>()
        override fun chooseAction(id: ActionId) { chosen += id }
        override fun chooseTarget(id: PlayerId) { targets += id }
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

    private fun show(state: GameUiState, actions: GameActions = Recorder()) {
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }) {
                CoupTheme { GameScreen(state, emptyFlow(), actions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    // ---- 자리 ----

    private fun assertSeatsFit(count: Int, screenWidth: Int) {
        (1..count).forEach { i ->
            val node = compose.onNodeWithTag(GameTags.opponent("p$i")).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("AI $i 자리가 화면 왼쪽으로 나갔다: $bounds", bounds.left >= 0.dp)
            assertTrue("AI $i 자리가 화면 오른쪽으로 나갔다: $bounds", bounds.right <= screenWidth.dp)
        }
    }

    @Test
    fun `상대가 다섯이어도 옆으로 밀지 않고 모두 화면 안에 보인다`() {
        show(state(opponents = (1..5).map { opponent(it) }))
        assertSeatsFit(5, 360)
    }

    @Test
    fun `상대가 다섯이면 세 자리와 두 자리 두 줄로 앉는다`() {
        show(state(opponents = (1..5).map { opponent(it) }))
        val tops = (1..5).map { compose.onNodeWithTag(GameTags.opponent("p$it")).getUnclippedBoundsInRoot().top }
        assertEquals("첫 줄 셋은 같은 높이", 1, tops.take(3).toSet().size)
        assertEquals("둘째 줄 둘은 같은 높이", 1, tops.drop(3).toSet().size)
        assertTrue("둘째 줄이 아래에 있다", tops[3] > tops[0])
    }

    @Test
    fun `상대가 넷이면 둘씩 두 줄로 앉는다`() {
        show(state(opponents = (1..4).map { opponent(it) }))
        val tops = (1..4).map { compose.onNodeWithTag(GameTags.opponent("p$it")).getUnclippedBoundsInRoot().top }
        assertEquals(tops[0], tops[1])
        assertEquals(tops[2], tops[3])
        assertTrue(tops[2] > tops[0])
        assertSeatsFit(4, 360)
    }

    @Test
    fun `한 줄의 자리들은 높이가 같다`() {
        // 한 사람만 주장 중이라 상태 줄이 길어도 옆 자리와 높이가 같아야 한다
        show(state(opponents = listOf(opponent(1, claiming = setOf(RoleId("contessa")), status = OpponentStatus.ACTING), opponent(2), opponent(3))))
        val heights = (1..3).map { compose.onNodeWithTag(GameTags.opponent("p$it")).getUnclippedBoundsInRoot().let { b -> b.bottom - b.top } }
        assertEquals(1, heights.toSet().size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h568dp")
    fun `좁은 화면에서는 줄을 늘려서라도 자리가 화면 밖으로 나가지 않는다`() {
        show(state(opponents = (1..3).map { opponent(it) }))
        (1..3).forEach { i ->
            val bounds = compose.onNodeWithTag(GameTags.opponent("p$i")).getUnclippedBoundsInRoot()
            assertTrue("AI $i: $bounds", bounds.left >= 0.dp && bounds.right <= 320.dp)
            // 카드 두 장의 누르는 영역(48dp씩)이 들어갈 너비
            assertTrue("AI $i 자리가 너무 좁다: $bounds", bounds.right - bounds.left >= 104.dp)
        }
    }

    @Test
    fun `주장 중인 역할과 탈락은 자리에 글자로 나온다`() {
        show(state(opponents = listOf(opponent(1, claiming = setOf(RoleId("duke")), status = OpponentStatus.ACTING), opponent(2, alive = false))))
        compose.onNodeWithText(string(R.string.game_claiming, string(R.string.game_role_duke))).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.game_eliminated)).assertIsDisplayed()
    }

    // ---- 자리를 눌러 대상 고르기 ----

    private val pickTarget = DecisionUi.PickTarget(ActionId("steal"), listOf(PlayerId("p1")))

    @Test
    fun `대상을 고르는 중에는 고를 수 있는 자리를 눌러서 고른다`() {
        val rec = Recorder()
        show(state(opponents = listOf(opponent(1, targetable = true), opponent(2)), decision = pickTarget), rec)
        compose.onNodeWithTag(GameTags.opponent("p1")).assertHasClickAction().performClick()
        assertEquals(listOf(PlayerId("p1")), rec.targets)
    }

    @Test
    fun `고를 수 없는 자리는 눌러지지 않는다`() {
        val rec = Recorder()
        show(state(opponents = listOf(opponent(1, targetable = true), opponent(2)), decision = pickTarget), rec)
        compose.onNodeWithTag(GameTags.opponent("p2")).assertHasNoClickAction()
        assertTrue(rec.targets.isEmpty())
    }

    @Test
    fun `대상을 고르는 중이 아니면 자리는 눌러지지 않는다`() {
        show(state(opponents = listOf(opponent(1, targetable = true)), decision = DecisionUi.Actions(emptyList())))
        compose.onNodeWithTag(GameTags.opponent("p1")).assertHasNoClickAction()
    }

    @Test
    fun `명령을 보내는 중에는 자리를 눌러도 고르지 않는다`() {
        show(state(opponents = listOf(opponent(1, targetable = true)), decision = pickTarget, busy = true))
        compose.onNodeWithTag(GameTags.opponent("p1")).assertHasNoClickAction()
    }

    @Test
    fun `자리로 고를 수 있어도 이름 버튼은 그대로 있다`() {
        val rec = Recorder()
        show(state(opponents = listOf(opponent(1, targetable = true)), decision = pickTarget), rec)
        compose.onNodeWithTag(GameTags.target("p1")).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(PlayerId("p1")), rec.targets)
    }

    // ---- 표시줄, 가운데 무대, 코인 ----

    @Test
    fun `표시줄에 몇 번째 턴인지 나온다`() {
        show(state())
        compose.onNodeWithText(string(R.string.game_turn, 7)).assertIsDisplayed()
    }

    @Test
    fun `가운데 무대에 지금 상황과 덱이 나온다`() {
        show(state(banner = Banner.WaitingFor(PlayerId("p2"))))
        compose.onNodeWithTag(GameTags.BANNER).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.game_waiting_for, "AI 2")) and hasAnyAncestor(hasTestTag(GameTags.BANNER))).assertIsDisplayed()
        // 덱은 그림이지만 스크린리더에는 "덱 N장"으로 읽힌다
        compose.onNodeWithContentDescription(string(R.string.game_deck, 9)).assertIsDisplayed()
    }

    @Test
    fun `응답을 기다리는 사람이 있으면 무대에 누구를 기다리는지 나온다`() {
        val banner = Banner.ActionDeclared(PlayerId("p1"), ActionId("tax"), null, setOf(RoleId("duke")), setOf(PlayerId("p2")))
        show(state(banner = banner))
        compose.onNodeWithText(string(R.string.game_waiting_on, "AI 2")).assertIsDisplayed()
    }

    @Test
    fun `기다리는 사람이 없으면 기다림 줄이 없다`() {
        show(state(banner = Banner.ActionDeclared(PlayerId("p1"), ActionId("tax"), null, setOf(RoleId("duke")), emptySet())))
        compose.onAllNodesWithText(string(R.string.game_waiting_on, ""), substring = true).assertCountEquals(0)
    }

    @Test
    fun `코인은 그림과 숫자로 보이고 스크린리더에는 코인 N으로 읽힌다`() {
        show(state(coins = 4))
        compose.onNodeWithTag(GameTags.MY_COINS).assertIsDisplayed().assertContentDescriptionEquals(string(R.string.game_coins, 4))
        compose.onNodeWithTag(GameTags.coins("p1")).assertContentDescriptionEquals(string(R.string.game_coins, 2))
    }

    // ---- 응답할 차례: 시트가 무대를 대신한다 ----

    private val assassinate = Banner.ActionDeclared(PlayerId("p1"), ActionId("assassinate"), me, setOf(RoleId("assassin")), setOf(me))
    private val respond = DecisionUi.Respond(WindowKind.ACTION, ActionId("assassinate"), PlayerId("p1"), me, PlayerId("p1"), setOf(RoleId("assassin")), true, emptyList())

    @Test
    fun `응답할 차례에는 지금 상황이 응답 시트 안에 한 번만 나온다`() {
        show(state(banner = assassinate, decision = respond, deadline = now + 9_000))
        compose.onAllNodesWithTag(GameTags.BANNER).assertCountEquals(1)
        compose.onNode(hasTestTag(GameTags.BANNER) and hasAnyAncestor(hasTestTag(GameTags.RESPONSE_SHEET))).assertIsDisplayed()
    }

    @Test
    fun `응답 시트의 머리말은 누가 누구에게 무엇을 하려는지 말하고, 주장은 따로 한 번만 나온다`() {
        val s = state(banner = assassinate, decision = respond)
        show(s)
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        val headline = texts.respondHeadline(assassinate, respond.actor, respond.actionId, respond.windowKind)
        // 머리말에는 대상(나)이 들어 있고 주장 문구는 들어 있지 않다
        assertEquals(string(R.string.game_banner_action, "AI 1", texts.actionDescription(ActionId("assassinate"), me)), headline)
        assertFalse(headline.contains(string(R.string.game_claiming, string(R.string.game_role_assassin))))
        compose.onNodeWithText(headline).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.game_claiming, string(R.string.game_role_assassin)), substring = true).assertCountEquals(1)
    }

    @Test
    fun `막기에 응답할 때의 머리말은 막기 선언 문장이다`() {
        val block = Banner.BlockDeclared(PlayerId("p2"), RoleId("contessa"), PlayerId("p1"), ActionId("assassinate"), setOf(me))
        val s = state(banner = block, decision = respond.copy(windowKind = WindowKind.BLOCK_CHALLENGE, claimant = PlayerId("p2"), claimedRoles = setOf(RoleId("contessa"))))
        show(s)
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        compose.onNodeWithText(texts.banner(block)).assertIsDisplayed()
    }

    @Test
    fun `응답이 끝나 내 결정이 없어지면 가운데 무대가 돌아온다`() {
        show(state(banner = assassinate, decision = null))
        assertFalse(exists(GameTags.RESPONSE_SHEET))
        compose.onNodeWithTag(GameTags.BANNER).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.game_deck, 9)).assertIsDisplayed()
    }

    // ---- 행동 칸 ----

    private val actions = DecisionUi.Actions(
        listOf(
            action("income"),
            action("foreign_aid"),
            action("coup", cost = 7, enabled = false, reason = DisabledReason.INSUFFICIENT_COINS),
            action("tax", claim = "duke"),
            action("assassinate", cost = 3, bluff = true, claim = "assassin"),
            action("steal", bluff = true, claim = "captain"),
            action("exchange", bluff = true, claim = "ambassador"),
        ),
    )

    @Test
    fun `행동 칸은 모두 한 화면에 보이고 누르는 영역이 충분하다`() {
        show(state(decision = actions))
        actions.buttons.forEach { compose.onNodeWithTag(GameTags.action(it.actionId.value)).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
    }

    @Test
    fun `들고 있는 역할의 행동에는 역할 이름이, 블러핑에는 블러핑이 글자로 나온다`() {
        show(state(decision = actions))
        compose.onNodeWithTag(GameTags.action("tax")).assertTextContains(string(R.string.game_role_duke))
        compose.onNodeWithTag(GameTags.action("steal")).assertTextContains(string(R.string.game_bluff))
        // 역할을 주장하지 않는 행동에는 둘 다 없다
        val config = compose.onNodeWithTag(GameTags.action("income")).fetchSemanticsNode().config
        val income = (if (config.contains(SemanticsProperties.Text)) config[SemanticsProperties.Text] else emptyList()).map { it.text }
        assertEquals(listOf(string(R.string.game_action_income)), income)
    }

    @Test
    fun `할 수 없는 행동은 잠겨 있고 이유가 글자로 나오며, 이유가 블러핑 표시보다 먼저다`() {
        val buttons = DecisionUi.Actions(listOf(action("assassinate", cost = 3, enabled = false, bluff = true, claim = "assassin", reason = DisabledReason.NO_VALID_TARGET), action("income")))
        show(state(decision = buttons))
        compose.onNodeWithTag(GameTags.action("assassinate")).assertIsNotEnabled().assertTextContains(string(R.string.game_reason_no_target))
        compose.onNodeWithTag(GameTags.action("income")).assertIsEnabled()
    }

    @Test
    fun `쿠만 할 수 있을 때는 필수라고 나온다`() {
        show(state(decision = DecisionUi.Actions(listOf(action("coup", cost = 7, forced = true)))))
        compose.onNodeWithTag(GameTags.action("coup")).assertTextContains(string(R.string.game_forced))
    }

    @Test
    fun `행동 칸을 누르면 그 행동을 고르고, 명령을 보내는 중에는 잠긴다`() {
        val rec = Recorder()
        show(state(decision = actions), rec)
        compose.onNodeWithTag(GameTags.action("tax")).performClick()
        assertEquals(listOf(ActionId("tax")), rec.chosen)
    }

    @Test
    fun `명령을 보내는 중에는 행동 칸이 잠긴다`() {
        show(state(decision = actions, busy = true))
        compose.onNodeWithTag(GameTags.action("income")).assertIsNotEnabled()
    }

    // ---- 기록 ----

    @Test
    fun `기록은 가장 최근 일이 맨 위에 나온다`() {
        val log = listOf(LogEntry.Conceded(PlayerId("p1")), LogEntry.Eliminated(PlayerId("p2")))
        val s = state(log = log)
        show(s)
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        val older = compose.onNodeWithText(texts.log(log[0])!!).getUnclippedBoundsInRoot().top
        val newest = compose.onNodeWithText(texts.log(log[1])!!).getUnclippedBoundsInRoot().top
        assertTrue("최근 일($newest)이 옛 일($older)보다 위에 있어야 한다", newest < older)
    }
}
