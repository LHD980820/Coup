package io.github.lhd980820.coup.data

import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.account.AccountState
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.AuthResult
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 로그인 어댑터를 실제 Firebase Auth 에뮬레이터에서 확인한다(ADR 0010). */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@Config(sdk = [35])
class FirebaseAuthGatewayTest {
    private fun failed(result: AuthResult): AuthError = (result as? AuthResult.Failed)?.error ?: error("expected Failed but was $result")

    @Test
    fun `가입, 로그아웃, 로그인 - 사용자 흐름이 따라 바뀐다`() = emulatorTest {
        val gateway = FirebaseAuthGateway(newApp("auth").first)
        assertNull(gateway.user.value)
        assertEquals(AuthResult.Ok, gateway.register("kim@coup.test", "secret-123"))
        val me = withTimeout(5_000) { gateway.user.first { it != null } }!!
        assertEquals("kim@coup.test", me.email)
        gateway.signOut()
        withTimeout(5_000) { gateway.user.first { it == null } }
        assertEquals(AuthResult.Ok, gateway.signIn("kim@coup.test", "secret-123"))
        assertEquals(me.uid, withTimeout(5_000) { gateway.user.first { it != null } }!!.uid)
    }

    @Test
    fun `실패 사유 - 틀린 비밀번호, 없는 계정, 중복 가입, 약한 비밀번호, 잘못된 이메일`() = emulatorTest {
        val gateway = FirebaseAuthGateway(newApp("auth").first)
        assertEquals(AuthResult.Ok, gateway.register("lee@coup.test", "secret-123"))
        gateway.signOut()

        assertEquals(AuthError.WRONG_CREDENTIALS, failed(gateway.signIn("lee@coup.test", "wrong-password")))
        // 이메일 열거 보호에 따라 없는 계정도 같은 사유일 수 있다
        assertTrue(failed(gateway.signIn("nobody@coup.test", "secret-123")) in setOf(AuthError.WRONG_CREDENTIALS, AuthError.ACCOUNT_NOT_FOUND))
        assertEquals(AuthError.EMAIL_IN_USE, failed(gateway.register("lee@coup.test", "secret-123")))
        assertEquals(AuthError.WEAK_PASSWORD, failed(gateway.register("new@coup.test", "123")))
        assertEquals(AuthError.INVALID_EMAIL, failed(gateway.register("not-an-email", "secret-123")))
        assertNull(gateway.user.value) // 실패는 로그인 상태를 바꾸지 않는다
    }

    @Test
    fun `비밀번호 재설정 메일 요청은 성공하고, 잘못된 이메일은 알려준다`() = emulatorTest {
        val gateway = FirebaseAuthGateway(newApp("auth").first)
        assertEquals(AuthResult.Ok, gateway.register("park@coup.test", "secret-123"))
        gateway.signOut()
        assertEquals(AuthResult.Ok, gateway.sendPasswordReset("park@coup.test"))
        // 실제 Firebase는 INVALID_EMAIL, 에뮬레이터는 "계정 없음"으로 답한다 — 앱은 둘 다 실패로 보여준다(입력 모양은 폼이 먼저 거른다)
        assertTrue(failed(gateway.sendPasswordReset("broken")) in setOf(AuthError.INVALID_EMAIL, AuthError.ACCOUNT_NOT_FOUND))
    }

    @Test
    fun `가입 직후 계정 준비 - 이메일 앞부분이 닉네임이 되고 실제 규칙이 받아들인다`() = emulatorTest {
        val (auth, db) = newApp("account")
        val gateway = FirebaseAuthGateway(auth)
        assertEquals(AuthResult.Ok, gateway.register("choi.min@coup.test", "secret-123"))
        val user = withTimeout(5_000) { gateway.user.first { it != null } }!!
        val users = FirestoreUserRepository(FirebaseDocumentStore(db), user.uid, user.email)
        // 프로필 구독 코루틴은 끝없이 도므로 테스트 범위와 따로 둔다
        val scope = CoroutineScope(SupervisorJob())
        try {
            val account = AccountSession(users, user.email, "player", scope)
            account.start()
            assertEquals(AccountState.Ready, account.state.value)
            assertEquals("choi.min", account.profile.value!!.nickname)
            assertNotNull(account.profile.value)
        } finally {
            scope.cancel()
        }
    }
}
