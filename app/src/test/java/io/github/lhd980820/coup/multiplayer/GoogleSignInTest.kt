package io.github.lhd980820.coup.multiplayer

import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.credentials.CustomCredential
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.shell.auth.GoogleToken
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class GoogleSignInTest : MpScreenTest() {
    // ---- Credential Manager 결과·오류 -> GoogleToken ------------------------------------------------

    @Test
    fun `구글 ID 토큰 자격 증명에서 토큰을 꺼낸다`() {
        val google = GoogleIdTokenCredential.Builder().setId("kim@gmail.com").setIdToken("id-token-123").build()
        val credential = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, google.data)
        assertEquals(GoogleToken.Ok("id-token-123"), GoogleTokens.fromCredential(credential))
    }

    @Test
    fun `다른 종류의 자격 증명이나 깨진 데이터는 실패`() {
        assertEquals(GoogleToken.Failed, GoogleTokens.fromCredential(PasswordCredential("kim", "pw")))
        assertEquals(GoogleToken.Failed, GoogleTokens.fromCredential(CustomCredential("some.other.type", Bundle())))
        // 데이터가 구글 토큰 모양이어도 종류가 다르면 받지 않는다
        val google = GoogleIdTokenCredential.Builder().setId("kim@gmail.com").setIdToken("id-token-123").build()
        assertEquals(GoogleToken.Failed, GoogleTokens.fromCredential(CustomCredential("some.other.type", google.data)))
        assertEquals(GoogleToken.Failed, GoogleTokens.fromCredential(CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())))
    }

    @Test
    fun `오류 종류별로 - 닫음, 계정 없음, 사용 불가, 그 밖의 실패`() {
        assertEquals(GoogleToken.Cancelled, GoogleTokens.fromException(GetCredentialCancellationException()))
        assertEquals(GoogleToken.NoAccount, GoogleTokens.fromException(NoCredentialException()))
        assertEquals(GoogleToken.Unavailable, GoogleTokens.fromException(GetCredentialProviderConfigurationException()))
        assertEquals(GoogleToken.Unavailable, GoogleTokens.fromException(GetCredentialUnsupportedException()))
        assertEquals(GoogleToken.Failed, GoogleTokens.fromException(GetCredentialInterruptedException()))
        assertEquals(GoogleToken.Failed, GoogleTokens.fromException(GetCredentialUnknownException()))
    }

    // ---- 로그인 화면 ------------------------------------------------------------------------------

    @Test
    fun `구글을 쓸 수 없는 구성이면 버튼이 없다`() {
        show(google = null)
        openMultiplayer()
        waitFor(LoginTags.SUBMIT)
        assertTrue(!exists(LoginTags.GOOGLE))
    }

    @Test
    fun `구글로 계속하기 - 계정을 고르면 로그인되고 이메일 앞부분으로 프로필이 생겨 로비가 열린다`() {
        show(google = GoogleTokenSource { GoogleToken.Ok("google:park.g@gmail.com") })
        openMultiplayer()
        waitFor(LoginTags.GOOGLE)
        click(LoginTags.GOOGLE)
        waitFor(LobbyTags.CREATE)
        assertEquals(listOf("google:park.g@gmail.com"), fx.auth.googleTokens)
        assertEquals("park.g", fx.shell.profile?.nickname)
    }

    @Test
    fun `구글로 계속하기 - 가입 화면에서도 보이고, 재설정 화면에는 없다`() {
        show(google = GoogleTokenSource { GoogleToken.Cancelled })
        openMultiplayer()
        click(LoginTags.GO_REGISTER)
        waitFor(LoginTags.GOOGLE)
        click(LoginTags.GO_SIGN_IN)
        click(LoginTags.GO_RESET)
        waitFor(LoginTags.SUBMIT)
        assertTrue(!exists(LoginTags.GOOGLE))
    }

    @Test
    fun `선택 창을 닫으면 로그인 화면에 그대로 있고 오류도 없다`() {
        show(google = GoogleTokenSource { GoogleToken.Cancelled })
        openMultiplayer()
        waitFor(LoginTags.GOOGLE)
        click(LoginTags.GOOGLE)
        compose.waitForIdle()
        assertTrue(!exists(LoginTags.FAILURE))
        assertTrue(exists(LoginTags.SUBMIT))
        assertNull(fx.auth.user.value)
    }

    @Test
    fun `기기에 구글 계정이 없으면 이유를 알려준다`() {
        show(google = GoogleTokenSource { GoogleToken.NoAccount })
        openMultiplayer()
        waitFor(LoginTags.GOOGLE)
        click(LoginTags.GOOGLE)
        waitFor(LoginTags.FAILURE)
        compose.onNodeWithText(string(R.string.mp_auth_google_no_account)).assertIsDisplayed()
    }

    @Test
    fun `계정을 고르는 동안에는 버튼이 잠긴다`() {
        val gate = CompletableDeferred<GoogleToken>()
        show(google = GoogleTokenSource { gate.await() })
        openMultiplayer()
        waitFor(LoginTags.GOOGLE)
        click(LoginTags.GOOGLE)
        until { fx.shell.authForm.state.value.busy }
        assertEnabled(LoginTags.GOOGLE, false)
        gate.complete(GoogleToken.Ok("google:kim@gmail.com"))
        waitFor(LobbyTags.CREATE)
    }
}
