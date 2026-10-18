package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.AuthFormModel
import io.github.lhd980820.coup.shell.auth.AuthGateway
import io.github.lhd980820.coup.shell.auth.AuthMode
import io.github.lhd980820.coup.shell.auth.AuthResult
import io.github.lhd980820.coup.shell.auth.AuthUser
import io.github.lhd980820.coup.shell.auth.Credentials
import io.github.lhd980820.coup.shell.auth.FieldError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** 계정 목록을 메모리에 두는 가짜 인증. */
class FakeAuth(var next: AuthResult = AuthResult.Ok) : AuthGateway {
    private val _user = MutableStateFlow<AuthUser?>(null)
    override val user: StateFlow<AuthUser?> = _user
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun signIn(email: String, password: String): AuthResult = act("signIn $email $password") {
        if (next == AuthResult.Ok) _user.value = AuthUser("uid-$email", email)
    }

    override suspend fun register(email: String, password: String): AuthResult = act("register $email $password") {
        if (next == AuthResult.Ok) _user.value = AuthUser("uid-$email", email)
    }

    override suspend fun sendPasswordReset(email: String): AuthResult = act("reset $email") {}

    override suspend fun signOut() {
        _user.value = null
    }

    private suspend fun act(call: String, onOk: () -> Unit): AuthResult {
        calls += call
        gate?.await()
        onOk()
        return next
    }
}

class AuthFormModelTest {
    @Test
    fun `이메일 모양 검사 - 앞뒤 공백은 무시하고 골뱅이와 도메인 점이 있어야 한다`() {
        assertThat(Credentials.emailError("  a@b.co  ")).isNull()
        assertThat(Credentials.emailError("")).isEqualTo(FieldError.EMAIL_EMPTY)
        assertThat(Credentials.emailError("   ")).isEqualTo(FieldError.EMAIL_EMPTY)
        for (bad in listOf("abc", "a@b", "a@@b.co", "a b@c.co", "@b.co", "a@.co")) {
            assertThat(Credentials.emailError(bad), name = bad).isEqualTo(FieldError.EMAIL_MALFORMED)
        }
        assertThat(Credentials.passwordError("12345")).isEqualTo(FieldError.PASSWORD_TOO_SHORT)
        assertThat(Credentials.passwordError("123456")).isNull()
    }

    @Test
    fun `로그인 - 틀린 입력은 서버에 보내지 않고, 제출 뒤에야 오류가 보이고, 고치면 사라진다`() = runBlocking {
        val auth = FakeAuth()
        val form = AuthFormModel(auth)
        form.setEmail("not-an-email")
        form.setPassword("x")
        assertThat(form.state.value.emailError).isNull() // 입력 중에는 방해하지 않는다
        form.submit()
        assertThat(form.state.value.emailError).isEqualTo(FieldError.EMAIL_MALFORMED)
        assertThat(auth.calls.isEmpty()).isTrue()
        form.setEmail("a@b.co")
        assertThat(form.state.value.emailError).isNull()
        form.submit()
        assertThat(auth.calls).isEqualTo(listOf("signIn a@b.co x")) // 로그인은 비밀번호 길이를 따지지 않는다(옛 계정)
        assertThat(auth.user.value).isEqualTo(AuthUser("uid-a@b.co", "a@b.co"))
    }

    @Test
    fun `로그인 실패는 사유를 보여주고, 입력을 고치면 사라진다`() = runBlocking {
        val auth = FakeAuth(AuthResult.Failed(AuthError.WRONG_CREDENTIALS))
        val form = AuthFormModel(auth)
        form.setEmail("a@b.co")
        form.setPassword("secret1")
        form.submit()
        assertThat(form.state.value.failure).isEqualTo(AuthError.WRONG_CREDENTIALS)
        assertThat(form.state.value.busy).isFalse()
        form.setPassword("secret2")
        assertThat(form.state.value.failure).isNull()
    }

    @Test
    fun `가입 - 비밀번호 길이와 확인 일치를 검사한다`() = runBlocking {
        val auth = FakeAuth()
        val form = AuthFormModel(auth)
        form.setMode(AuthMode.REGISTER)
        form.setEmail("new@b.co")
        form.setPassword("12345")
        form.setConfirm("12345")
        form.submit()
        assertThat(form.state.value.passwordError).isEqualTo(FieldError.PASSWORD_TOO_SHORT)
        form.setPassword("123456")
        form.setConfirm("1234567")
        form.submit()
        assertThat(form.state.value.confirmError).isEqualTo(FieldError.PASSWORD_MISMATCH)
        assertThat(auth.calls.isEmpty()).isTrue()
        form.setConfirm("123456")
        form.submit()
        assertThat(auth.calls).isEqualTo(listOf("register new@b.co 123456"))
    }

    @Test
    fun `비밀번호 재설정 - 이메일만 필요하고 보냈다고 알려준다`() = runBlocking {
        val auth = FakeAuth()
        val form = AuthFormModel(auth)
        form.setMode(AuthMode.RESET)
        form.setEmail(" a@b.co ")
        assertThat(form.state.value.canSubmit).isTrue()
        form.submit()
        assertThat(auth.calls).isEqualTo(listOf("reset a@b.co"))
        assertThat(form.state.value.resetSent).isTrue()
        form.setEmail("a@b.com")
        assertThat(form.state.value.resetSent).isFalse()
    }

    @Test
    fun `모드를 바꾸면 비밀번호와 오류를 지우되 이메일은 남긴다`() {
        val form = AuthFormModel(FakeAuth())
        form.setEmail("a@b.co")
        form.setPassword("secret")
        form.setMode(AuthMode.REGISTER)
        assertThat(form.state.value.email).isEqualTo("a@b.co")
        assertThat(form.state.value.password).isEqualTo("")
    }

    @Test
    fun `진행 중에 다시 제출해도 한 번만 보낸다`() = runBlocking {
        val auth = FakeAuth().apply { gate = CompletableDeferred() }
        val form = AuthFormModel(auth)
        form.setEmail("a@b.co")
        form.setPassword("secret")
        val first = async { form.submit() }
        while (!form.state.value.busy) kotlinx.coroutines.yield()
        form.submit()
        auth.gate!!.complete(Unit)
        first.await()
        assertThat(auth.calls.size).isEqualTo(1)
    }

    @Test
    fun `제출 가능 여부 - 빈 칸이 있으면 막는다`() {
        val form = AuthFormModel(FakeAuth())
        assertThat(form.state.value.canSubmit).isFalse()
        form.setEmail("a@b.co")
        assertThat(form.state.value.canSubmit).isFalse()
        form.setPassword("x")
        assertThat(form.state.value.canSubmit).isTrue()
        form.setMode(AuthMode.REGISTER)
        form.setPassword("secret")
        assertThat(form.state.value.canSubmit).isFalse() // 확인 칸이 비어 있다
    }
}
