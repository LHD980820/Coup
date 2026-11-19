package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.UiEffect
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 테이블이 이야기를 순서대로 들려주는가(오너 피드백 2026-11-19):
 * 남은 카드가 1장인 사람에게 도전하면 결과(탈락)가 띠의 공개보다 먼저 보이지 않는다, 누가 누구에게 무엇을 하는지 화살표로 보인다,
 * 누구의 차례인지 분명히 보인다, 역할을 주장하는 사람은 그 카드를 내밀고 있다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class TableStoryTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val now = 1_000_000L
    private val duke = setOf(RoleId("duke"))
    private val effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 64)

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

    private val claimTax = LogEntry.ActionDeclared(a2, ActionId("tax"), null, duke)
    private val eliminated = LogEntry.Eliminated(a2)

    /** AI 2는 카드가 1장 남았고 공작을 주장해 세금을 걷으려 한다. */
    private fun before(turn: PlayerId? = a2) = GameUiState(
        gameId = "g", turnNumber = 5, banner = Banner.ActionDeclared(a2, ActionId("tax"), null, duke, setOf(me)),
        opponents = listOf(
            OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 4, 1, listOf(RoleId("captain")), true, OpponentStatus.IDLE, duke, null, false),
        ),
        me = MyUi(me, "나", 3, listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), false)), true, OpponentStatus.IDLE),
        deckSize = 9, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = listOf(claimTax),
        turnPlayer = turn,
    )

    /** 내가 도전했고, AI 2는 고를 것 없이 마지막 카드(백작부인)를 뒤집어 블러핑이 들통나 탈락했다. 다음은 AI 1의 차례. */
    private fun after(decision: DecisionUi? = null, myCoins: Int = 3) = before().copy(
        banner = Banner.WaitingFor(a1),
        opponents = listOf(
            OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.ACTING, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 4, 0, listOf(RoleId("captain"), RoleId("contessa")), false, OpponentStatus.ELIMINATED, emptySet(), null, false),
        ),
        me = before().me!!.copy(coins = myCoins),
        decision = decision,
        log = listOf(claimTax, LogEntry.Challenged(me, a2, duke), LogEntry.Revealed(a2, RoleId("contessa"), false), LogEntry.InfluenceLost(a2, RoleId("contessa"), LossReason.BluffExposed), eliminated),
        turnPlayer = a1,
    )

    private fun show(s: MutableState<GameUiState>, speed: MotionSpeed) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }, LocalMotion provides Motion(speed)) {
                CoupTheme { GameScreen(s.value, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}, effects = effects) }
            }
        }
        frames(48)
    }

    private fun frames(millis: Long) = compose.mainClock.advanceTimeBy(millis)

    private fun update(s: MutableState<GameUiState>, next: GameUiState) {
        compose.runOnUiThread {
            s.value = next
            Snapshot.sendApplyNotifications()
        }
    }

    /** 세션이 하는 그대로: 상태를 먼저 알리고, 그 상태를 만든 사건(연출 신호)을 뒤이어 보낸다. */
    private fun challengeLastCard(s: MutableState<GameUiState>, next: GameUiState = after()) {
        update(s, next)
        listOf(
            UiEffect.Challenged(me, a2, duke), UiEffect.CardRevealed(a2, RoleId("contessa"), false),
            UiEffect.InfluenceLost(a2, RoleId("contessa")), UiEffect.Eliminated(a2),
        ).forEach { assertTrue(effects.tryEmit(it)) }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun moment(): String? = compose.onAllNodesWithTag(GameTags.MOMENT).fetchSemanticsNodes().firstOrNull()?.config?.get(MomentKind)
    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
    private fun texts(state: GameUiState) = GameTexts(ApplicationProvider.getApplicationContext<Context>().resources, state)
    private fun logShows(entry: LogEntry, state: GameUiState) = compose.onAllNodesWithText(texts(state).log(entry)!!).fetchSemanticsNodes().isNotEmpty()

    // ---- 1. 결과가 공개보다 먼저 보이지 않는다 ----

    @Test
    fun `남은 카드 1장에 도전하면 공개가 끝난 뒤에야 탈락 도장이 찍힌다`() {
        val s = mutableStateOf(before())
        show(s, MotionSpeed.NORMAL)
        challengeLastCard(s)
        frames(200)
        assertEquals("challenge", moment())
        assertFalse("도전 중에는 아직 탈락이 아니다", exists(GameTags.stamp("a2")))
        assertTrue("마지막 카드는 아직 가려져 있다", exists(GameTags.hidden("a2", 0)))
        assertFalse("기록도 아직 탈락을 말하지 않는다", logShows(eliminated, after()))

        frames(1_150)
        assertEquals("bluff", moment())
        assertFalse("카드를 뒤집는 동안에도 아직이다", exists(GameTags.stamp("a2")))

        frames(2_000)
        assertEquals("eliminated", moment())
        assertTrue("띠의 탈락과 함께 자리에 도장이 찍힌다", exists(GameTags.stamp("a2")))
        assertFalse(exists(GameTags.hidden("a2", 0)))

        frames(1_700)
        assertEquals(null, moment())
        assertTrue("다 보인 뒤에 기록이 따라온다", logShows(eliminated, after()))
    }

    @Test
    fun `연출이 꺼져 있으면 판은 기다리지 않는다`() {
        val s = mutableStateOf(before())
        show(s, MotionSpeed.OFF)
        challengeLastCard(s)
        frames(48)
        assertTrue(exists(GameTags.stamp("a2")))
        assertTrue(logShows(eliminated, after()))
    }

    @Test
    fun `판이 기다리는 동안에도 내 결정과 코인은 바로 보인다`() {
        val income = ActionButtonUi(ActionId("income"), 0, true, null, false, emptySet(), false, emptyList(), false)
        val s = mutableStateOf(before())
        show(s, MotionSpeed.NORMAL)
        challengeLastCard(s, after(decision = DecisionUi.Actions(listOf(income)), myCoins = 6))
        frames(200)
        assertFalse(exists(GameTags.stamp("a2")))
        compose.onNodeWithTag(GameTags.action("income")).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.MY_COINS).assertContentDescriptionEquals(string(R.string.game_coins, 6))
    }

    @Test
    fun `그냥 카드를 잃은 것은 신호를 잠깐 기다린 뒤 바로 보인다`() {
        val s = mutableStateOf(before())
        show(s, MotionSpeed.NORMAL)
        update(s, after())
        frames(BoardPacing.GRACE_MS + 48)
        assertTrue(exists(GameTags.stamp("a2")))
    }

    // ---- 2. 누가 누구에게 ----

    private fun arrows(): List<String> = compose.onNodeWithTag(GameTags.ARROWS).fetchSemanticsNode().config[ArrowsShown]

    @Test
    fun `대상이 있는 행동은 행동한 자리에서 대상으로 화살표가 이어진다`() {
        val steal = before(turn = a1).copy(
            banner = Banner.ActionDeclared(a1, ActionId("steal"), me, setOf(RoleId("captain")), setOf(me, a2)),
            opponents = before().opponents.map { if (it.id == a1) it.copy(claiming = setOf(RoleId("captain"))) else it.copy(claiming = emptySet()) },
        )
        show(mutableStateOf(steal), MotionSpeed.OFF)
        assertEquals(listOf("a1>me:attack"), arrows())
    }

    @Test
    fun `대상이 없는 행동에는 화살표가 없다`() {
        show(mutableStateOf(before()), MotionSpeed.OFF)
        assertEquals(emptyList<String>(), arrows())
    }

    @Test
    fun `역할을 주장하는 사람은 그 카드를 내밀고 있다`() {
        show(mutableStateOf(before()), MotionSpeed.OFF)
        compose.onNodeWithTag(GameTags.claim("a2")).assertIsDisplayed()
        assertFalse(exists(GameTags.claim("a1")))
    }

    // ---- 3. 누구의 차례 ----

    @Test
    fun `차례인 자리에 차례 탭이 붙고 표시줄에 누구의 차례인지 나온다`() {
        show(mutableStateOf(before(turn = a2)), MotionSpeed.OFF)
        compose.onNodeWithTag(GameTags.turn("a2")).assertIsDisplayed()
        assertFalse(exists(GameTags.turn("a1")))
        assertFalse(exists(GameTags.MY_TURN))
        compose.onNodeWithTag(GameTags.TURN_CHIP).assertTextContains(string(R.string.game_waiting_for, "AI 2"))
    }

    @Test
    fun `내 차례면 내 자리에 내 차례 표시가 붙는다`() {
        show(mutableStateOf(before(turn = me)), MotionSpeed.OFF)
        compose.onNodeWithTag(GameTags.MY_TURN).assertIsDisplayed()
        compose.onNodeWithTag(GameTags.TURN_CHIP).assertTextContains(string(R.string.game_turn_mine))
        assertTrue(compose.onAllNodesWithTag(GameTags.turn("a1")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `판이 끝나면 차례 표시가 없다`() {
        show(mutableStateOf(before(turn = null)), MotionSpeed.OFF)
        assertFalse(exists(GameTags.TURN_CHIP))
        assertFalse(exists(GameTags.turn("a2")))
        assertFalse(exists(GameTags.MY_TURN))
    }
}
