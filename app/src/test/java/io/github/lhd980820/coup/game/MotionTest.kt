package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 애니메이션 묶음(Phase 6): 속도 설정과 저장, 설정 화면, 카드 뒤집기·코인·응답 시트 연출. 연출은 시계를 직접 돌려 도중 상태를 확인한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val ai = PlayerId("ai")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    // ---- 속도 규칙과 저장 ----

    @Test
    fun `기기의 애니메이션 없음이 켜져 있으면 고른 속도와 상관없이 꺼진다`() {
        assertEquals(MotionSpeed.OFF, effectiveSpeed(MotionSpeed.NORMAL, 0f))
        assertEquals(MotionSpeed.OFF, effectiveSpeed(MotionSpeed.FAST, 0f))
        assertEquals(MotionSpeed.NORMAL, effectiveSpeed(MotionSpeed.NORMAL, 1f))
        assertEquals(MotionSpeed.FAST, effectiveSpeed(MotionSpeed.FAST, 0.5f))
    }

    @Test
    fun `화면에 주는 연출은 설정과 기기 설정을 함께 본다`() {
        assertEquals(MotionSpeed.OFF, motionFor(null, 1f).speed)
        assertEquals(MotionSpeed.NORMAL, motionFor(AppSettings(InMemorySettingsStore(MotionSpeed.NORMAL)), 1f).speed)
        assertEquals(MotionSpeed.FAST, motionFor(AppSettings(InMemorySettingsStore(MotionSpeed.FAST)), 1f).speed)
        assertEquals(MotionSpeed.OFF, motionFor(AppSettings(InMemorySettingsStore(MotionSpeed.NORMAL)), 0f).speed)
    }

    @Test
    fun `속도는 기본 길이에 배율을 곱한다`() {
        assertEquals(400, Motion(MotionSpeed.NORMAL).ms(400))
        assertEquals(200, Motion(MotionSpeed.FAST).ms(400))
        assertEquals(0, Motion(MotionSpeed.OFF).ms(400))
        assertTrue(!Motion(MotionSpeed.OFF).enabled && Motion(MotionSpeed.FAST).enabled)
    }

    @Test
    fun `설정은 기기에 저장되고, 없거나 알 수 없는 값이면 보통이다`() {
        val a = PrefsSettingsStore(context)
        assertEquals(MotionSpeed.NORMAL, a.motion())
        a.setMotion(MotionSpeed.FAST)
        assertEquals(MotionSpeed.FAST, PrefsSettingsStore(context).motion())
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("motion", "WARP").commit()
        assertEquals(MotionSpeed.NORMAL, PrefsSettingsStore(context).motion())
        a.setMotion(MotionSpeed.NORMAL)
    }

    @Test
    fun `앱 설정을 바꾸면 화면이 보는 값과 저장소가 함께 바뀐다`() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        settings.chooseMotion(MotionSpeed.OFF)
        assertEquals(MotionSpeed.OFF, settings.motion)
        assertEquals(MotionSpeed.OFF, store.motion())
    }

    // ---- 설정 화면 ----

    private fun withFlow(settings: AppSettings?, block: () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { AppFlow(vm, onExit = {}, settings = settings) } }
        try {
            block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `홈에서 설정을 열어 속도를 고르면 바로 적용되고 저장된다`() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        withFlow(settings) {
            compose.onNodeWithTag(ShellTags.HOME_SETTINGS).performClick()
            compose.onNodeWithTag(ShellTags.motion(MotionSpeed.NORMAL)).assertIsSelected()
            compose.onNodeWithTag(ShellTags.motion(MotionSpeed.FAST)).performClick()
            compose.onNodeWithTag(ShellTags.motion(MotionSpeed.FAST)).assertIsSelected()
            assertEquals(MotionSpeed.FAST, settings.motion)
            assertEquals(MotionSpeed.FAST, store.motion())
            compose.onNodeWithTag(ShellTags.SETTINGS_BACK).performClick()
            compose.onNodeWithTag(ShellTags.HOME_SINGLE).assertIsDisplayed()
        }
    }

    @Test
    fun `설정이 없는 구성에서는 설정 버튼이 없다`() {
        withFlow(null) {
            compose.onNodeWithTag(ShellTags.HOME_SINGLE).assertIsDisplayed()
            compose.onAllNodesWithTag(ShellTags.HOME_SETTINGS).assertCountEquals(0)
        }
    }

    // ---- 게임 화면 연출 ----

    private fun state(
        revealed: List<String> = emptyList(),
        hidden: Int = 2,
        oppCoins: Int = 2,
        decision: DecisionUi? = null,
    ) = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.YourTurn(false),
        opponents = listOf(OpponentUi(ai, "AI 1", oppCoins, hidden, revealed.map(::RoleId), true, OpponentStatus.IDLE, emptySet(), null, false)),
        me = MyUi(PlayerId("me"), "나", 7, emptyList(), true, OpponentStatus.IDLE),
        deckSize = 11, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
    )

    private val actions = object : GameActions {
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

    private fun show(state: androidx.compose.runtime.State<GameUiState>, speed: MotionSpeed) {
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(speed)) {
                CoupTheme { GameScreen(state.value, emptyFlow(), actions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    /** 시계를 멈춘 채 상태를 바꾼다: 메인 스레드에서 쓰고 곧바로 알려야 다음 프레임에 구성된다. */
    private fun update(s: androidx.compose.runtime.MutableState<GameUiState>, next: GameUiState) {
        compose.runOnUiThread {
            s.value = next
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
    }

    private fun flip(i: Int) = compose.onNodeWithTag(GameTags.flip("ai", i)).fetchSemanticsNode().config[FlipProgress]

    @Test
    fun `상대 카드가 공개되면 뒷면에서 앞면으로 뒤집히며 나타난다`() {
        val s = mutableStateOf(state())
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.NORMAL)
        update(s, state(revealed = listOf("duke"), hidden = 1))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        assertTrue("처음에는 뒷면 쪽: ${flip(0)}", flip(0) < 0.2f)
        compose.mainClock.advanceTimeBy(150) // 500ms 연출의 앞쪽(이징 때문에 선형이 아니다)
        assertTrue("도중에는 0과 1 사이: ${flip(0)}", flip(0) in 0.05f..0.95f)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1f, flip(0), 0f)
    }

    @Test
    fun `빠른 속도는 같은 연출을 절반 시간에 끝낸다`() {
        val s = mutableStateOf(state())
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.FAST)
        update(s, state(revealed = listOf("duke"), hidden = 1))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        compose.mainClock.advanceTimeBy(300) // 보통이면 아직 도중인 시간
        assertEquals(1f, flip(0), 0f)
    }

    @Test
    fun `처음 그릴 때 이미 공개돼 있던 카드는 뒤집지 않는다`() {
        val s = mutableStateOf(state(revealed = listOf("duke"), hidden = 1))
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.NORMAL)
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        assertEquals(1f, flip(0), 0f)
        // 그 뒤에 공개되는 두 번째 카드만 뒤집힌다
        update(s, state(revealed = listOf("duke", "captain"), hidden = 0))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        assertEquals(1f, flip(0), 0f)
        assertTrue("새로 공개된 카드는 뒷면 쪽에서 시작: ${flip(1)}", flip(1) < 0.2f)
    }

    @Test
    fun `연출이 꺼져 있으면 공개 카드가 바로 앞면이다`() {
        val s = mutableStateOf(state())
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.OFF)
        update(s, state(revealed = listOf("duke"), hidden = 1))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        assertEquals(1f, flip(0), 0f)
    }

    @Test
    fun `뒤집힌 공개 카드는 역할 이름을 스크린리더에 알린다`() {
        val s = mutableStateOf(state(revealed = listOf("duke"), hidden = 1))
        show(s, MotionSpeed.OFF)
        compose.onNodeWithTag(GameTags.flip("ai", 0)).assertContentDescriptionEquals(string(R.string.game_role_duke))
    }

    @Test
    fun `코인이 바뀌는 동안 옛 숫자와 새 숫자가 함께 있다가 새 숫자만 남고, 스크린리더는 처음부터 새 값이다`() {
        val s = mutableStateOf(state(oppCoins = 2))
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.NORMAL)
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        update(s, state(oppCoins = 5))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithText(string(R.string.game_coins, 2), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(string(R.string.game_coins, 5), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(GameTags.coins("ai")).assertContentDescriptionEquals(string(R.string.game_coins, 5))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText(string(R.string.game_coins, 2), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.game_coins, 5), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `연출이 꺼져 있으면 코인은 바로 바뀐다`() {
        val s = mutableStateOf(state(oppCoins = 2))
        compose.mainClock.autoAdvance = false
        show(s, MotionSpeed.OFF)
        update(s, state(oppCoins = 5))
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        compose.onAllNodesWithText(string(R.string.game_coins, 2), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.game_coins, 5), useUnmergedTree = true).assertCountEquals(1)
    }

    private val respond = DecisionUi.Respond(WindowKind.ACTION, ActionId("tax"), ai, null, ai, setOf(RoleId("duke")), true, emptyList())

    @Test
    fun `응답 시트는 아래에서 올라와 자리를 잡는다`() {
        compose.mainClock.autoAdvance = false
        show(mutableStateOf(state(decision = respond)), MotionSpeed.NORMAL)
        compose.mainClock.advanceTimeBy(48)
        val startY = compose.onNodeWithTag(GameTags.RESPONSE_SHEET).fetchSemanticsNode().positionInRoot.y
        compose.mainClock.advanceTimeBy(1_000)
        val endY = compose.onNodeWithTag(GameTags.RESPONSE_SHEET).fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET).assertIsDisplayed()
        assertTrue("올라오는 동안 더 아래에 있었다: $startY > $endY", startY > endY + 20f)
    }

    @Test
    fun `연출이 꺼져 있으면 응답 시트가 바로 보인다`() {
        compose.mainClock.autoAdvance = false
        show(mutableStateOf(state(decision = respond)), MotionSpeed.OFF)
        compose.mainClock.advanceTimeBy(48) // 상태 반영 -> 구성 -> 배치가 끝나도록 몇 프레임
        compose.onNodeWithTag(GameTags.RESPONSE_SHEET).assertIsDisplayed()
    }
}
