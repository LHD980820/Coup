package io.github.lhd980820.coup.game

import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 화면 문구: 모든 배너/로그 종류가 채워지고(자리표시자 남지 않음), 한국어/영어 둘 다 되며, 모르는 역할·행동도 깨지지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameTextsTest {
    private val me = PlayerId("me")
    private val ai = PlayerId("ai")
    private val duke = RoleId("duke")
    private val mystery = RoleId("future_role")

    private val state = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(OpponentUi(ai, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false)),
        me = MyUi(me, "나", 2, emptyList(), true, OpponentStatus.IDLE),
        deckSize = 11, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
    )

    private val banners = listOf(
        Banner.YourTurn(false), Banner.YourTurn(true), Banner.WaitingFor(ai),
        Banner.ActionDeclared(ai, ActionId("tax"), null, setOf(duke), setOf(me)),
        Banner.ActionDeclared(ai, ActionId("steal"), me, setOf(RoleId("captain")), setOf(me)),
        Banner.ActionDeclared(me, ActionId("coup"), ai, emptySet(), setOf(ai)),
        Banner.BlockDeclared(ai, duke, me, ActionId("foreign_aid"), setOf(me)),
        Banner.BlockWindow(ai, ActionId("steal"), me, setOf(me)),
        Banner.Challenged(me, ai, setOf(duke), againstBlock = false),
        Banner.Challenged(me, ai, setOf(duke), againstBlock = true),
        Banner.LosingInfluence(me, LossReason.ChallengeLost), Banner.Exchanging(ai), Banner.GameOver(me, listOf(me, ai)),
        // 코어에 새 행동/역할이 생겨도 화면이 깨지지 않는다
        Banner.ActionDeclared(ai, ActionId("future_action"), me, setOf(mystery), setOf(me)),
    )

    private val logs = listOf(
        LogEntry.ActionDeclared(ai, ActionId("assassinate"), me, setOf(RoleId("assassin"))),
        LogEntry.Blocked(me, RoleId("contessa"), ActionId("assassinate")),
        LogEntry.Challenged(me, ai, setOf(duke)),
        LogEntry.Revealed(ai, duke, proven = true), LogEntry.Revealed(ai, mystery, proven = false),
        LogEntry.InfluenceLost(me, RoleId("captain"), LossReason.BluffExposed),
        LogEntry.CardReplaced(ai), LogEntry.ExchangeDone(ai),
        LogEntry.Resolved(ActionId("steal"), ActionOutcome.BLOCKED), LogEntry.Resolved(ActionId("steal"), ActionOutcome.FAILED),
        LogEntry.Conceded(ai), LogEntry.Eliminated(ai), LogEntry.GameEnded(me),
    )

    private fun texts() = GameTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources, state)

    private fun check(t: GameTexts) {
        banners.forEach { b ->
            val text = t.banner(b)
            assertTrue("배너가 비었다: $b", text.isNotBlank())
            assertFalse("자리표시자가 남았다: $text", Regex("%\\d?\\$?[sd]").containsMatchIn(text))
        }
        logs.forEach { l ->
            val text = t.log(l)
            if (text != null) {
                assertTrue(text.isNotBlank())
                assertFalse("자리표시자가 남았다: $text", Regex("%\\d?\\$?[sd]").containsMatchIn(text))
            }
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ko")
    fun `한국어 문구`() {
        check(texts())
        assertEquals("공작", texts().role(duke))
        assertEquals("나: 세금을 걷습니다 (+3) (공작 주장)", texts().banner(Banner.ActionDeclared(me, ActionId("tax"), null, setOf(duke), emptySet())))
    }

    @Test
    @Config(sdk = [35], qualifiers = "en")
    fun `영어 문구`() {
        check(texts())
        assertEquals("Duke", texts().role(duke))
        assertEquals("나: collects Tax (+3) (claims Duke)", texts().banner(Banner.ActionDeclared(me, ActionId("tax"), null, setOf(duke), emptySet())))
    }

    @Test
    fun `모르는 역할과 행동은 ID 그대로 보여준다`() {
        val t = texts()
        assertEquals("future_action", t.actionName(ActionId("future_action")))
        assertEquals(RoleUiCatalog.of(mystery).art, RoleUiCatalog.cardBack)
    }
}
