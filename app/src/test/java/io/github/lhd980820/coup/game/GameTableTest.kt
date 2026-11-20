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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
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
    fun `주장 중인 역할은 자리에 들어 보인 카드로, 탈락은 도장으로 나온다`() {
        show(state(opponents = listOf(opponent(1, claiming = setOf(RoleId("duke")), status = OpponentStatus.ACTING), opponent(2, alive = false))))
        // 글자 대신 카드 그림이지만 스크린리더에는 "공작 주장"으로 읽힌다
        compose.onNodeWithTag(GameTags.claim("p1")).assertIsDisplayed().assertContentDescriptionEquals(string(R.string.game_claiming, string(R.string.game_role_duke)))
        assertTrue("주장은 글자로 쓰지 않는다", compose.onAllNodesWithText(string(R.string.game_claiming, string(R.string.game_role_duke))).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(string(R.string.game_eliminated)).assertIsDisplayed()
    }

    @Test
    fun `자리의 상태는 글자 대신 그림이고 스크린리더에는 글로 읽힌다`() {
        show(
            state(
                opponents = listOf(
                    opponent(1, status = OpponentStatus.THINKING),
                    opponent(2, status = OpponentStatus.RESPONDED),
                    opponent(3, status = OpponentStatus.ACTING),
                ),
            ),
        )
        listOf(R.string.game_status_thinking, R.string.game_status_responded, R.string.game_status_acting).forEach { id ->
            compose.onNodeWithContentDescription(string(id)).assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText(string(id)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `들어 보인 카드는 자리 크기를 바꾸지 않는다`() {
        // 같은 줄의 자리는 늘 높이가 같으므로 옆 자리가 아니라 같은 자리의 주장 전후를 비교한다
        val shown = androidx.compose.runtime.mutableStateOf(state(opponents = listOf(opponent(1), opponent(2))))
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }) {
                CoupTheme { GameScreen(shown.value, emptyFlow(), Recorder(), onExit = {}, onPlayAgain = {}) }
            }
        }
        val before = compose.onNodeWithTag(GameTags.opponent("p1")).getUnclippedBoundsInRoot()
        shown.value = state(opponents = listOf(opponent(1, claiming = setOf(RoleId("captain"))), opponent(2)))
        compose.waitForIdle()
        val claiming = compose.onNodeWithTag(GameTags.opponent("p1")).getUnclippedBoundsInRoot()
        assertEquals(before.bottom - before.top, claiming.bottom - claiming.top)
        // 카드는 자리 안, 카드 줄 위에 놓인다
        val card = compose.onNodeWithTag(GameTags.claim("p1")).getUnclippedBoundsInRoot()
        assertTrue("$card in $claiming", card.top >= claiming.top && card.bottom <= claiming.bottom)
        val hidden = compose.onNodeWithTag(GameTags.hidden("p1", 0)).getUnclippedBoundsInRoot()
        assertTrue("들어 보인 카드가 카드 줄과 겹친다: $card / $hidden", card.top < hidden.bottom && hidden.top < card.bottom)
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
    fun `몇 번째 턴인지는 기록 창의 머리에 나온다`() {
        show(state())
        assertTrue("테이블에는 턴 글자가 없다", compose.onAllNodesWithText(string(R.string.game_turn, 7)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(GameTags.LOG_OPEN).performClick()
        compose.onNode(hasText(string(R.string.game_turn, 7)) and hasAnyAncestor(hasTestTag(GameTags.LOG_SHEET))).assertIsDisplayed()
    }

    @Test
    fun `가운데 판은 그림이고 스크린리더에는 지금 상황이 문장으로 읽힌다, 덱은 표시줄 구석에 있다`() {
        show(state(banner = Banner.WaitingFor(PlayerId("p2"))))
        compose.onNodeWithTag(GameTags.BANNER).assertIsDisplayed()
        compose.onNode(hasContentDescription(string(R.string.game_waiting_for, "AI 2")) and hasAnyAncestor(hasTestTag(GameTags.BANNER))).assertIsDisplayed()
        assertTrue("판에 문장을 쓰지 않는다", compose.onAllNodesWithText(string(R.string.game_waiting_for, "AI 2")).fetchSemanticsNodes().none { node ->
            generateSequence(node.parent) { it.parent }.any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == GameTags.BANNER }
        })
        // 덱은 그림이지만 스크린리더에는 "덱 N장"으로 읽힌다
        compose.onNodeWithContentDescription(string(R.string.game_deck, 9)).assertIsDisplayed()
    }

    @Test
    fun `응답을 기다리는 사람이 있으면 판이 얼굴로 보이고 스크린리더에는 누구를 기다리는지 읽힌다`() {
        val banner = Banner.ActionDeclared(PlayerId("p1"), ActionId("tax"), null, setOf(RoleId("duke")), setOf(PlayerId("p2")))
        show(state(banner = banner))
        compose.onNode(hasContentDescription(string(R.string.game_waiting_on, "AI 2"), substring = true) and hasAnyAncestor(hasTestTag(GameTags.BANNER))).assertIsDisplayed()
    }

    @Test
    fun `기다리는 사람이 없으면 기다림을 읽지 않는다`() {
        show(state(banner = Banner.ActionDeclared(PlayerId("p1"), ActionId("tax"), null, setOf(RoleId("duke")), emptySet())))
        compose.onAllNodes(hasContentDescription(string(R.string.game_waiting_on, ""), substring = true)).assertCountEquals(0)
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
    fun `응답 시트의 머리는 판과 같은 그림이고, 스크린리더에는 누가 누구에게 무엇을 하려는지와 주장이 한 번씩 읽힌다`() {
        val s = state(banner = assassinate, decision = respond)
        show(s)
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        val headline = texts.respondHeadline(assassinate, respond.actor, respond.actionId, respond.windowKind)
        // 머리말에는 대상(나)이 들어 있고 주장 문구는 들어 있지 않다
        assertEquals(string(R.string.game_banner_action, "AI 1", texts.actionDescription(ActionId("assassinate"), me)), headline)
        val claim = string(R.string.game_claiming, string(R.string.game_role_assassin))
        assertFalse(headline.contains(claim))
        val spoken = compose.onNode(hasTestTag(GameTags.BANNER) and hasAnyAncestor(hasTestTag(GameTags.RESPONSE_SHEET)))
            .assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(spoken, spoken.startsWith(headline))
        assertEquals(spoken, 1, spoken.windowed(claim.length).count { it == claim })
        // 글로 쓰지 않는다
        assertTrue(compose.onAllNodesWithText(headline).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `막기에 응답할 때의 머리는 막기 선언이다`() {
        val block = Banner.BlockDeclared(PlayerId("p2"), RoleId("contessa"), PlayerId("p1"), ActionId("assassinate"), setOf(me))
        val s = state(banner = block, decision = respond.copy(windowKind = WindowKind.BLOCK_CHALLENGE, claimant = PlayerId("p2"), claimedRoles = setOf(RoleId("contessa"))))
        show(s)
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        compose.onNode(hasTestTag(GameTags.BANNER) and hasContentDescription(texts.banner(block), substring = true)).assertIsDisplayed()
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

    private fun descriptions(tag: String): List<String> =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }

    private fun texts(tag: String): List<String> =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    @Test
    fun `행동 칸의 글은 이름 하나뿐이고, 역할과 블러핑은 그림이며 스크린리더에는 이름부터 글로 읽힌다`() {
        show(state(decision = actions))
        val tax = string(R.string.game_action_tax)
        assertEquals(listOf(tax), texts(GameTags.action("tax")))
        assertEquals(listOf("$tax, " + string(R.string.game_role_duke)), descriptions(GameTags.action("tax")))
        val steal = string(R.string.game_action_steal)
        assertEquals(listOf(steal), texts(GameTags.action("steal")))
        assertEquals(listOf("$steal, " + string(R.string.game_role_captain), string(R.string.game_bluff)), descriptions(GameTags.action("steal")))
        // 드는 코인은 이름과 함께: "암살 (-3), 암살자"
        val assassinate = string(R.string.game_action_cost, string(R.string.game_action_assassinate), 3)
        assertEquals(listOf("$assassinate, " + string(R.string.game_role_assassin), string(R.string.game_bluff)), descriptions(GameTags.action("assassinate")))
        // 역할을 주장하지 않는 행동에는 둘 다 없다
        assertEquals(listOf(string(R.string.game_action_income)), texts(GameTags.action("income")))
        assertEquals(emptyList<String>(), descriptions(GameTags.action("income")))
    }

    @Test
    fun `할 수 없는 행동은 잠겨 있고 자물쇠의 이유가 읽히며, 이유가 블러핑 표시보다 먼저다`() {
        val buttons = DecisionUi.Actions(listOf(action("assassinate", cost = 3, enabled = false, bluff = true, claim = "assassin", reason = DisabledReason.NO_VALID_TARGET), action("income")))
        show(state(decision = buttons))
        compose.onNodeWithTag(GameTags.action("assassinate")).assertIsNotEnabled()
        val said = descriptions(GameTags.action("assassinate"))
        assertTrue(said.toString(), string(R.string.game_reason_no_target) in said)
        assertFalse(said.toString(), string(R.string.game_bluff) in said)
        compose.onNodeWithTag(GameTags.action("income")).assertIsEnabled()
    }

    @Test
    fun `쿠만 할 수 있을 때는 필수 표시가 붙는다`() {
        show(state(decision = DecisionUi.Actions(listOf(action("coup", cost = 7, forced = true)))))
        assertTrue(string(R.string.game_forced) in descriptions(GameTags.action("coup")))
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
    fun `기록은 테이블에 없고 표시줄 구석의 버튼으로 연다`() {
        val log = listOf(LogEntry.Conceded(PlayerId("p1")))
        val s = state(log = log)
        show(s)
        val line = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s).log(log[0])!!
        assertTrue("폰의 테이블 가운데에는 기록이 없다", compose.onAllNodesWithText(line).fetchSemanticsNodes().isEmpty())
        // 그림 버튼이지만 스크린리더에는 "진행 기록"으로 읽히고, 누르는 영역은 48dp다
        compose.onNodeWithTag(GameTags.LOG_OPEN).assertIsDisplayed().assertContentDescriptionEquals(string(R.string.game_log_title)).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(hasText(line) and hasAnyAncestor(hasTestTag(GameTags.LOG_SHEET))).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.LOG_CLOSE).performClick()
        assertFalse(exists(GameTags.LOG_SHEET))
    }

    @Test
    fun `기록은 가장 최근 일이 맨 위에 나온다`() {
        val log = listOf(LogEntry.Conceded(PlayerId("p1")), LogEntry.Eliminated(PlayerId("p2")))
        val s = state(log = log)
        show(s)
        compose.onNodeWithTag(GameTags.LOG_OPEN).performClick()
        val texts = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, s)
        val older = compose.onNodeWithText(texts.log(log[0])!!).getUnclippedBoundsInRoot().top
        val newest = compose.onNodeWithText(texts.log(log[1])!!).getUnclippedBoundsInRoot().top
        assertTrue("최근 일($newest)이 옛 일($older)보다 위에 있어야 한다", newest < older)
    }

    @Test
    fun `기권은 표시줄 구석의 깃발 그림이고 스크린리더에는 기권으로 읽힌다`() {
        show(state())
        compose.onNodeWithTag(GameTags.CONCEDE).assertIsDisplayed().assertContentDescriptionEquals(string(R.string.game_concede)).assertHeightIsAtLeast(48.dp)
        assertTrue(compose.onAllNodesWithText(string(R.string.game_concede)).fetchSemanticsNodes().isEmpty())
    }

    // ---- 자리 카드 크기 ----

    @Test
    fun `자리에 줄 높이가 남으면 카드가 커지고, 모자라면 기본 크기다`() {
        assertEquals(50.dp, seatCardHeight(null, 1, 120.dp))
        assertEquals(50.dp, seatCardHeight(120.dp, 1, 120.dp))
        // 넉넉하면 너비(카드 두 장)가 상한이다: (120 - 20) / 2 / 0.714
        assertEquals(70.0f, seatCardHeight(400.dp, 1, 120.dp).value, 0.1f)
        // 아주 넓고 높아도 상한을 넘지 않는다
        assertEquals(84.dp, seatCardHeight(800.dp, 1, 200.dp))
        // 두 줄이면 남는 높이를 나눠 쓴다: (360 - 10 - 140 x 2 - 18) / 2 = 26 -> 50 + 26 x 0.75
        assertTrue(seatCardHeight(300.dp, 2, 200.dp) < seatCardHeight(300.dp, 1, 200.dp))
        assertEquals(69.5f, seatCardHeight(360.dp, 2, 200.dp).value, 0.1f)
    }

    @Test
    fun `높이가 남는 화면에서는 자리의 카드가 기본보다 크다`() {
        show(state(opponents = listOf(opponent(1), opponent(2))))
        val card = compose.onNodeWithTag(GameTags.hidden("p1", 0)).getUnclippedBoundsInRoot()
        assertTrue("$card", card.bottom - card.top > 50.dp)
    }
}
