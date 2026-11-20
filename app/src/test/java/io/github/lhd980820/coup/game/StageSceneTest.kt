package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.theme.CoupTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 가운데 판의 그림 장면(docs/DESIGN.md "가운데 판"): 진행 배너 -> 누가, 무슨 카드/문양, 무엇을, 누구에게. 그리고 화살표가 판 아래로 지나가는 규칙. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class StageSceneTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private val state = GameUiState(
        gameId = "g", turnNumber = 3, banner = Banner.YourTurn(false),
        opponents = listOf(
            OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
        ),
        me = MyUi(me, "나", 3, listOf(CardUi(CardId(1), RoleId("duke"), false)), true, OpponentStatus.IDLE),
        deckSize = 9, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
    )
    private val texts = GameTexts(res, state)
    private val words = stageWordsOf(res)

    private fun scene(banner: Banner, standard: Boolean = true) = sceneOf(banner, me, texts, standard, words)

    @Test
    fun `역할을 주장한 행동은 그 역할 카드와 행동 이름, 표준 규칙이면 얻는 코인이 놓인다`() {
        val tax = scene(Banner.ActionDeclared(a1, ActionId("tax"), null, setOf(RoleId("duke")), setOf(me, a2)))
        assertEquals(a1, tax.actor)
        assertEquals(RoleId("duke"), tax.card)
        assertNull("카드가 있으면 문양은 없다", tax.token)
        assertEquals(texts.actionName(ActionId("tax")), tax.verb)
        assertEquals(3, tax.gain)
        assertNull(tax.target)
        assertEquals(setOf(me, a2), tax.waiting)
    }

    @Test
    fun `규칙이 바뀐 판에서는 얻는 코인 숫자를 보이지 않는다`() {
        assertNull(scene(Banner.ActionDeclared(a1, ActionId("tax"), null, setOf(RoleId("duke")), emptySet()), standard = false).gain)
    }

    @Test
    fun `대상이 있는 행동은 화살표 너머에 대상이 선다`() {
        val steal = scene(Banner.ActionDeclared(a1, ActionId("steal"), me, setOf(RoleId("captain")), setOf(me)))
        assertEquals(RoleId("captain"), steal.card)
        assertEquals(me, steal.target)
        assertEquals(2, steal.gain)
        val assassinate = scene(Banner.ActionDeclared(a1, ActionId("assassinate"), a2, setOf(RoleId("assassin")), emptySet()))
        assertNull("암살은 코인을 얻지 않는다", assassinate.gain)
        assertEquals(a2, assassinate.target)
    }

    @Test
    fun `역할 없는 행동은 문양이 놓인다 - 수입은 코인 한 닢, 해외 원조는 더미, 쿠는 번개`() {
        assertEquals(Token.Coin, scene(Banner.ActionDeclared(a1, ActionId("income"), null, emptySet(), emptySet())).token)
        val aid = scene(Banner.ActionDeclared(a1, ActionId("foreign_aid"), null, emptySet(), setOf(me)))
        assertEquals(Token.Coins, aid.token)
        assertEquals(2, aid.gain)
        val coup = scene(Banner.ActionDeclared(a1, ActionId("coup"), me, emptySet(), emptySet()))
        assertEquals(CoupIcons.Bolt, (coup.token as Token.Glyph).icon)
        assertEquals(me, coup.target)
        assertNull(coup.card)
    }

    @Test
    fun `막기는 막는 사람이 역할 카드를 들고 행동한 사람에게 맞선다`() {
        val block = scene(Banner.BlockDeclared(a2, RoleId("contessa"), a1, ActionId("assassinate"), setOf(a1)))
        assertEquals(a2, block.actor)
        assertEquals(RoleId("contessa"), block.card)
        assertEquals(words.block, block.verb)
        assertEquals(a1, block.target)
        assertEquals(setOf(a1), block.waiting)
    }

    @Test
    fun `도전은 칼을 든 도전자가 주장한 사람과 의심받는 카드에 맞선다`() {
        val challenge = scene(Banner.Challenged(me, a1, setOf(RoleId("duke")), againstBlock = false))
        assertEquals(me, challenge.actor)
        assertEquals(CoupIcons.Swords, (challenge.token as Token.Glyph).icon)
        assertEquals(words.challenge, challenge.verb)
        assertEquals(a1, challenge.target)
        assertEquals(RoleId("duke"), challenge.targetCard)
    }

    @Test
    fun `내 차례에는 별과 내 차례, 쿠만 할 수 있으면 번개다`() {
        val mine = scene(Banner.YourTurn(false))
        assertNull("내 얼굴은 바로 아래 내 자리에 있다", mine.actor)
        assertEquals(CoupIcons.Star, (mine.token as Token.Glyph).icon)
        assertEquals(words.yourTurn, mine.verb)
        assertEquals(CoupIcons.Bolt, (scene(Banner.YourTurn(true)).token as Token.Glyph).icon)
    }

    @Test
    fun `기다림·카드 잃기·교환·끝도 장면이 된다`() {
        assertEquals(a1, scene(Banner.WaitingFor(a1)).actor)
        assertEquals(words.turn, scene(Banner.WaitingFor(a1)).verb)
        assertEquals(words.losing, scene(Banner.LosingInfluence(a2, LossReason.Concede)).verb)
        assertEquals(RoleId("ambassador"), scene(Banner.Exchanging(a1)).card)
        val over = scene(Banner.GameOver(me, listOf(me, a1, a2)))
        assertEquals(me, over.actor)
        assertEquals(words.wins, over.verb)
    }

    @Test
    fun `스크린리더 문장에는 기다리는 사람이 붙는다`() {
        val banner = Banner.ActionDeclared(a1, ActionId("tax"), null, setOf(RoleId("duke")), setOf(a2))
        val spoken = spokenScene(res, texts.banner(banner), scene(banner), texts)
        assertTrue(spoken, spoken.startsWith(texts.banner(banner)) && spoken.endsWith(res.getString(io.github.lhd980820.coup.R.string.game_waiting_on, "AI 2")))
        val alone = Banner.ActionDeclared(a1, ActionId("tax"), null, setOf(RoleId("duke")), emptySet())
        assertEquals(texts.banner(alone), spokenScene(res, texts.banner(alone), scene(alone), texts))
    }

    // ---- 화살표는 판 아래로 지나간다 ----

    @Test
    fun `화살표의 문양은 판에 가려지지 않는 곳에 실린다`() {
        val curve = arrowCurve(Rect(0f, 0f, 100f, 100f), Rect(0f, 600f, 100f, 700f), gap = 5f)
        assertEquals(curve.point(0.5f), discPoint(curve, null))
        // 판이 곡선 가운데를 덮으면 판 밖의 가장 가까운 곳으로 옮긴다
        val stage = Rect(-50f, 300f, 400f, 420f)
        assertTrue(stage.contains(curve.point(0.5f)))
        val moved = discPoint(curve, stage)
        assertTrue("$moved", !stage.contains(moved))
        // 위아래로 0.05씩 번갈아 찾으므로 가운데에서 멀지 않다
        val t = (0..100).map { it / 100f }.minBy { (curve.point(it) - moved).getDistance() }
        assertTrue("t=$t", t in 0.2f..0.8f)
        // 어디나 가려지면 가운데
        assertEquals(curve.point(0.5f), discPoint(curve, Rect(-1000f, -1000f, 1000f, 1000f)))
    }

    @Test
    fun `판이 있으면 그 자리를 적어 두고, 판이 사라지면 지운다`() {
        val anchors = TableAnchors()
        val shown = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { 0L }) {
                CoupTheme { if (shown.value) StagePanel(state, texts, anchors = anchors) }
            }
        }
        compose.waitForIdle()
        assertNotNull(anchors.stage)
        assertTrue(anchors.stage!!.height > 0f)
        shown.value = false
        compose.waitForIdle()
        assertNull(anchors.stage)
    }

    @Test
    fun `판은 내 결정의 남은 시간을 보이지만, 응답할 때는 보이지 않는다(응답 시트가 보인다)`() {
        val deadline = 1_000_000L + 9_000
        val pick = state.copy(decision = io.github.lhd980820.coup.presentation.DecisionUi.PickTarget(ActionId("steal"), listOf(a1)), myDeadline = deadline)
        val respond = state.copy(
            decision = io.github.lhd980820.coup.presentation.DecisionUi.Respond(
                io.github.lhd980820.coup.engine.model.WindowKind.ACTION, ActionId("tax"), a1, null, a1, setOf(RoleId("duke")), true, emptyList(),
            ),
            myDeadline = deadline,
        )
        val shown = mutableStateOf(pick)
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { 1_000_000L }) {
                CoupTheme { StagePanel(shown.value, GameTexts(res, shown.value)) }
            }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithTag(GameTags.TIMER_MINE).fetchSemanticsNodes().size)
        shown.value = respond
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(GameTags.TIMER_MINE).fetchSemanticsNodes().size)
    }
}
