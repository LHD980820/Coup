package io.github.lhd980820.coup.shell.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public enum class AuthMode { SIGN_IN, REGISTER, RESET }

/** 입력칸 검사 결과. 번역은 앱이 맡는다. */
public enum class FieldError { EMAIL_EMPTY, EMAIL_MALFORMED, PASSWORD_TOO_SHORT, PASSWORD_MISMATCH }

/** 이메일·비밀번호 규칙. 서버(Firebase)가 최종 판단하지만, 틀린 입력은 네트워크를 타기 전에 알려준다. */
public object Credentials {
    /** Firebase Auth의 최소 길이. */
    public const val MIN_PASSWORD_LENGTH: Int = 6

    // 느슨한 모양 검사: 골뱅이 하나, 양쪽에 글자, 도메인에 점. 진짜 검증은 서버가 한다.
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    public fun normalizeEmail(input: String): String = input.trim()

    public fun emailError(input: String): FieldError? {
        val email = normalizeEmail(input)
        return when {
            email.isEmpty() -> FieldError.EMAIL_EMPTY
            !EMAIL.matches(email) -> FieldError.EMAIL_MALFORMED
            else -> null
        }
    }

    public fun passwordError(password: String): FieldError? =
        if (password.length < MIN_PASSWORD_LENGTH) FieldError.PASSWORD_TOO_SHORT else null
}

public data class AuthFormState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    /** 제출을 시도한 뒤에만 채워진다(입력 중에는 빨간 글씨로 방해하지 않는다). 해당 칸을 고치면 사라진다. */
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val confirmError: FieldError? = null,
    /** 서버가 거절한 사유. 입력을 고치거나 모드를 바꾸면 사라진다. */
    val failure: AuthError? = null,
    /** 비밀번호 재설정 메일을 보냈다. */
    val resetSent: Boolean = false,
) {
    /** 지금 모드에서 제출할 수 있는가(입력 중 버튼 활성 상태). */
    val canSubmit: Boolean
        get() = !busy && email.isNotBlank() && (mode == AuthMode.RESET || password.isNotEmpty()) && (mode != AuthMode.REGISTER || confirm.isNotEmpty())
}

/**
 * 로그인·가입·비밀번호 재설정 한 화면의 상태 홀더. 로그인에 성공하면 [AuthGateway.user]가 바뀌므로 화면 전환은 앱이 그것을 보고 한다.
 * 제출은 `suspend`이고, 진행 중에 또 불러도 무시한다(중복 탭).
 */
public class AuthFormModel(private val gateway: AuthGateway) {
    private val _state = MutableStateFlow(AuthFormState())
    public val state: StateFlow<AuthFormState> = _state.asStateFlow()

    public fun setMode(mode: AuthMode) {
        _state.update { it.copy(mode = mode, password = "", confirm = "", emailError = null, passwordError = null, confirmError = null, failure = null, resetSent = false) }
    }

    public fun setEmail(value: String) {
        _state.update { it.copy(email = value, emailError = null, failure = null, resetSent = false) }
    }

    public fun setPassword(value: String) {
        _state.update { it.copy(password = value, passwordError = null, confirmError = null, failure = null) }
    }

    public fun setConfirm(value: String) {
        _state.update { it.copy(confirm = value, confirmError = null, failure = null) }
    }

    /**
     * 구글로 로그인한다(계정 선택 -> Firebase 로그인). 사용자가 선택 창을 닫으면 아무 오류도 보이지 않는다.
     * 입력칸은 쓰지 않으므로 입력 검사도 하지 않는다.
     */
    public suspend fun signInWithGoogle(source: GoogleTokenSource) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null, resetSent = false, emailError = null, passwordError = null, confirmError = null) }
        var failure: AuthError? = AuthError.UNKNOWN
        try {
            failure = when (val token = source.requestIdToken()) {
                is GoogleToken.Ok -> (gateway.signInWithGoogle(token.idToken) as? AuthResult.Failed)?.error
                GoogleToken.Cancelled -> null
                GoogleToken.NoAccount -> AuthError.GOOGLE_NO_ACCOUNT
                GoogleToken.Unavailable -> AuthError.GOOGLE_UNAVAILABLE
                GoogleToken.Failed -> AuthError.UNKNOWN
            }
        } finally {
            _state.update { it.copy(busy = false, failure = failure) }
        }
    }

    public suspend fun submit() {
        val s = _state.value
        if (s.busy) return
        val emailError = Credentials.emailError(s.email)
        val passwordError = if (s.mode == AuthMode.REGISTER) Credentials.passwordError(s.password) else null
        val confirmError = if (s.mode == AuthMode.REGISTER && passwordError == null && s.password != s.confirm) FieldError.PASSWORD_MISMATCH else null
        // 로그인은 비밀번호 길이를 따지지 않는다(옛 계정의 짧은 비밀번호도 들어올 수 있어야 한다). 비어 있지 않으면 서버에 맡긴다.
        if (emailError != null || passwordError != null || confirmError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError, confirmError = confirmError, failure = null) }
            return
        }
        _state.update { it.copy(busy = true, failure = null, resetSent = false) }
        val email = Credentials.normalizeEmail(s.email)
        val result = when (s.mode) {
            AuthMode.SIGN_IN -> gateway.signIn(email, s.password)
            AuthMode.REGISTER -> gateway.register(email, s.password)
            AuthMode.RESET -> gateway.sendPasswordReset(email)
        }
        _state.update {
            when (result) {
                is AuthResult.Ok -> it.copy(busy = false, resetSent = s.mode == AuthMode.RESET, password = "", confirm = "")
                is AuthResult.Failed -> it.copy(busy = false, failure = result.error)
            }
        }
    }
}
