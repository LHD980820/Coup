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
import io.github.lhd980820.coup.R
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

    /** 방장이 사라진 뒤 서버 정리가 그 판을 버려진 판으로 끝낸다(시계를 11분 뒤로, ADR 0016). */
    private fun serverAbandons(gameId: String) = runBlocking {
        io.github.lhd980820.coup.judge.GameSweeper(fx.fs.admin(), clock = io.github.lhd980820.coup.runtime.Clock { System.currentTimeMillis() + 11 * 60_000L })
            .sweepGame(gameId)
    }

    @Test
    fun `버려진 판 - 서버가 판을 끝내면 안내를 띄우고 로비로 보내며, 다시 켜도 그 판으로 돌아가지 않는다`() {
        val (host, _) = guestInGame()
        val gameId = fx.shell.multiGame!!.gameId
        host.device.cancel() // 방장 앱이 꺼졌고 돌아오지 않는다
        assertTrue(serverAbandons(gameId) is io.github.lhd980820.coup.judge.GameSweep.Abandoned)

        waitFor(GameTags.ABANDONED, timeoutMillis = 20_000)
        assertShown(string(R.string.mp_abandoned_body))
        assertTrue("더 둘 수 없으므로 기권 버튼이 없다", !exists(GameTags.CONCEDE))
        assertTrue("배너 대신 안내 대화상자", !exists(GameTags.CONNECTION))

        click(GameTags.ABANDONED_LEAVE)
        waitFor(LobbyTags.NOTICE)
        assertText(LobbyTags.NOTICE, string(R.string.mp_notice_abandoned))
        assertEquals(null, fx.activeRooms.get("kim"))
        assertEquals(null, fx.shell.multiGame)

        restartApp()
        waitFor(ShellTags.HOME_MULTI)
        click(ShellTags.HOME_MULTI)
        waitFor(LobbyTags.CREATE)
        assertTrue("버려진 판으로 다시 들어가지 않는다", !exists(GameTags.ABANDONED) && fx.shell.multiGame == null)
    }

    @Test
    fun `버려진 판 - 방장이 오래 끊겼다 돌아오면 판이 끝났음과 패배를 알리고 로비로 보낸다`() {
        signInAndOpenLobbySwappable()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
        val roomId = fx.shell.room!!.roomId
        // 사람 게스트가 들어와 준비해야 레이팅 판이다
        val guest = fx.remote("guest@test.co", "손님")
        runBlocking {
            guest.signUp()
            guest.rooms.join(roomId, guest.lobbyUser)
            guest.rooms.setReady(roomId, guest.uid, true)
        }
        until { fx.shell.room?.state?.value?.canStart == true }
        click(WaitingTags.START)
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000)
        val gameId = fx.shell.multiGame!!.gameId

        // 방장 기기가 10분 넘게 끊겨 있는 동안 서버가 판을 끝냈다(방장 앱은 그동안 가만히 있다)
        assertTrue(serverAbandons(gameId) is io.github.lhd980820.coup.judge.GameSweep.Abandoned)
        waitFor(GameTags.ABANDONED, timeoutMillis = 20_000)
        assertShown(string(R.string.mp_abandoned_body_host))
        assertTrue("더 둘 수 없으므로 기권 버튼이 없다", !exists(GameTags.CONCEDE))
        // 방장은 꼴찌(2인 판 -20)로 처리됐고 그 레이팅이 안내에 보인다
        until(20_000) { fx.shell.settlement != null && fx.shell.settlement != io.github.lhd980820.coup.remote.Settlement.Verifying }
        assertShown(string(R.string.mp_result_rating, 980, -20))
        assertEquals(980, fx.shell.profile?.rating)

        click(GameTags.ABANDONED_LEAVE)
        waitFor(LobbyTags.NOTICE)
        assertText(LobbyTags.NOTICE, string(R.string.mp_notice_abandoned_host))
        assertEquals(null, fx.activeRooms.get("kim"))
    }

    @Test
    fun `버려진 판 - 뒤로 가기는 기권을 묻지 않고 로비로 나간다`() {
        val (host, _) = guestInGame()
        val gameId = fx.shell.multiGame!!.gameId
        host.device.cancel()
        serverAbandons(gameId)
        waitFor(GameTags.ABANDONED, timeoutMillis = 20_000)
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            (compose as AndroidComposeTestRule<*, androidx.activity.ComponentActivity>).activity.onBackPressedDispatcher.onBackPressed()
        }
        waitFor(LobbyTags.NOTICE)
        assertTrue(!exists(GameTags.CONCEDE_CONFIRM))
    }

    @Test
    fun `방장이 돌아오지 않으면 게스트가 방장을 이어받아 그대로 이어 두고, 판이 끝나면 방의 방장이 된다`() {
        val (host, roomId) = guestInGame()
        val gameId = fx.shell.multiGame!!.gameId
        host.device.cancel() // 방장 앱이 꺼졌고 돌아오지 않는다
        waitFor(GameTags.CONNECTION_LEAVE, timeoutMillis = 20_000)
        assertShown(string(R.string.mp_connection_host_lost))
        fx.clockOffset += 61_000 // 방장이 60초 넘게 조용하다(서버 시각·기기 시계 함께)

        // 화면은 그대로인 채 내가(kim) 방장을 이어받는다: 끊김 배너가 사라지고 계속 둘 수 있다
        until(20_000) { gameDoc(gameId)?.get("hostUid")?.toString() == "\"kim\"" }
        compose.waitUntil(20_000) { !exists(GameTags.CONNECTION) }
        assertTrue(exists(GameTags.CONCEDE))
        assertEquals(gameId, fx.shell.multiGame?.gameId)

        // 기권으로 판을 끝내고 대기실로 돌아오면 이제 내가 방의 방장이다
        click(GameTags.CONCEDE)
        waitFor(GameTags.CONCEDE_CONFIRM)
        click(GameTags.CONCEDE_CONFIRM)
        waitFor(GameTags.RESULT, timeoutMillis = 20_000)
        click(GameTags.RESULT_AGAIN)
        waitFor(WaitingTags.START, timeoutMillis = 20_000)
        assertEquals(roomId, fx.shell.room?.roomId)
        assertTrue(fx.shell.room?.state?.value?.isHost == true)
    }

    private fun gameDoc(gameId: String) = fx.fs.docs[io.github.lhd980820.coup.remote.FirestoreSchema.game(gameId)]

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
