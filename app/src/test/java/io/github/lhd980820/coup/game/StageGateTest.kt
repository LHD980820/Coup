package io.github.lhd980820.coup.game

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.UiEffect
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 화면이 극적인 순간을 보여 주는 동안 AI가 다음 수를 기다린다: 화면은 바쁜지를 알리고, 뷰모델은 그동안 AI를 붙잡는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class StageGateTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 16)

    private val state = GameUiState(
        gameId = "g", turnNumber = 2, banner = Banner.WaitingFor(a1),
        opponents = listOf(OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.ACTING, emptySet(), null, false)),
        me = MyUi(me, "나", 2, emptyList(), true, OpponentStatus.IDLE),
        deckSize = 9, decision = null, myDeadline = null, connection = ConnectionState.CONNECTED, result = null, busy = false, log = emptyList(),
        turnPlayer = a1,
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

    @Test
    fun `순간을 보여 주는 동안 바쁘다고 알리고 끝나면 한가하다고 알린다`() {
        val reports = mutableListOf<Boolean>()
        val showing = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(MotionSpeed.NORMAL)) {
                CoupTheme { if (showing.value) GameScreen(state, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}, effects = effects, onStageBusy = { reports += it }) }
            }
        }
        compose.mainClock.advanceTimeBy(48)
        assertEquals("처음에는 한가하다", listOf(false), reports)
        assertTrue(effects.tryEmit(UiEffect.Challenged(me, a1, setOf(RoleId("duke")))))
        compose.mainClock.advanceTimeBy(200)
        assertEquals(listOf(false, true), reports)
        compose.mainClock.advanceTimeBy(1_300)
        assertEquals("순간이 끝나면 한가하다", listOf(false, true, false), reports)
        // 순간을 보여 주는 중에 화면이 사라지면 한가하다고 알린다(AI가 붙잡힌 채로 남지 않게)
        assertTrue(effects.tryEmit(UiEffect.Challenged(me, a1, setOf(RoleId("duke")))))
        compose.mainClock.advanceTimeBy(200)
        assertEquals(true, reports.last())
        compose.runOnUiThread { showing.value = false }
        compose.mainClock.advanceTimeBy(48)
        assertEquals(false, reports.last())
    }

    @Test
    fun `연출이 꺼져 있으면 바쁘다고 알리지 않는다`() {
        val reports = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CoupTheme { GameScreen(state, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}, effects = effects, onStageBusy = { reports += it }) }
        }
        compose.mainClock.advanceTimeBy(48)
        assertTrue(effects.tryEmit(UiEffect.Challenged(me, a1, setOf(RoleId("duke")))))
        compose.mainClock.advanceTimeBy(300)
        assertFalse(true in reports)
    }

    @Test
    fun `뷰모델은 바쁜 동안 AI를 붙잡고, 한가해지면 숨을 고른 뒤 놓아준다`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = GameViewModel(scope = scope)
        try {
            // 한가하면 바로 돌아온다
            withTimeout(200) { vm.awaitStage() }
            vm.setStageBusy(true)
            val waiting = scope.async { vm.awaitStage() }
            kotlinx.coroutines.delay(300)
            assertFalse("바쁜 동안에는 기다린다", waiting.isCompleted)
            val released = System.currentTimeMillis()
            vm.setStageBusy(false)
            withTimeout(2_000) { waiting.await() }
            assertTrue("한가해진 뒤 숨을 고른다", System.currentTimeMillis() - released >= 300)
        } finally {
            scope.cancel()
        }
    }
}
