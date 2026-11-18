package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.ResultUi
import io.github.lhd980820.coup.presentation.UiEffect
import io.github.lhd980820.coup.pressBack
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 극적인 순간(백로그 1, docs/DESIGN.md "극적인 순간")의 화면 동작: 띠가 나타나고 사라지는 때, 차례로 보이는 것, 누르는 것을 막지 않는 것,
 * 탈락 도장, 내 카드를 잃을 때의 진동, 마지막 순간이 지나간 뒤에 뜨는 결과. 시계를 멈추고 정해진 시각으로 넘기며 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class MomentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val now = 1_000_000L
    private val duke = setOf(RoleId("duke"))
    private val effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 64)

    private class RecordingHaptics : HapticFeedback {
        val calls = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            calls += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()

    private class Recorder : GameActions {
        val chosen = mutableListOf<ActionId>()
        var exits = 0
        override fun chooseAction(id: ActionId) { chosen += id }
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

    private fun state(
        a2Alive: Boolean = true,
        meAlive: Boolean = true,
        decision: DecisionUi? = null,
        result: ResultUi? = null,
    ) = GameUiState(
        gameId = "g", turnNumber = 3, banner = Banner.YourTurn(false),
        opponents = listOf(
            OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 0, if (a2Alive) 1 else 0, if (a2Alive) listOf(RoleId("captain")) else listOf(RoleId("captain"), RoleId("duke")), a2Alive, OpponentStatus.IDLE, emptySet(), null, false),
        ),
        me = MyUi(
            me, "나", 3,
            if (meAlive) listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), false))
            else listOf(CardUi(CardId(1), RoleId("duke"), true), CardUi(CardId(2), RoleId("contessa"), true)),
            meAlive, OpponentStatus.IDLE,
        ),
        deckSize = 9, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = result, busy = false, log = emptyList(),
    )

    private val won = ResultUi(me, listOf(me, a1, a2), iWon = true, myRank = 1)

    private var playAgain = 0

    private fun show(s: MutableState<GameUiState>, speed: MotionSpeed, actions: GameActions = Recorder()) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }, LocalMotion provides Motion(speed), LocalHapticFeedback provides haptics) {
                CoupTheme { GameScreen(s.value, emptyFlow(), actions, onExit = {}, onPlayAgain = { playAgain++ }, effects = effects) }
            }
        }
        frames(48) // 구성 -> 연출 신호를 받기 시작할 때까지
    }

    private fun frames(millis: Long) = compose.mainClock.advanceTimeBy(millis)

    private fun emit(vararg e: UiEffect) = e.forEach { assertTrue(effects.tryEmit(it)) }

    /** 시계를 멈춘 채 상태를 바꾼다: 메인 스레드에서 쓰고 곧바로 알려야 다음 프레임에 구성된다. */
    private fun update(s: MutableState<GameUiState>, next: GameUiState) {
        compose.runOnUiThread {
            s.value = next
            Snapshot.sendApplyNotifications()
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** 지금 보이는 순간의 이름. 없으면 null. */
    private fun moment(): String? = compose.onAllNodesWithTag(GameTags.MOMENT).fetchSemanticsNodes().firstOrNull()?.config?.get(MomentKind)

    private fun landed(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[StampLanded]

    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    // ---- 나타나고 사라진다 ----

    @Test
    fun `연출이 꺼져 있으면 극적인 순간이 나타나지 않는다`() {
        show(mutableStateOf(state()), MotionSpeed.OFF)
        emit(UiEffect.Challenged(a1, me, duke), UiEffect.CardRevealed(me, RoleId("duke"), true), UiEffect.Eliminated(a2))
        frames(300)
        assertNull(moment())
    }

    @Test
    fun `도전이 오면 순간이 나타났다가 정해진 시간 뒤에 사라진다`() {
        show(mutableStateOf(state()), MotionSpeed.NORMAL)
        emit(UiEffect.Challenged(a1, me, duke))
        frames(100)
        assertEquals("challenge", moment())
        frames(900)
        assertEquals("아직 보인다", "challenge", moment())
        frames(400)
        assertNull("1150ms 뒤에는 사라진다", moment())
    }

    @Test
    fun `도전과 그 결과가 함께 와도 차례로 보인다`() {
        show(mutableStateOf(state()), MotionSpeed.NORMAL)
        emit(UiEffect.Challenged(me, a1, duke), UiEffect.CardRevealed(a1, RoleId("contessa"), false))
        frames(100)
        assertEquals("challenge", moment())
        frames(1_200)
        assertEquals("bluff", moment())
        frames(2_100)
        assertNull(moment())
    }

    @Test
    fun `빠르게로 두면 같은 순간이 절반 시간에 끝난다`() {
        show(mutableStateOf(state()), MotionSpeed.FAST)
        emit(UiEffect.Challenged(a1, me, duke))
        frames(300)
        assertEquals("challenge", moment())
        frames(400)
        assertNull("575ms 뒤에는 사라진다", moment())
    }

    @Test
    fun `평범한 행동 선언과 코인은 순간이 아니다`() {
        show(mutableStateOf(state()), MotionSpeed.NORMAL)
        emit(UiEffect.ActionDeclared(a1, ActionId("income"), null, emptySet()), UiEffect.CoinsChanged(a1, 1))
        frames(200)
        assertNull(moment())
    }

    @Test
    fun `쿠·막기·탈락도 순간이 된다`() {
        show(mutableStateOf(state()), MotionSpeed.NORMAL)
        emit(UiEffect.ActionDeclared(a1, ActionId("coup"), me, emptySet()))
        frames(100)
        assertEquals("coup", moment())
        frames(1_300)
        emit(UiEffect.BlockDeclared(a2, RoleId("duke"), ActionId("foreign_aid")))
        frames(100)
        assertEquals("block", moment())
        frames(1_200)
        emit(UiEffect.Eliminated(me))
        frames(100)
        assertEquals("eliminated_me", moment())
    }

    @Test
    fun `순간이 떠 있어도 그 아래의 버튼은 눌린다`() {
        val actions = Recorder()
        val income = ActionButtonUi(ActionId("income"), 0, true, null, false, emptySet(), false, emptyList(), false)
        show(mutableStateOf(state(decision = DecisionUi.Actions(listOf(income)))), MotionSpeed.NORMAL, actions)
        emit(UiEffect.ActionDeclared(a1, ActionId("coup"), me, emptySet()))
        frames(400)
        assertEquals("coup", moment())
        compose.onNodeWithTag(GameTags.action("income")).performClick()
        assertEquals(listOf(ActionId("income")), actions.chosen)
    }

    @Test
    fun `띠의 글은 그림이라 스크린리더에 두 번 읽히지 않는다`() {
        show(mutableStateOf(state()), MotionSpeed.NORMAL)
        emit(UiEffect.Challenged(a1, me, duke))
        frames(500)
        assertEquals("challenge", moment())
        compose.onAllNodesWithText(string(R.string.moment_challenge)).assertCountEquals(0)
    }

    // ---- 탈락 도장 ----

    @Test
    fun `탈락한 상대 자리에는 도장이 찍혀 있고 살아 있는 자리에는 없다(연출이 꺼져 있어도)`() {
        show(mutableStateOf(state(a2Alive = false)), MotionSpeed.OFF)
        compose.onNodeWithTag(GameTags.stamp("a2")).assertIsDisplayed()
        assertTrue(landed(GameTags.stamp("a2")))
        assertFalse(exists(GameTags.stamp("a1")))
        assertFalse(exists(GameTags.MY_STAMP))
    }

    @Test
    fun `탈락하는 순간을 보면 도장이 떨어지며 찍힌다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        assertFalse(exists(GameTags.stamp("a2")))
        update(s, state(a2Alive = false))
        frames(48)
        assertFalse("막 떨어지는 중", landed(GameTags.stamp("a2")))
        frames(1_000)
        assertTrue(landed(GameTags.stamp("a2")))
    }

    @Test
    fun `연출이 꺼져 있으면 탈락하는 순간에도 도장이 바로 찍혀 있다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.OFF)
        update(s, state(a2Alive = false))
        frames(48)
        assertTrue(landed(GameTags.stamp("a2")))
    }

    @Test
    fun `처음부터 탈락해 있던 자리는 도장이 그냥 찍혀 있다`() {
        show(mutableStateOf(state(a2Alive = false)), MotionSpeed.NORMAL)
        assertTrue(landed(GameTags.stamp("a2")))
    }

    @Test
    fun `내가 탈락하면 내 손패에 도장이 찍힌다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        assertFalse(exists(GameTags.MY_STAMP))
        update(s, state(meAlive = false))
        frames(48)
        compose.onNodeWithTag(GameTags.MY_STAMP).assertIsDisplayed()
        assertFalse(landed(GameTags.MY_STAMP))
        frames(1_000)
        assertTrue(landed(GameTags.MY_STAMP))
    }

    // ---- 내 카드를 잃을 때 ----

    @Test
    fun `내 카드를 잃으면 진동하고(연출이 꺼져 있어도) 남의 카드는 아니다`() {
        show(mutableStateOf(state()), MotionSpeed.OFF)
        emit(UiEffect.InfluenceLost(a1, RoleId("duke")))
        frames(48)
        assertTrue(haptics.calls.isEmpty())
        emit(UiEffect.InfluenceLost(me, RoleId("duke")))
        frames(48)
        assertEquals(1, haptics.calls.size)
    }

    // ---- 판이 끝날 때 ----

    @Test
    fun `판이 끝나도 마지막 탈락 순간이 지나간 뒤에 결과가 뜬다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        emit(UiEffect.Eliminated(a2))
        update(s, state(a2Alive = false, result = won))
        frames(400)
        assertEquals("eliminated", moment())
        assertFalse("마지막 순간을 보는 동안에는 결과가 아직 없다", exists(GameTags.RESULT))
        frames(1_500)
        assertTrue(exists(GameTags.RESULT))
        assertNull("결과가 뜨면 순간은 사라진다", moment())
    }

    @Test
    fun `끝을 알리는 상태가 마지막 연출 신호보다 조금 먼저 와도 기다린다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        update(s, state(a2Alive = false, result = won))
        frames(64)
        emit(UiEffect.Eliminated(a2))
        frames(400)
        assertEquals("eliminated", moment())
        assertFalse(exists(GameTags.RESULT))
    }

    @Test
    fun `연출이 꺼져 있으면 결과가 바로 뜬다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.OFF)
        emit(UiEffect.Eliminated(a2))
        update(s, state(a2Alive = false, result = won))
        frames(48)
        assertTrue(exists(GameTags.RESULT))
    }

    @Test
    fun `마지막 순간이 없으면 잠깐만 기다리고 결과가 뜬다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        update(s, state(result = won))
        frames(400)
        assertTrue(exists(GameTags.RESULT))
    }

    @Test
    fun `처음 그릴 때 이미 끝난 판이면 기다리지 않고 결과를 보인다`() {
        show(mutableStateOf(state(result = won)), MotionSpeed.NORMAL)
        assertTrue(exists(GameTags.RESULT))
    }

    @Test
    fun `마지막 순간을 보는 중에 뒤로 가기를 누르면 결과로 바로 넘어간다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        emit(UiEffect.Eliminated(a2))
        update(s, state(a2Alive = false, result = won))
        frames(400)
        assertFalse(exists(GameTags.RESULT))
        compose.pressBack()
        frames(48)
        assertTrue(exists(GameTags.RESULT))
        assertNull("결과가 뜨면 남은 순간은 보이지 않는다", moment())
        assertEquals("뒤로 가기는 결과를 넘기지 않고 보여 주기만 한다", 0, playAgain)
    }

    @Test
    fun `마지막 순간이 길게 밀려 있어도 결과는 정해진 시간 안에 뜬다`() {
        val s = mutableStateOf(state())
        show(s, MotionSpeed.NORMAL)
        // 도전과 공개가 두 번: 다 보면 5초가 넘는다
        emit(
            UiEffect.Challenged(a1, me, duke), UiEffect.CardRevealed(me, RoleId("duke"), true),
            UiEffect.Challenged(me, a1, duke), UiEffect.CardRevealed(a1, RoleId("captain"), false),
        )
        update(s, state(result = won))
        frames(3_000)
        assertFalse(exists(GameTags.RESULT))
        frames(1_000)
        assertTrue("3.6초 남짓이면 결과가 뜬다", exists(GameTags.RESULT))
        assertNull(moment())
    }
}
