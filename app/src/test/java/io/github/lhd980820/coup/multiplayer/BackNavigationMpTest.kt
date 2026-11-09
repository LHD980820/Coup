package io.github.lhd980820.coup.multiplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.lhd980820.coup.game.BackTags
import io.github.lhd980820.coup.game.GameTags
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.pressBack
import io.github.lhd980820.coup.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 멀티플레이 영역의 뒤로 가기(백로그 3): 안쪽 화면부터 한 단계씩, 마지막은 홈. 기기와 같은 순서(대화상자 먼저)로 누른다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class BackNavigationMpTest : MpScreenTest() {
    // ---- 로그인 ----

    @Test
    fun `로그인 화면에서 뒤로 가기는 가입 모드에서는 로그인으로, 로그인에서는 홈으로`() {
        show()
        openMultiplayer()
        waitFor(LoginTags.EMAIL)
        click(LoginTags.GO_REGISTER)
        waitFor(LoginTags.CONFIRM) // 가입 화면에만 있는 비밀번호 확인 칸
        compose.pressBack()
        waitGone(LoginTags.CONFIRM)
        assertTrue(exists(LoginTags.GO_REGISTER)) // 로그인 모드
        compose.pressBack()
        waitFor(ShellTags.HOME_MULTI) // 홈
        assertTrue(!exists(LoginTags.EMAIL))
    }

    @Test
    fun `비밀번호 재설정 화면에서도 뒤로 가기는 먼저 로그인으로`() {
        show()
        openMultiplayer()
        waitFor(LoginTags.GO_RESET)
        click(LoginTags.GO_RESET)
        waitGone(LoginTags.GO_RESET)
        compose.pressBack()
        waitFor(LoginTags.GO_RESET) // 다시 로그인 모드
        assertTrue(exists(LoginTags.EMAIL))
    }

    // ---- 로비 / 방 만들기 ----

    @Test
    fun `로비에서 뒤로 가기는 홈으로`() {
        signInAndOpenLobby()
        compose.pressBack()
        waitFor(ShellTags.HOME_MULTI)
        assertTrue(!exists(LobbyTags.CREATE))
    }

    @Test
    fun `방 만들기에서 뒤로 가기는 로비로, 로비에서 한 번 더 누르면 홈으로`() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        compose.pressBack()
        waitFor(LobbyTags.CREATE)
        assertTrue(!exists(CreateTags.SUBMIT))
        compose.pressBack()
        waitFor(ShellTags.HOME_MULTI)
    }

    // ---- 대기실 ----

    private fun openRoom() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
    }

    @Test
    fun `대기실에서 뒤로 가기는 바로 나가지 않고 묻는다 - 방장에게는 방이 닫힌다고 경고하고, 머무르면 그대로`() {
        openRoom()
        val roomId = fx.shell.room!!.roomId
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        assertTrue(exists(WaitingTags.START)) // 아직 대기실이다
        assertTrue(exists(BackTags.STAY))
        compose.onNodeWithTag(BackTags.STAY).performClick()
        waitGone(BackTags.DIALOG)
        assertNotNull(fx.shell.room)
        assertEquals(RoomStatus.WAITING, runBlocking { fx.remote("other@test.co", "다른이").rooms.observe(roomId).first()?.status })
    }

    @Test
    fun `방장에게는 방이 닫힌다는 경고가 보인다`() {
        openRoom()
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithText(string(R.string.back_leave_room_host)).assertIsDisplayed()
    }

    @Test
    fun `게스트에게는 방장 경고 없이 묻고, 나가도 방은 그대로 열려 있다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("원격 방") }
        val hostModel = host.waitingRoom(room.id)
        fx.scope.launch { hostModel.run() } // 방장 쪽 방 모델이 방을 지켜본다
        compose.onNodeWithTag(LobbyTags.REFRESH).performClick()
        waitFor(LobbyTags.join(room.id))
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)

        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithText(string(R.string.back_leave_room)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(string(R.string.back_leave_room_host)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(BackTags.CONFIRM).performClick()
        waitFor(LobbyTags.CREATE)
        assertEquals(RoomStatus.WAITING, runBlocking { host.rooms.observe(room.id).first()?.status }) // 게스트가 나가도 방은 열려 있다
    }

    @Test
    fun `대기실에서 뒤로 가기 후 나가기를 고르면 로비로, 방장이면 방이 닫힌다`() {
        openRoom()
        val roomId = fx.shell.room!!.roomId
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.onNodeWithTag(BackTags.CONFIRM).performClick()
        waitFor(LobbyTags.CREATE)
        assertEquals(RoomStatus.CLOSED, runBlocking { fx.remote("other@test.co", "다른이").rooms.observe(roomId).first()?.status })
    }

    @Test
    fun `대기실에서 뒤로 가기 확인 중에 한 번 더 누르면 머무르기와 같다`() {
        openRoom()
        compose.pressBack()
        waitFor(BackTags.DIALOG)
        compose.pressBack()
        waitGone(BackTags.DIALOG)
        assertTrue(exists(WaitingTags.START))
    }

    // ---- 멀티플레이 게임 ----

    @Test
    fun `멀티플레이 결과 대화상자에서 뒤로 가기는 대기실로 돌아간다`() {
        openRoom()
        click(WaitingTags.ADD_BOT_EASY)
        until { fx.shell.room?.state?.value?.canStart == true }
        click(WaitingTags.START)
        waitFor(GameTags.CONCEDE)
        click(GameTags.CONCEDE)
        waitFor(GameTags.CONCEDE_CONFIRM)
        click(GameTags.CONCEDE_CONFIRM)
        waitFor(GameTags.RESULT)
        compose.pressBack() // 기기에서는 결과 대화상자가 받는다(예전에는 삼켜졌다)
        waitFor(WaitingTags.START)
        assertTrue(!exists(GameTags.RESULT))
    }

    // ---- 랭킹 / 전적 ----

    @Test
    fun `랭킹과 전적 화면에서 뒤로 가기는 홈으로`() {
        signInAndOpenLobby()
        compose.pressBack() // 홈
        waitFor(ProfileTags.RANKING)
        click(ProfileTags.RANKING)
        waitFor(StatsTags.BACK) // 랭킹 화면
        compose.pressBack()
        waitFor(ProfileTags.RANKING)
        assertTrue(!exists(StatsTags.BACK))
        click(ProfileTags.HISTORY)
        waitFor(StatsTags.BACK) // 전적 화면
        compose.pressBack()
        waitFor(ProfileTags.HISTORY)
        assertTrue(!exists(StatsTags.BACK))
    }
}
