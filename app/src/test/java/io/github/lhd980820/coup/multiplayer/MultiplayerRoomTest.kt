package io.github.lhd980820.coup.multiplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.game.GameTags
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class MultiplayerRoomTest : MpScreenTest() {
    private fun refreshLobby() = click(LobbyTags.REFRESH)

    /** 상대 기기의 좌석을 무작위 에이전트가 둔다(자기 시점의 뷰와 결정만 보고). */
    private fun drive(id: String, session: GameSession) {
        val agent = RandomAgent(id.hashCode().toLong())
        fx.scope.launch {
            var acted = -1L
            session.snapshot.collect { snap ->
                val request = snap?.view?.myDecision ?: return@collect
                if (snap.view.version == acted) return@collect
                acted = snap.view.version
                session.submit(agent.decide(snap.view, request))
            }
        }
    }

    /** 상대 방장의 대기실 모델을 돌리고, 게임이 열리면 그 좌석을 대신 둔다. */
    private fun runHost(model: WaitingRoomModel, id: String) {
        fx.scope.launch { model.run() }
        fx.scope.launch {
            model.events.collect { if (it is WaitingRoomEvent.GameStarted) drive(id, it.session) }
        }
    }

    @Test
    fun `방 만들기 - 제목, 비공개, 인원을 정하면 대기실이 열리고 참가 코드가 보인다`() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.TITLE)
        compose.onNodeWithText(string(R.string.mp_create_default_name, "kim")).assertIsDisplayed()
        compose.onNodeWithTag(CreateTags.TITLE).performTextReplacement("우리 방")
        click(CreateTags.PRIVATE)
        click(CreateTags.max(3))
        click(CreateTags.SUBMIT)

        waitFor(WaitingTags.START)
        compose.onNodeWithText("우리 방").assertIsDisplayed()
        compose.onNodeWithTag(WaitingTags.seat("kim")).assertIsDisplayed()
        assertTrue(exists(WaitingTags.CODE))
        assertEquals(2, compose.onAllNodesWithTag(WaitingTags.EMPTY_SEAT).fetchSemanticsNodes().size) // 3인 방에 나 혼자
        assertText(WaitingTags.MAX_VALUE, string(R.string.mp_max_players, 3))
        // 혼자서는 시작할 수 없고 이유가 보인다
        assertEnabled(WaitingTags.START, false)
        waitFor(WaitingTags.BLOCKERS)
    }

    @Test
    fun `대기실 - 봇을 추가하면 레이팅 안내가 바뀌고 시작할 수 있고, 빼면 되돌아온다, 나가면 방이 닫힌다`() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
        compose.onNodeWithText(string(R.string.mp_room_rated)).assertIsDisplayed()

        click(WaitingTags.ADD_BOT_EASY)
        waitFor(WaitingTags.seat("bot-1"))
        compose.onNodeWithText(string(R.string.mp_room_unrated)).assertIsDisplayed()
        until { fx.shell.room?.state?.value?.canStart == true }
        assertEnabled(WaitingTags.START, true)

        click(WaitingTags.kick("bot-1"))
        waitGone(WaitingTags.seat("bot-1"))
        compose.onNodeWithText(string(R.string.mp_room_rated)).assertIsDisplayed()

        val roomId = fx.shell.room!!.roomId
        click(WaitingTags.LEAVE)
        waitFor(LobbyTags.CREATE) // 로비로
        val room = runBlocking { fx.remote("other@test.co", "다른이").rooms.observe(roomId).first() }
        assertEquals(RoomStatus.CLOSED, room?.status) // 방장이 나가서 닫혔다
    }

    @Test
    fun `방장 - 봇과 한 판 시작하고 기권하면 결과에 레이팅 제외 안내가 뜨고, 대기실로 돌아온다`() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
        click(WaitingTags.ADD_BOT_EASY)
        until { fx.shell.room?.state?.value?.canStart == true }
        click(WaitingTags.START)

        waitFor(GameTags.CONCEDE)
        click(GameTags.CONCEDE)
        waitFor(GameTags.CONCEDE_CONFIRM)
        click(GameTags.CONCEDE_CONFIRM)
        waitFor(GameTags.RESULT)
        waitFor(FlowTags.RATING)
        assertShown(string(R.string.mp_result_rating_unrated)) // 같은 자리의 "반영하는 중" 문구가 바뀐 뒤를 기다린다

        click(GameTags.RESULT_AGAIN) // "대기실로"
        waitFor(WaitingTags.START)
        val roomId = fx.shell.room!!.roomId
        until { runBlocking { fx.remote("other@test.co", "다른이").rooms.observe(roomId).first()?.status } == RoomStatus.WAITING }
    }

    @Test
    fun `게스트 - 목록에서 입장해 준비하면 방장이 시작할 때 같이 게임에 들어가고, 기권하면 레이팅이 반영된다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("원격 방") }
        val hostModel = host.waitingRoom(room.id)
        runHost(hostModel, host.uid)

        refreshLobby()
        waitFor(LobbyTags.join(room.id))
        compose.onNodeWithText("원격 방").assertIsDisplayed()
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)
        compose.onNodeWithTag(WaitingTags.seat("host")).assertIsDisplayed()
        assertText(WaitingTags.READY, string(R.string.mp_ready))
        click(WaitingTags.READY)
        waitFor(WaitingTags.WAITING_HOST)
        compose.waitUntil(10_000) { compose.onNodeWithTag(WaitingTags.READY).fetchSemanticsNode().config.toString().contains(string(R.string.mp_unready)) }
        until { hostModel.state.value?.canStart == true }

        fx.scope.launch { hostModel.start() }
        waitFor(GameTags.CONCEDE, timeoutMillis = 20_000) // 방장이 시작하면 게스트 화면이 게임으로 넘어간다
        click(GameTags.CONCEDE)
        waitFor(GameTags.CONCEDE_CONFIRM)
        click(GameTags.CONCEDE_CONFIRM)
        waitFor(GameTags.RESULT)

        // 기권한 쪽이 꼴찌: 결과의 순위로 기대 레이팅을 계산한다
        val ranking = fx.game.controller!!.state.value!!.result!!.ranking
        val delta = TableRatingPolicy.ratingDeltas(ranking).getValue(PlayerId("kim"))
        assertTrue("기권하면 레이팅이 내려간다: $delta", delta < 0)
        waitFor(FlowTags.RATING, timeoutMillis = 30_000)
        until(30_000) { fx.shell.settlement != null }
        assertShown(string(R.string.mp_result_rating, 1000 + delta, delta))
        assertEquals(1000 + delta, fx.shell.profile?.rating)

        // 대기실로 돌아오면 방장이 방을 다시 열어 주고, 나는 다시 준비해야 한다
        click(GameTags.RESULT_AGAIN)
        waitFor(WaitingTags.READY)
        fx.scope.launch { hostModel.gameFinished() }
        until { fx.shell.room?.state?.value?.myReady == false }
        assertNotNull(fx.shell.room)
    }

    @Test
    fun `방장이 나를 내보내면 로비로 돌아가 이유를 알려준다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("원격 방") }
        val hostModel = host.waitingRoom(room.id)
        runHost(hostModel, host.uid)
        refreshLobby()
        waitFor(LobbyTags.join(room.id))
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)

        fx.scope.launch { hostModel.kick("kim") }
        waitFor(LobbyTags.NOTICE)
        compose.onNodeWithText(string(R.string.mp_notice_kicked)).assertIsDisplayed()
    }

    @Test
    fun `방장이 방을 닫으면 로비로 돌아가 이유를 알려준다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("원격 방") }
        val hostModel = host.waitingRoom(room.id)
        runHost(hostModel, host.uid)
        refreshLobby()
        waitFor(LobbyTags.join(room.id))
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)

        fx.scope.launch { hostModel.leave() }
        waitFor(LobbyTags.NOTICE)
        compose.onNodeWithText(string(R.string.mp_notice_closed)).assertIsDisplayed()
    }

    @Test
    fun `가득 찬 방은 입장할 수 없고, 없는 코드는 이유를 알려준다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        val other = fx.remote("other@test.co", "다른이")
        runBlocking {
            host.signUp()
            other.signUp()
            val room = host.createRoom("꽉 찬 방", max = 2)
            other.rooms.join(room.id, other.lobbyUser)
        }
        refreshLobby()
        val id = fx.remote("x@test.co", "x").let { runBlocking { it.rooms.listOpen().first().id } }
        waitFor(LobbyTags.join(id))
        assertEnabled(LobbyTags.join(id), false)

        type(LobbyTags.CODE, "ZZZZZZ")
        click(LobbyTags.CODE_JOIN)
        waitFor(LobbyTags.PROBLEM)
        compose.onNodeWithText(string(R.string.mp_room_error_not_found)).assertIsDisplayed()
    }
}
