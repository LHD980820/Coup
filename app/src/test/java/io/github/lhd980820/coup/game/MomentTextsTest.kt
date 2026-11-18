package io.github.lhd980820.coup.game

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 극적인 순간의 글: 무슨 일인지와 그 결과를 한국어 조사까지 맞춰 말한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class MomentTextsTest {
    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val duke = RoleId("duke")
    private val contessa = RoleId("contessa")

    private val state = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(
            OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
        ),
        me = MyUi(me, "나", 2, emptyList(), true, OpponentStatus.IDLE),
        deckSize = 9, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
    )

    private fun texts() = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, state)

    @Test
    fun `도전`() {
        assertEquals(GameTexts.MomentText("도전!", "내가 AI 1의 공작 주장에 도전합니다"), texts().moment(Moment.Challenge(me, a1, setOf(duke))))
        assertEquals("AI 2가 AI 1에게 도전합니다", texts().moment(Moment.Challenge(a2, a1, emptySet())).line)
    }

    @Test
    fun `증명 - 도전한 사람이 카드를 잃는다`() {
        val t = texts().moment(Moment.Reveal(a1, duke, proven = true, claimed = setOf(duke), challenger = me))
        assertEquals(GameTexts.MomentText("증명!", "AI 1은 정말 공작을 갖고 있었습니다", "도전한 내가 카드를 잃습니다"), t)
        // 도전자를 모르면 결과 줄이 없다
        assertNull(texts().moment(Moment.Reveal(a1, duke, true, emptySet(), null)).detail)
    }

    @Test
    fun `거짓말 - 거짓 주장과 잃는 카드`() {
        val t = texts().moment(Moment.Reveal(a2, contessa, proven = false, claimed = setOf(duke), challenger = me))
        assertEquals(GameTexts.MomentText("거짓말!", "AI 2는 공작이 없었습니다", "AI 2가 백작부인을 잃습니다"), t)
        assertEquals("나의 블러핑이 들통났습니다", texts().moment(Moment.Reveal(me, contessa, false, emptySet(), a1)).line)
    }

    @Test
    fun `막기와 쿠`() {
        assertEquals(GameTexts.MomentText("막기!", "AI 2가 백작부인으로 암살을 막습니다"), texts().moment(Moment.Block(a2, contessa, ActionId("assassinate"))))
        assertEquals(GameTexts.MomentText("쿠!", "AI 1: 나에게 쿠를 일으킵니다"), texts().moment(Moment.Coup(a1, me)))
    }

    @Test
    fun `탈락 - 남과 나`() {
        assertEquals(GameTexts.MomentText("탈락!", "AI 2가 모든 영향력을 잃었습니다"), texts().moment(Moment.Eliminated(a2, isMe = false)))
        assertEquals(GameTexts.MomentText("탈락했습니다", "남은 판은 지켜볼 수 있어요"), texts().moment(Moment.Eliminated(me, isMe = true)))
    }

    @Test
    @Config(sdk = [35], qualifiers = "en")
    fun `영어`() {
        assertEquals(GameTexts.MomentText("Proven!", "AI 1 really had the Duke", "나, who challenged, loses a card"), texts().moment(Moment.Reveal(a1, duke, true, setOf(duke), me)))
        assertEquals("AI 2 blocks Assassinate with the Contessa", texts().moment(Moment.Block(a2, contessa, ActionId("assassinate"))).line)
        assertEquals("You're out", texts().moment(Moment.Eliminated(me, true)).headline)
    }
}
