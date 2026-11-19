package io.github.lhd980820.coup.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 판 붙잡기(극적인 순간보다 결과가 먼저 보이지 않게)와 "누가 누구에게" 화살표의 규칙(화면 없이). */
class PacingAndArrowsTest {
    private val me = PlayerId("me")
    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val duke = RoleId("duke")

    private fun state(
        aAlive: Boolean = true,
        aCoins: Int = 2,
        meAlive: Boolean = true,
        banner: Banner = Banner.YourTurn(false),
        log: List<LogEntry> = emptyList(),
        decision: DecisionUi? = null,
        turn: PlayerId? = me,
    ) = GameUiState(
        gameId = "g", turnNumber = 3, banner = banner,
        opponents = listOf(
            OpponentUi(a, "A", aCoins, if (aAlive) 1 else 0, if (aAlive) listOf(RoleId("captain")) else listOf(RoleId("captain"), RoleId("contessa")), aAlive,
                if (aAlive) OpponentStatus.IDLE else OpponentStatus.ELIMINATED, emptySet(), null, false),
            OpponentUi(b, "B", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
        ),
        me = MyUi(me, "나", 3, listOf(CardUi(CardId(1), duke, !meAlive), CardUi(CardId(2), RoleId("contessa"), true)), meAlive, OpponentStatus.IDLE),
        deckSize = 9, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = log,
        turnPlayer = turn,
    )

    // ---- 판 붙잡기 ----

    private val before = state(log = listOf(LogEntry.Conceded(b)))
    private val after = state(aAlive = false, banner = Banner.WaitingFor(b), log = listOf(LogEntry.Conceded(b), LogEntry.Eliminated(a)), turn = b)

    @Test
    fun `붙잡힌 사람의 카드와 생사는 그대로 두고 나머지는 따라간다`() {
        val paced = BoardPacing.pace(before, after, held = setOf(a), busy = false)
        val shownA = paced.opponents.first { it.id == a }
        assertTrue(shownA.isAlive)
        assertEquals(1, shownA.hiddenCount)
        assertEquals(listOf(RoleId("captain")), shownA.revealed)
        assertEquals("붙잡히지 않은 것은 따라간다", Banner.WaitingFor(b), paced.banner)
        assertEquals(after.log, paced.log)
    }

    @Test
    fun `순간이 무대에 있는 동안에는 진행 문구와 기록과 차례도 붙잡는다`() {
        val paced = BoardPacing.pace(before, after, held = emptySet(), busy = true)
        assertEquals(before.banner, paced.banner)
        assertEquals(before.log, paced.log)
        assertEquals(me, paced.turnPlayer)
        assertTrue("붙잡히지 않은 사람의 카드는 따라간다", !paced.opponents.first { it.id == a }.isAlive)
    }

    @Test
    fun `아무것도 붙잡지 않으면 그대로 따라간다`() {
        assertEquals(after, BoardPacing.pace(before, after, held = emptySet(), busy = false))
    }

    @Test
    fun `내 손패와 생사도 붙잡는다`() {
        val dead = state(meAlive = false)
        val paced = BoardPacing.pace(state(), dead, held = setOf(me), busy = false)
        assertTrue(paced.me!!.isAlive)
        assertEquals(state().me!!.hand, paced.me!!.hand)
    }

    @Test
    fun `보이는 판에 지금 상태의 결정·코인·차례·상태를 얹는다`() {
        val board = BoardPacing.pace(before, after, held = setOf(a), busy = true)
        val live = after.copy(
            opponents = after.opponents.map { if (it.id == a) it.copy(coins = 9) else it },
            decision = DecisionUi.Actions(emptyList()),
            myDeadline = 123L,
            busy = true,
        )
        val shown = BoardPacing.withLive(board, live)
        val shownA = shown.opponents.first { it.id == a }
        assertEquals("코인은 지금 상태", 9, shownA.coins)
        assertTrue("생사는 보이는 판", shownA.isAlive)
        assertEquals("살아 있는 것으로 보이는 동안 탈락 상태는 내지 않는다", OpponentStatus.IDLE, shownA.status)
        assertEquals(DecisionUi.Actions(emptyList()), shown.decision)
        assertEquals(123L, shown.myDeadline)
        assertTrue(shown.busy)
        assertEquals("차례 표시는 보이는 판(이야기가 끝난 뒤에 넘어간다)", me, shown.turnPlayer)
        assertEquals("진행 문구는 보이는 판", before.banner, shown.banner)
        assertEquals(before.log, shown.log)
    }

    @Test
    fun `보이는 판에 지금 상태를 얹어도 붙잡은 내 손패와 생사는 그대로다`() {
        val held = BoardPacing.pace(state(), state(meAlive = false), held = setOf(me), busy = false)
        val shown = BoardPacing.withLive(held, state(meAlive = false).copy(decision = DecisionUi.Actions(emptyList())))
        assertTrue(shown.me!!.isAlive)
        assertEquals(state().me!!.hand, shown.me!!.hand)
        assertEquals("내 코인·결정은 지금 상태", DecisionUi.Actions(emptyList()), shown.decision)
    }

    // ---- 순간이 붙잡는 사람 ----

    @Test
    fun `순간마다 붙잡는 사람`() {
        assertEquals(setOf(me, a), Moment.Challenge(me, a, setOf(duke)).involves)
        assertEquals(setOf(a, me), Moment.Reveal(a, duke, false, setOf(duke), me).involves)
        assertEquals(setOf(a), Moment.Reveal(a, duke, true, emptySet(), null).involves)
        assertEquals("막기 선언만으로는 아무 카드도 바뀌지 않는다", emptySet<PlayerId>(), Moment.Block(a, duke, ActionId("foreign_aid")).involves)
        assertEquals(setOf(b), Moment.Coup(a, b).involves)
        assertEquals(setOf(a), Moment.Eliminated(a, false).involves)
    }

    @Test
    fun `무대는 오른 순간과 기다리는 순간의 사람을 붙잡고, 그 사람의 탈락이 오르면 놓아준다`() {
        val stage = MomentStage()
        stage.offer(Moment.Challenge(me, a, setOf(duke)))
        stage.offer(Moment.Reveal(a, RoleId("contessa"), false, setOf(duke), me))
        stage.offer(Moment.Eliminated(a, false))
        assertEquals(setOf(me, a), stage.held)
        stage.finished(stage.playing!!) // 도전 끝 -> 공개
        assertEquals(setOf(me, a), stage.held)
        stage.finished(stage.playing!!) // 공개 끝 -> A의 탈락이 오른다: A를 놓아준다
        assertEquals(emptySet<PlayerId>(), stage.held)
        stage.finished(stage.playing!!)
        assertEquals(emptySet<PlayerId>(), stage.held)
    }

    @Test
    fun `다른 순간이 재생되는 중에 줄 선 순간의 사람도 바로 붙잡는다`() {
        val stage = MomentStage()
        stage.offer(Moment.Block(b, duke, ActionId("foreign_aid"))) // 아무도 붙잡지 않는 순간이 재생 중
        assertEquals(emptySet<PlayerId>(), stage.held)
        stage.offer(Moment.Coup(a, me)) // 그 뒤에 줄 선 쿠: 나를 붙잡아야 쿠보다 내 카드 상실이 먼저 보이지 않는다
        assertEquals(setOf(me), stage.held)
    }

    // ---- 화살표 ----

    @Test
    fun `대상이 있는 행동은 행동한 사람에서 대상으로 화살표가 간다`() {
        assertEquals(
            listOf(ArrowSpec(a, me, ArrowKind.ATTACK, RoleId("captain"), ActionId("steal"))),
            arrowsFor(Banner.ActionDeclared(a, ActionId("steal"), me, setOf(RoleId("captain")), setOf(me))),
        )
        assertEquals(
            listOf(ArrowSpec(a, b, ArrowKind.ATTACK, null, ActionId("coup"))),
            arrowsFor(Banner.ActionDeclared(a, ActionId("coup"), b, emptySet(), emptySet())),
        )
        assertEquals(listOf(ArrowSpec(a, b, ArrowKind.ATTACK, null, ActionId("assassinate"))), arrowsFor(Banner.BlockWindow(a, ActionId("assassinate"), b, setOf(b))))
    }

    @Test
    fun `막기는 막는 사람에서 행동한 사람으로, 도전은 도전하는 사람에서 주장한 사람으로`() {
        assertEquals(listOf(ArrowSpec(b, a, ArrowKind.BLOCK, RoleId("contessa"), ActionId("assassinate"))), arrowsFor(Banner.BlockDeclared(b, RoleId("contessa"), a, ActionId("assassinate"), setOf(a))))
        assertEquals(listOf(ArrowSpec(me, a, ArrowKind.CHALLENGE, null, null)), arrowsFor(Banner.Challenged(me, a, setOf(duke), againstBlock = false)))
        assertEquals("me>a:challenge", arrowsFor(Banner.Challenged(me, a, setOf(duke), false)).single().label)
    }

    @Test
    fun `대상이 없는 행동과 다른 진행에는 화살표가 없다`() {
        listOf(
            Banner.ActionDeclared(a, ActionId("tax"), null, setOf(duke), setOf(me)),
            Banner.BlockWindow(a, ActionId("foreign_aid"), null, setOf(me)),
            Banner.YourTurn(false), Banner.WaitingFor(a), Banner.Exchanging(a),
            Banner.LosingInfluence(a, io.github.lhd980820.coup.engine.model.LossReason.ChallengeLost),
            Banner.GameOver(a, listOf(a, me)),
        ).forEach { assertEquals("$it", emptyList<ArrowSpec>(), arrowsFor(it)) }
    }

    @Test
    fun `위아래로 떨어진 자리는 마주 보는 테두리에서 나가고 들어온다`() {
        val top = Rect(0f, 0f, 100f, 100f)
        val bottom = Rect(0f, 300f, 100f, 400f)
        val curve = arrowCurve(top, bottom, gap = 5f)
        assertEquals(Offset(50f, 105f), curve.start)
        assertEquals(Offset(50f, 295f), curve.end)
        assertTrue("세로로 놓이면 오른쪽으로 휜다: ${curve.control}", curve.control.x > 50f)
    }

    @Test
    fun `같은 줄의 자리끼리는 아래로 크게 휘어 돌아간다`() {
        val left = Rect(0f, 0f, 100f, 120f)
        val right = Rect(110f, 0f, 210f, 120f)
        val curve = arrowCurve(right, left, gap = 5f, dip = 50f)
        assertEquals(Offset(160f, 125f), curve.start)
        assertEquals(Offset(50f, 125f), curve.end)
        assertTrue("자리 아래로 휜다: ${curve.control}", curve.control.y >= 125f + 50f)
        assertTrue("가운데는 두 자리 아래에 있다: ${curve.point(0.5f)}", curve.point(0.5f).y > 125f)
    }

    @Test
    fun `테두리와 만나는 점`() {
        val r = Rect(0f, 0f, 100f, 50f)
        assertEquals(Offset(100f, 25f), edgeToward(r, Offset(300f, 25f), gap = 0f))
        assertEquals(Offset(50f, 0f), edgeToward(r, Offset(50f, -200f), gap = 0f))
        assertEquals(Offset(50f, 25f), edgeToward(r, Offset(50f, 25f), gap = 0f))
        // 테두리에서 조금 떨어진 곳에서 나간다(자리의 테두리와 겹치지 않게)
        assertNear(Offset(105f, 25f), edgeToward(r, Offset(300f, 25f), gap = 5f))
        assertNear(Offset(50f, -5f), edgeToward(r, Offset(50f, -200f), gap = 5f))
    }

    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals("x: $actual", expected.x, actual.x, 0.01f)
        assertEquals("y: $actual", expected.y, actual.y, 0.01f)
    }
}
