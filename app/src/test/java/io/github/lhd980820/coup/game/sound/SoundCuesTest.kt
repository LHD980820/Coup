package io.github.lhd980820.coup.game.sound

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.ResultUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 상태가 바뀔 때 어떤 소리를 낼지: 변화의 순간에만, 중요한 것부터 최대 둘. */
class SoundCuesTest {
    private val me = PlayerId("me")
    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val duke = RoleId("duke")

    private fun opp(id: PlayerId, coins: Int = 2, hidden: Int = 2, revealed: List<RoleId> = emptyList(), alive: Boolean = true) =
        OpponentUi(id, id.value, coins, hidden, revealed, alive, OpponentStatus.IDLE, emptySet(), null, false)

    private fun state(
        myCoins: Int = 2,
        myHand: List<CardUi> = listOf(CardUi(CardId(1), duke, false), CardUi(CardId(2), RoleId("captain"), false)),
        myAlive: Boolean = true,
        opponents: List<OpponentUi> = listOf(opp(a), opp(b)),
        decision: DecisionUi? = null,
        banner: Banner = Banner.WaitingFor(a),
        result: ResultUi? = null,
    ) = GameUiState(
        gameId = "g", turnNumber = 1, banner = banner, opponents = opponents,
        me = MyUi(me, "나", myCoins, myHand, myAlive, OpponentStatus.IDLE),
        deckSize = 9, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = result, busy = false, log = emptyList(),
    )

    private fun cues(before: GameUiState?, after: GameUiState) = SoundCues.between(before, after)

    private val respond = DecisionUi.Respond(WindowKind.ACTION, ActionId("tax"), a, null, a, setOf(duke), true, emptyList())
    private val actions = DecisionUi.Actions(emptyList())

    @Test
    fun `처음 그릴 때와 아무것도 안 바뀐 상태는 조용하다`() {
        assertEquals(emptyList<SoundCue>(), cues(null, state(decision = actions)))
        val s = state(decision = actions)
        assertEquals(emptyList<SoundCue>(), cues(s, s))
    }

    @Test
    fun `내 차례가 되는 순간에 한 번, 그대로 있는 동안에는 반복하지 않는다`() {
        assertEquals(listOf(SoundCue.YOUR_TURN), cues(state(), state(decision = actions)))
        assertEquals(emptyList<SoundCue>(), cues(state(decision = actions), state(decision = actions, banner = Banner.YourTurn(false))))
    }

    @Test
    fun `응답할 차례가 되는 순간에 한 번`() {
        assertEquals(listOf(SoundCue.RESPOND), cues(state(), state(decision = respond)))
        assertEquals(emptyList<SoundCue>(), cues(state(decision = respond), state(decision = respond, banner = Banner.WaitingFor(b))))
    }

    @Test
    fun `누구든 코인이 늘면 얻는 소리, 줄기만 하면 잃는 소리, 뺏기는 얻는 소리 하나`() {
        assertEquals(listOf(SoundCue.COIN_GAIN), cues(state(), state(myCoins = 3)))
        assertEquals(listOf(SoundCue.COIN_GAIN), cues(state(), state(opponents = listOf(opp(a, coins = 4), opp(b)))))
        assertEquals(listOf(SoundCue.COIN_LOSS), cues(state(myCoins = 5), state(myCoins = 2)))
        // 강탈: 내가 +2, 상대가 -2
        assertEquals(listOf(SoundCue.COIN_GAIN), cues(state(myCoins = 2, opponents = listOf(opp(a, coins = 5), opp(b))), state(myCoins = 4, opponents = listOf(opp(a, coins = 3), opp(b)))))
    }

    @Test
    fun `카드가 공개되면 카드 소리 - 상대의 공개와 내 공개 모두`() {
        assertEquals(listOf(SoundCue.CARD_REVEAL), cues(state(), state(opponents = listOf(opp(a, hidden = 1, revealed = listOf(duke)), opp(b)))))
        val lost = listOf(CardUi(CardId(1), duke, true), CardUi(CardId(2), RoleId("captain"), false))
        assertEquals(listOf(SoundCue.CARD_REVEAL), cues(state(), state(myHand = lost)))
        // 이미 공개돼 있던 것은 다시 울리지 않는다
        assertEquals(emptyList<SoundCue>(), cues(state(myHand = lost), state(myHand = lost)))
    }

    @Test
    fun `도전이 선언되는 순간에 한 번`() {
        val challenged = Banner.Challenged(a, b, setOf(duke), againstBlock = false)
        assertEquals(listOf(SoundCue.CHALLENGE), cues(state(), state(banner = challenged)))
        assertEquals(emptyList<SoundCue>(), cues(state(banner = challenged), state(banner = challenged)))
    }

    @Test
    fun `탈락하면 탈락 소리`() {
        val before = state(opponents = listOf(opp(a, hidden = 1), opp(b)))
        val after = state(opponents = listOf(opp(a, hidden = 0, revealed = listOf(duke, duke), alive = false), opp(b)))
        assertEquals(SoundCue.ELIMINATED, cues(before, after).first())
        assertEquals(listOf(SoundCue.ELIMINATED, SoundCue.CARD_REVEAL), cues(before, after))
        assertEquals(SoundCue.ELIMINATED, cues(state(), state(myAlive = false)).first())
    }

    @Test
    fun `판이 끝나면 이겼는지에 따라 승리 또는 패배 소리`() {
        val win = ResultUi(me, listOf(me, a, b), iWon = true, myRank = 1)
        val lose = ResultUi(a, listOf(a, me, b), iWon = false, myRank = 2)
        assertEquals(listOf(SoundCue.WIN), cues(state(), state(result = win)))
        assertEquals(listOf(SoundCue.LOSE), cues(state(), state(result = lose)))
        assertEquals(emptyList<SoundCue>(), cues(state(result = win), state(result = win)))
    }

    @Test
    fun `한꺼번에 일어나도 중요한 소리 둘까지만, 끝 소리가 먼저`() {
        val lose = ResultUi(a, listOf(a, me, b), iWon = false, myRank = 2)
        val before = state(myCoins = 5, decision = respond)
        val after = state(
            myCoins = 1, myAlive = false, result = lose,
            myHand = listOf(CardUi(CardId(1), duke, true), CardUi(CardId(2), RoleId("captain"), true)),
        )
        assertEquals(listOf(SoundCue.LOSE, SoundCue.ELIMINATED), cues(before, after))
        assertEquals(SoundCues.MAX_AT_ONCE, cues(before, after).size)
    }

    @Test
    fun `도전과 공개가 함께면 도전이 먼저, 내 차례와 코인이 함께면 내 차례가 먼저`() {
        val challenged = Banner.Challenged(a, b, setOf(duke), againstBlock = false)
        val revealed = state(opponents = listOf(opp(a, hidden = 1, revealed = listOf(duke)), opp(b)), banner = challenged)
        assertEquals(listOf(SoundCue.CHALLENGE, SoundCue.CARD_REVEAL), cues(state(), revealed))
        assertEquals(listOf(SoundCue.YOUR_TURN, SoundCue.COIN_GAIN), cues(state(), state(myCoins = 3, decision = actions)))
    }
}
