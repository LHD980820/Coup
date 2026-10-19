package io.github.lhd980820.coup.multiplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.game.GameTags
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.game.SinglePlayerFlow
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 재접속을 화면에서(ADR 0012). 앱 재시작은 화면 모델·세션을 새것으로 갈아 끼워 흉내 낸다(서버·로그인·기기에 저장한 방은 그대로). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class ReconnectScreenTest : MpScreenTest() {
    private var current by mutableStateOf<MpFixture?>(null)

    private fun showSwappable() {
        current = fx
        compose.setContent {
            val f = current ?: return@setContent
            key(f) { CoupTheme { SinglePlayerFlow(f.game, onExit = {}, shell = f.shell) } }
        }
    }

    /** 앱을 껐다 켠다. 옛 화면 모델의 코루틴은 모두 멈춘다. */
    private fun restartApp() {
        val old = fx
        fx = old.restart()
        old.close()
        compose.runOnIdle { current = fx }
        compose.waitForIdle()
    }

    private fun drive(scope: CoroutineScope, id: String, session: GameSession) {
        val agent = RandomAgent(id.hashCode().toLong())
        scope.launch {
            var acted = -1L
            while (isActive) {
                val snap = session.snapshot.value
                val request = snap?.view?.myDecision
                if (snap != null && request != null && snap.view.version != acted) {
                    acted = snap.view.version
                    if (session.submit(agent.decide(snap.view, request)) !is io.github.lhd980820.coup.runtime.session.SubmitResult.Ok) acted = -1
                }
                delay(10)
            }
        }
    }

    /** 다른 기기의 방장: 방을 구독하고, 게임이 열리면(시작 또는 복구) 그 좌석을 대신 둔다. 열린 세션을 돌려준다. */
    private fun runHost(remote: MpFixture.Remote, model: WaitingRoomModel): java.util.concurrent.atomic.AtomicReference<GameSession?> {
        val opened = java.util.concurrent.atomic.AtomicReference<GameSession?>(null)
        remote.device.launch { model.run() }
        remote.device.launch {
            model.events.collect {
                if (it is WaitingRoomEvent.GameStarted) {
                    opened.set(it.session)
                    drive(remote.device, remote.uid, it.session)
                }
            }
        }
        return opened
    }

    /** UI 사용자(kim)가 원격 방장의 방에 들어가 준비하고, 방장이 시작해 게임 화면까지. */
    private fun guestInGame(): Pair<MpFixture.Remote, String> {
        signInAndOpenLobbySwappable()
        val host = fx.remote("host@test.co", "방장")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("원격 방") }
        val model = host.waitingRoom(room.id)
        runHost(host, model)
        click(LobbyTags.REFRESH)
        waitFor(LobbyTags.join(room.id))
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)
        click(WaitingTags.READY)
        until { model.state.value?.canStart == true }
        host.device.launch { model.start() }
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000)
        return host to room.id
    }

    private fun signInAndOpenLobbySwappable() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        showSwappable()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
    }

    @Test
    fun `게스트 - 게임 중 앱이 꺼졌다가 켜지면 멀티플레이를 열자마자 그 방, 그 게임으로 돌아간다`() {
        val (host, roomId) = guestInGame()
        val gameId = fx.shell.multiGame!!.gameId
        assertEquals(roomId, fx.activeRooms.get("kim"))

        restartApp()
        waitFor(ShellTags.HOME_MULTI)
        click(ShellTags.HOME_MULTI) // 로그인은 남아 있다
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000)
        assertEquals(gameId, fx.shell.multiGame?.gameId)
        host.device.cancel()
    }

    @Test
    fun `방장 연결이 끊기면 알리고 대기실로 나갈 수 있고, 방장이 돌아오면 게임으로 돌아간다`() {
        val (host, roomId) = guestInGame()
        host.device.cancel() // 방장 앱이 꺼졌다
        waitFor(GameTags.CONNECTION_LEAVE, timeoutMillis = 20_000)
        click(GameTags.CONNECTION_LEAVE)
        waitFor(WaitingTags.REJOIN)

        // 방장이 다시 켜서 같은 방으로 돌아온다(마지막 게시 상태에서 복구)
        val back = fx.remote("host@test.co", "방장")
        val resumed = runHost(back, back.waitingRoom(roomId))
        until(20_000) { resumed.get() != null }
        click(WaitingTags.REJOIN)
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000)
        compose.waitUntil(20_000) { !exists(GameTags.CONNECTION) } // 방장 생존 신호가 다시 오면 배너가 사라진다
        back.device.cancel()
    }

    @Test
    fun `방장 - 봇과 하던 게임 중 앱이 꺼졌다가 켜지면 복구되어 이어 간다`() {
        signInAndOpenLobbySwappable()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
        click(WaitingTags.ADD_BOT_EASY)
        until { fx.shell.room?.state?.value?.canStart == true }
        click(WaitingTags.START)
        waitFor(GameTags.CONCEDE)
        val gameId = fx.shell.multiGame!!.gameId

        restartApp()
        waitFor(ShellTags.HOME_MULTI)
        click(ShellTags.HOME_MULTI)
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000)
        assertEquals(gameId, fx.shell.multiGame?.gameId)
        assertTrue(fx.game.controller != null)
    }

    @Test
    fun `게임 중 뒤로 가기는 바로 나가지 않고 기권을 확인한다`() {
        guestInGame()
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            (compose as AndroidComposeTestRule<*, androidx.activity.ComponentActivity>).activity.onBackPressedDispatcher.onBackPressed()
        }
        waitFor(GameTags.CONCEDE_CONFIRM)
        assertTrue(exists(GameTags.CONCEDE)) // 아직 게임 화면이다
    }
}
