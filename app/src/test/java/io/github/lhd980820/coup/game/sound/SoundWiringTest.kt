package io.github.lhd980820.coup.game.sound

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.lhd980820.coup.game.SetupTags
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.game.AppFlow
import io.github.lhd980820.coup.game.AppSettings
import io.github.lhd980820.coup.game.GameActions
import io.github.lhd980820.coup.game.GameScreen
import io.github.lhd980820.coup.game.GameViewModel
import io.github.lhd980820.coup.game.InMemorySettingsStore
import io.github.lhd980820.coup.game.LocalSounds
import io.github.lhd980820.coup.game.PrefsSettingsStore
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.game.soundSink
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

/** 소리 연결: 화면이 상태 변화에서만 소리를 요청하고, 설정(꺼짐·작게·보통)에 맞춰 내며, 설정 화면에서 바꾼 값이 저장된다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class SoundWiringTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingPlayer : SoundPlayer {
        val played: MutableList<Pair<SoundCue, Float>> = java.util.Collections.synchronizedList(mutableListOf())
        override fun play(cue: SoundCue, volume: Float) {
            played += cue to volume
        }
    }

    // ---- 소리 내는 함수 ----

    @Test
    fun `보통과 작게는 설정한 크기로 내고, 끄면 아무 소리도 내지 않는다`() {
        val player = RecordingPlayer()
        soundSink(player, SoundLevel.NORMAL)(SoundCue.WIN)
        soundSink(player, SoundLevel.LOW)(SoundCue.LOSE)
        soundSink(player, SoundLevel.OFF)(SoundCue.COIN_GAIN)
        soundSink(null, SoundLevel.NORMAL)(SoundCue.COIN_GAIN)
        assertEquals(listOf(SoundCue.WIN to SoundLevel.NORMAL.volume, SoundCue.LOSE to SoundLevel.LOW.volume), player.played)
    }

    // ---- 게임 화면 ----

    private val me = PlayerId("me")
    private val a = PlayerId("a")

    private fun state(coins: Int = 2, decision: DecisionUi? = null) = GameUiState(
        gameId = "g", turnNumber = 1, banner = Banner.WaitingFor(a),
        opponents = listOf(OpponentUi(a, "AI", 2, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false)),
        me = MyUi(me, "나", coins, emptyList(), true, OpponentStatus.IDLE),
        deckSize = 9, decision = decision, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
    )

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

    private fun showGame(state: androidx.compose.runtime.State<GameUiState>, played: MutableList<SoundCue>) {
        compose.setContent {
            CompositionLocalProvider(LocalSounds provides { played += it }) {
                CoupTheme { GameScreen(state.value, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}) }
            }
        }
    }

    private fun update(s: androidx.compose.runtime.MutableState<GameUiState>, next: GameUiState) {
        compose.runOnUiThread { s.value = next }
        compose.waitForIdle()
    }

    @Test
    fun `화면을 처음 그릴 때는 조용하고, 상태가 바뀔 때만 소리를 요청한다`() {
        val played = mutableListOf<SoundCue>()
        val s = mutableStateOf(state())
        showGame(s, played)
        compose.waitForIdle()
        assertEquals(emptyList<SoundCue>(), played)

        update(s, state(coins = 3))
        assertEquals(listOf(SoundCue.COIN_GAIN), played)

        // 같은 상태를 다시 그려도 반복하지 않는다
        update(s, state(coins = 3))
        assertEquals(listOf(SoundCue.COIN_GAIN), played)

        update(s, state(coins = 3, decision = DecisionUi.Actions(emptyList())))
        assertEquals(listOf(SoundCue.COIN_GAIN, SoundCue.YOUR_TURN), played)
    }

    @Test
    fun `앱 흐름 - 게임이 진행되면 설정한 크기로 효과음이 요청된다`() {
        val player = RecordingPlayer()
        val settings = AppSettings(InMemorySettingsStore(soundLevel = SoundLevel.LOW))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { AppFlow(vm, onExit = {}, settings = settings, sounds = player) } }
        try {
            compose.onNodeWithTag(ShellTags.HOME_SINGLE).performClick()
            compose.onNodeWithTag(SetupTags.START).performScrollTo().performClick()
            // AI가 두거나 내 차례가 오면 코인·차례 소리가 요청된다
            compose.waitUntil(30_000) { synchronized(player.played) { player.played.isNotEmpty() } }
            synchronized(player.played) { assertTrue(player.played.all { it.second == SoundLevel.LOW.volume }) }
        } finally {
            scope.cancel()
        }
    }

    // ---- 설정 ----

    @Test
    fun `소리 설정은 기본이 보통이고 기기에 저장된다`() {
        val store = PrefsSettingsStore(context)
        assertEquals(SoundLevel.NORMAL, store.sound())
        store.setSound(SoundLevel.LOW)
        assertEquals(SoundLevel.LOW, PrefsSettingsStore(context).sound())
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("sound", "LOUD").commit()
        assertEquals(SoundLevel.NORMAL, PrefsSettingsStore(context).sound())
        store.setSound(SoundLevel.NORMAL)
    }

    @Test
    fun `앱 설정에서 소리 크기를 고르면 화면이 보는 값과 저장소가 함께 바뀐다`() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        assertEquals(SoundLevel.NORMAL, settings.sound)
        settings.chooseSound(SoundLevel.OFF)
        assertEquals(SoundLevel.OFF, settings.sound)
        assertEquals(SoundLevel.OFF, store.sound())
    }

    @Test
    fun `설정 화면에서 효과음 크기를 고르면 바로 적용되고 저장된다`() {
        val store = InMemorySettingsStore()
        val settings = AppSettings(store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        compose.setContent { CoupTheme { AppFlow(vm, onExit = {}, settings = settings) } }
        try {
            compose.onNodeWithTag(ShellTags.HOME_SETTINGS).performClick()
            compose.onNodeWithTag(ShellTags.sound(SoundLevel.NORMAL)).assertIsSelected()
            compose.onNodeWithTag(ShellTags.sound(SoundLevel.LOW)).performClick()
            compose.onNodeWithTag(ShellTags.sound(SoundLevel.LOW)).assertIsSelected()
            assertEquals(SoundLevel.LOW, settings.sound)
            assertEquals(SoundLevel.LOW, store.sound())
            compose.onNodeWithTag(ShellTags.sound(SoundLevel.OFF)).performClick()
            assertEquals(SoundLevel.OFF, store.sound())
        } finally {
            scope.cancel()
        }
    }
}
