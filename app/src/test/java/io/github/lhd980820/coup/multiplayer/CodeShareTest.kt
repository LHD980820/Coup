package io.github.lhd980820.coup.multiplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.lobby.JoinCodes
import io.github.lhd980820.coup.lobby.Visibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class CodeShareTest : MpScreenTest() {
    private fun createPrivateRoom(title: String) {
        click(LobbyTags.CREATE)
        waitFor(CreateTags.TITLE)
        compose.onNodeWithTag(CreateTags.TITLE).performTextReplacement(title)
        click(CreateTags.PRIVATE)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.CODE)
    }

    @Test
    fun `비공개 방에서 코드를 복사하면 클립보드에 코드만 들어가고 복사했다고 알린다`() {
        val sharer = FakeCodeSharer()
        signInAndOpenLobby(sharer = sharer)
        createPrivateRoom("우리 방")
        val code = fx.shell.room!!.state.value!!.joinCode!!
        click(WaitingTags.CODE_COPY)
        waitFor(WaitingTags.CODE_COPIED)
        assertEquals(listOf(string(R.string.mp_code_label) to code), sharer.copied)
        compose.onNodeWithText(string(R.string.mp_code_copied)).assertIsDisplayed()
    }

    @Test
    fun `복사가 막히면 실패를 알려 직접 알려 주게 한다`() {
        val sharer = FakeCodeSharer(copyWorks = false)
        signInAndOpenLobby(sharer = sharer)
        createPrivateRoom("우리 방")
        click(WaitingTags.CODE_COPY)
        waitFor(WaitingTags.CODE_COPIED)
        compose.onNodeWithText(string(R.string.mp_code_copy_failed)).assertIsDisplayed()
    }

    @Test
    fun `공유하면 방 이름과 코드가 든 메시지가 공유 시트로 넘어가고, 그 메시지에서 코드를 다시 꺼낼 수 있다`() {
        val sharer = FakeCodeSharer()
        signInAndOpenLobby(sharer = sharer)
        createPrivateRoom("우리 방")
        val code = fx.shell.room!!.state.value!!.joinCode!!
        click(WaitingTags.CODE_SHARE)
        assertEquals(1, sharer.shared.size)
        val (text, chooser) = sharer.shared.single()
        assertEquals(string(R.string.mp_code_share_chooser), chooser)
        assertTrue(text, "우리 방" in text && code in text)
        assertEquals(code, JoinCodes.extract(text)) // 받는 쪽이 통째로 붙여 넣으면 코드가 나온다
    }

    @Test
    fun `공유 수단이 없으면 코드만 보이고 버튼은 없다`() {
        signInAndOpenLobby(sharer = null)
        createPrivateRoom("우리 방")
        assertTrue(exists(WaitingTags.CODE))
        assertTrue(!exists(WaitingTags.CODE_COPY))
        assertTrue(!exists(WaitingTags.CODE_SHARE))
    }

    @Test
    fun `공개 방에는 코드 줄이 없다`() {
        signInAndOpenLobby(sharer = FakeCodeSharer())
        click(LobbyTags.CREATE)
        waitFor(CreateTags.SUBMIT)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.START)
        assertTrue(!exists(WaitingTags.CODE))
        assertTrue(!exists(WaitingTags.CODE_SHARE))
    }

    @Test
    fun `공유받은 메시지를 로비의 코드 칸에 통째로 붙여 넣으면 코드만 남아 입장된다`() {
        signInAndOpenLobby()
        val host = fx.remote("host@test.co", "방장")
        val room = runBlocking {
            host.signUp()
            host.createRoom("비밀 방", visibility = Visibility.PRIVATE)
        }
        val message = string(R.string.mp_code_share_message, "비밀 방", room.joinCode!!)
        type(LobbyTags.CODE, message)
        compose.waitForIdle()
        // 칸에는 코드만 남는다
        val shown = compose.onNodeWithTag(LobbyTags.CODE).fetchSemanticsNode().config
            .firstOrNull { it.key == androidx.compose.ui.semantics.SemanticsProperties.EditableText }?.value.toString()
        assertEquals(room.joinCode!!.uppercase(), shown)
        click(LobbyTags.CODE_JOIN)
        waitFor(WaitingTags.READY)
        compose.onNodeWithText("비밀 방").assertIsDisplayed()
    }
}
