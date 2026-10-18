package io.github.lhd980820.coup.multiplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.shell.auth.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class MultiplayerAuthTest : MpScreenTest() {
    @Test
    fun `로그인 전 홈에는 멀티플레이 버튼만 있고 프로필 카드는 없다`() {
        show()
        waitFor(ShellTags.HOME_MULTI)
        assertTrue(!exists(ProfileTags.CARD))
    }

    @Test
    fun `형식이 틀린 이메일은 서버에 가기 전에 알려준다`() {
        show()
        openMultiplayer()
        signInWith("not-an-email", "x")
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.mp_field_email_malformed)).assertIsDisplayed()
        assertNull(fx.auth.user.value)
    }

    @Test
    fun `서버가 거절하면 이유를 보여주고 입력을 고치면 사라진다`() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "wrong-password")
        waitFor(LoginTags.FAILURE)
        compose.onNodeWithText(string(R.string.mp_auth_wrong_credentials)).assertIsDisplayed()
        assertNull(fx.auth.user.value)
        type(LoginTags.PASSWORD, "!") // 입력을 고치면 오류가 사라진다
        waitGone(LoginTags.FAILURE)
    }

    @Test
    fun `맞는 계정으로 로그인하면 프로필이 만들어지고 로비가 열린다`() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        assertEquals("kim", fx.shell.profile?.nickname) // 이메일 앞부분이 기본 닉네임
        assertEquals(1000, fx.shell.profile?.rating)
        assertTrue(exists(LobbyTags.EMPTY))
    }

    @Test
    fun `가입 - 비밀번호가 짧거나 확인이 다르면 알려주고, 맞으면 가입해서 로비로`() {
        show()
        openMultiplayer()
        click(LoginTags.GO_REGISTER)
        waitFor(LoginTags.CONFIRM)
        type(LoginTags.EMAIL, "new@test.co")
        type(LoginTags.PASSWORD, "12345")
        type(LoginTags.CONFIRM, "12345")
        click(LoginTags.SUBMIT)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.mp_field_password_too_short, Credentials.MIN_PASSWORD_LENGTH)).assertIsDisplayed()

        type(LoginTags.PASSWORD, "6") // 123456
        click(LoginTags.SUBMIT)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.mp_field_password_mismatch)).assertIsDisplayed()

        type(LoginTags.CONFIRM, "6") // 123456
        click(LoginTags.SUBMIT)
        waitFor(LobbyTags.CREATE)
        assertEquals("new", fx.shell.profile?.nickname)
        assertNotNull(fx.auth.user.value)
    }

    @Test
    fun `비밀번호 재설정 - 메일을 보냈다고 알려준다`() {
        show()
        openMultiplayer()
        click(LoginTags.GO_RESET)
        waitFor(LoginTags.EMAIL)
        assertTrue(!exists(LoginTags.PASSWORD))
        type(LoginTags.EMAIL, "kim@test.co")
        click(LoginTags.SUBMIT)
        waitFor(LoginTags.RESET_SENT)
    }

    @Test
    fun `프로필 카드 - 닉네임 바꾸기는 규칙을 안내하고, 저장하면 갱신되고, 로그아웃하면 사라진다`() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        click(LobbyTags.BACK)

        waitFor(ProfileTags.CARD)
        compose.onNodeWithText(string(R.string.mp_profile_line, "kim", 1000)).assertIsDisplayed()
        click(ProfileTags.EDIT)
        waitFor(ProfileTags.NICKNAME)
        compose.onNodeWithTag(ProfileTags.NICKNAME).performTextReplacement("a")
        click(ProfileTags.SAVE)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(string(R.string.mp_nickname_too_short)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(ProfileTags.NICKNAME).performTextReplacement("김철수")
        click(ProfileTags.SAVE)
        waitGone(ProfileTags.NICKNAME) // 저장하면 편집이 닫힌다
        compose.waitUntil(10_000) { fx.shell.profile?.nickname == "김철수" }
        compose.onNodeWithText(string(R.string.mp_profile_line, "김철수", 1000)).assertIsDisplayed()

        click(ProfileTags.SIGN_OUT)
        waitGone(ProfileTags.CARD)
        assertNull(fx.shell.services)
        // 다시 멀티플레이를 열면 로그인 화면이다
        openMultiplayer()
        waitFor(LoginTags.EMAIL)
    }

    @Test
    fun `계정을 준비하는 동안 안내가 보이고, 끝나면 로비가 열린다`() {
        lateinit var slow: SlowStore
        fx.backend.wrapStore = { SlowStore(it).also { s -> slow = s; s.gate = kotlinx.coroutines.CompletableDeferred() } }
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(FlowTags.ACCOUNT_LOADING)
        assertTrue(!exists(LobbyTags.CREATE))
        until { fx.shell.services != null } // 로그인 직후 서비스가 만들어질 때까지(그 전에도 준비 중 화면이 보인다)
        slow.gate!!.complete(Unit)
        waitFor(LobbyTags.CREATE)
    }

    @Test
    fun `계정을 준비하지 못하면 이유를 알리고, 다시 시도하면 로비가 열린다`() {
        lateinit var slow: SlowStore
        fx.backend.wrapStore = { SlowStore(it).also { s -> slow = s; s.fail = true } }
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(FlowTags.ACCOUNT_FAILED)
        until { fx.shell.services != null }
        slow.fail = false
        click(FlowTags.ACCOUNT_RETRY)
        waitFor(LobbyTags.CREATE)
        assertEquals("kim", fx.shell.profile?.nickname)
    }
}
