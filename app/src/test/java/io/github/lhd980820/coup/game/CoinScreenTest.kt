package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.game.sound.SoundCue
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.UiEffect
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 코인이 날아가는 화면(docs/DESIGN.md "코인이 날아간다"): 언제 날고, 숫자가 언제 오르고, 극적인 순간 뒤에 나는지, 연출을 끄면 없는지.
 * 시계를 멈추고 정해진 시각으로 넘기며 본다(보통 속도: 코인 하나가 0.56초에 닿고, 다음 코인은 0.11초 뒤에 떠난다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class CoinScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val now = 1_000_000L
    private val effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 64)
    private val sounds = mutableListOf<SoundCue>()

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

    private fun state(myCoins: Int = 4, a1Coins: Int = 2) = GameUiState(
        gameId = "g", turnNumber = 3, banner = Banner.WaitingFor(a1),
        opponents = listOf(
            OpponentUi(a1, "AI 1", a1Coins, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
        ),
        me = MyUi(me, "나", myCoins, listOf(CardUi(CardId(1), RoleId("duke"), false), CardUi(CardId(2), RoleId("contessa"), false)), true, OpponentStatus.IDLE),
        deckSize = 9, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
        turnPlayer = a1,
    )

    private fun show(s: MutableState<GameUiState>, speed: MotionSpeed = MotionSpeed.NORMAL) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalNowMillis provides { now }, LocalMotion provides Motion(speed), LocalSounds provides { sounds += it }) {
                CoupTheme { GameScreen(s.value, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}, effects = effects) }
            }
        }
        frames(48)
    }

    private fun frames(millis: Long) = compose.mainClock.advanceTimeBy(millis)

    /** 세션이 하는 그대로: 상태를 먼저 알리고, 그 상태를 만든 사건(연출 신호)을 뒤이어 보낸다. */
    private fun happen(s: MutableState<GameUiState>, next: GameUiState, vararg signals: UiEffect) {
        compose.runOnUiThread {
            s.value = next
            Snapshot.sendApplyNotifications()
        }
        signals.forEach { assertTrue(effects.tryEmit(it)) }
    }

    private fun shown(tag: String): Int = compose.onNodeWithTag(tag).fetchSemanticsNode().config[ShownCoins]
    private fun spoken(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
    private fun flying(): List<String> = compose.onAllNodesWithTag(GameTags.COINS).fetchSemanticsNodes().firstOrNull()?.config?.getOrElse(CoinsFlying) { emptyList() }.orEmpty()
    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    @Test
    fun `세금 3닢은 은행에서 날아와 닿을 때마다 숫자가 하나씩 오른다`() {
        val s = mutableStateOf(state(myCoins = 4))
        show(s)
        happen(s, state(myCoins = 7), UiEffect.CoinsChanged(me, 3))
        frames(48)
        assertEquals(listOf("bank>me:3"), flying())
        assertEquals("날아오는 동안은 아직 4다", 4, shown(GameTags.MY_COINS))
        assertEquals("스크린리더에는 언제나 지금 값", string(R.string.game_coins, 7), spoken(GameTags.MY_COINS))
        frames(560)
        assertEquals("첫 코인이 닿았다", 5, shown(GameTags.MY_COINS))
        frames(300)
        assertEquals(7, shown(GameTags.MY_COINS))
        frames(1_000)
        assertEquals(emptyList<String>(), flying())
        assertEquals(7, shown(GameTags.MY_COINS))
    }

    @Test
    fun `갈취한 코인은 내 자리에서 상대 자리로 날아간다`() {
        val s = mutableStateOf(state(myCoins = 4, a1Coins = 2))
        show(s)
        happen(s, state(myCoins = 2, a1Coins = 4), UiEffect.CoinsChanged(me, -2), UiEffect.CoinsChanged(a1, 2))
        frames(48)
        assertEquals(listOf("me>a1:2"), flying())
        assertEquals("두 번째 코인은 아직 내 자리에 있다", 3, shown(GameTags.MY_COINS))
        assertEquals("아직 닿지 않았다", 2, shown(GameTags.coins("a1")))
        frames(1_500)
        assertEquals(2, shown(GameTags.MY_COINS))
        assertEquals(4, shown(GameTags.coins("a1")))
    }

    @Test
    fun `쿠에 낸 코인은 자리에서 은행으로 간다`() {
        val s = mutableStateOf(state(a1Coins = 9))
        show(s)
        happen(s, state(a1Coins = 2), UiEffect.CoinsChanged(a1, -7))
        frames(32)
        assertEquals("짝이 될 얻음을 잠깐 기다린다", emptyList<String>(), flying())
        assertEquals(9, shown(GameTags.coins("a1")))
        frames(80)
        assertEquals(listOf("a1>bank:7"), flying())
        assertTrue("코인이 떠나며 줄어든다", shown(GameTags.coins("a1")) < 9)
        frames(1_500)
        assertEquals(2, shown(GameTags.coins("a1")))
    }

    @Test
    fun `극적인 순간이 그 일을 말하는 동안에는 코인이 날지 않고 숫자도 그대로다`() {
        val s = mutableStateOf(state(myCoins = 4, a1Coins = 2))
        show(s)
        // 도전이 실패해 갈취가 그대로 일어났다: 띠("도전!", "증명!")가 먼저, 코인은 그 뒤에
        happen(
            s, state(myCoins = 2, a1Coins = 4),
            UiEffect.Challenged(me, a1, setOf(RoleId("captain"))), UiEffect.CardRevealed(a1, RoleId("captain"), proven = true),
            UiEffect.CoinsChanged(me, -2), UiEffect.CoinsChanged(a1, 2),
        )
        frames(600)
        assertEquals(emptyList<String>(), flying())
        assertEquals(4, shown(GameTags.MY_COINS))
        assertEquals(2, shown(GameTags.coins("a1")))
        // 도전(1.15초) + 증명(2초)이 지나면 난다
        frames(2_700)
        assertEquals(listOf("me>a1:2"), flying())
        frames(1_500)
        assertEquals(2, shown(GameTags.MY_COINS))
        assertEquals(4, shown(GameTags.coins("a1")))
    }

    @Test
    fun `코인 소리는 코인이 닿을 때 한 번 난다`() {
        val s = mutableStateOf(state(myCoins = 4))
        show(s)
        happen(s, state(myCoins = 7), UiEffect.CoinsChanged(me, 3))
        frames(200)
        assertTrue("아직 닿지 않았다: $sounds", SoundCue.COIN_GAIN !in sounds)
        frames(500)
        assertEquals(1, sounds.count { it == SoundCue.COIN_GAIN })
        frames(1_000)
        assertEquals(1, sounds.count { it == SoundCue.COIN_GAIN })
    }

    @Test
    fun `연출이 꺼져 있으면 코인이 날지 않고 숫자가 바로 바뀌며 소리도 바로 난다`() {
        val s = mutableStateOf(state(myCoins = 4))
        show(s, MotionSpeed.OFF)
        happen(s, state(myCoins = 7), UiEffect.CoinsChanged(me, 3))
        frames(48)
        assertEquals(emptyList<String>(), flying())
        assertEquals(7, shown(GameTags.MY_COINS))
        assertTrue(SoundCue.COIN_GAIN in sounds)
    }

    @Test
    fun `판이 끝나 결과가 뜨면 아직 날던 코인은 그만 날고 숫자는 끝난 값이다`() {
        val s = mutableStateOf(state(myCoins = 4))
        show(s)
        // 마지막 세금으로 이겼다: 결과와 코인 신호가 함께 온다(극적인 순간이 없으니 결과는 곧 뜬다)
        happen(s, state(myCoins = 7).copy(result = io.github.lhd980820.coup.presentation.ResultUi(me, listOf(me, a1, a2), iWon = true, myRank = 1)), UiEffect.CoinsChanged(me, 3))
        frames(48)
        assertEquals("아직 날아오는 중", 4, shown(GameTags.MY_COINS))
        frames(300)
        assertTrue(compose.onAllNodesWithTag(GameTags.RESULT).fetchSemanticsNodes().isNotEmpty())
        assertEquals("결과가 뜨면 바로 끝난 값", 7, shown(GameTags.MY_COINS))
    }
}
