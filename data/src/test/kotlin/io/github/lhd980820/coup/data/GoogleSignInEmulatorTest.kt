package io.github.lhd980820.coup.data

import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.account.AccountState
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.AuthResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 구글 로그인을 Auth 에뮬레이터로 확인한다(ADR 0011). 에뮬레이터는 서명 없는 ID 토큰(JSON 문자열)을 받아 주므로
 * 실제 구글 계정 없이 "구글이 이 사람이라고 확인했다"를 흉내 낼 수 있다.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@Config(sdk = [35])
class GoogleSignInEmulatorTest {
    private fun googleToken(sub: String, email: String) = """{"sub":"$sub","email":"$email","email_verified":true}"""

    @Test
    fun `구글로 처음 로그인하면 계정이 생기고, 다시 로그인해도 같은 계정이다`() = emulatorTest {
        val gateway = FirebaseAuthGateway(newApp("google").first)
        assertEquals(AuthResult.Ok, gateway.signInWithGoogle(googleToken("g-1", "kim@gmail.com")))
        val first = withTimeout(5_000) { gateway.user.first { it != null } }!!
        assertEquals("kim@gmail.com", first.email)
        gateway.signOut()
        withTimeout(5_000) { gateway.user.first { it == null } }

        val again = FirebaseAuthGateway(newApp("google-again").first)
        assertEquals(AuthResult.Ok, again.signInWithGoogle(googleToken("g-1", "kim@gmail.com")))
        assertEquals(first.uid, withTimeout(5_000) { again.user.first { it != null } }!!.uid)

        val other = FirebaseAuthGateway(newApp("google-other").first)
        assertEquals(AuthResult.Ok, other.signInWithGoogle(googleToken("g-2", "lee@gmail.com")))
        assertNotEquals(first.uid, withTimeout(5_000) { other.user.first { it != null } }!!.uid)
    }

    @Test
    fun `깨진 토큰은 로그인하지 않고 실패로 알린다`() = emulatorTest {
        val gateway = FirebaseAuthGateway(newApp("google").first)
        val result = gateway.signInWithGoogle("not-a-token")
        assertTrue("실패여야 한다: $result", result is AuthResult.Failed)
        assertNull(gateway.user.value)
    }

    @Test
    fun `같은 이메일이 비밀번호로 가입돼 있어도 계정이 둘로 갈라지지 않는다`() = emulatorTest {
        val password = FirebaseAuthGateway(newApp("pw").first)
        assertEquals(AuthResult.Ok, password.register("park@example.com", "secret-123"))
        val pwUid = withTimeout(5_000) { password.user.first { it != null } }!!.uid

        val google = FirebaseAuthGateway(newApp("google").first)
        val result = google.signInWithGoogle(googleToken("g-park", "park@example.com"))
        // 에뮬레이터는 "이메일당 계정 하나" 설정을 따른다: 다른 방법으로 이미 가입된 이메일은 충돌이거나, 같은 계정으로 이어진다.
        // 어느 쪽이든 새 uid로 갈라지면 안 된다(전적이 둘로 나뉜다).
        when (result) {
            is AuthResult.Failed -> assertEquals(AuthError.CREDENTIAL_CONFLICT, result.error)
            AuthResult.Ok -> assertEquals(pwUid, withTimeout(5_000) { google.user.first { it != null } }!!.uid)
        }
    }

    @Test
    fun `기존 앱에서 쓰던 계정 - 구글로 로그인하면 옛 기록(user 이메일 문서)을 이어받는다`() = emulatorTest {
        Emulator.seed("user/old@gmail.com", buildJsonObject { put("nickname", "옛고수"); put("rating", 1450); put("plays", 77) })
        val (auth, db) = newApp("legacy-google")
        val gateway = FirebaseAuthGateway(auth)
        assertEquals(AuthResult.Ok, gateway.signInWithGoogle(googleToken("g-old", "old@gmail.com")))
        val user = withTimeout(5_000) { gateway.user.first { it != null } }!!
        val scope = CoroutineScope(SupervisorJob())
        try {
            val account = AccountSession(FirestoreUserRepository(FirebaseDocumentStore(db), user.uid, user.email), user.email, "player", scope)
            account.start()
            assertEquals(AccountState.Ready, account.state.value)
            val profile = account.profile.value!!
            assertEquals("옛고수", profile.nickname)
            assertEquals(1450, profile.rating)
            assertTrue(profile.migratedFromLegacy)
        } finally {
            scope.cancel()
        }
    }
}
