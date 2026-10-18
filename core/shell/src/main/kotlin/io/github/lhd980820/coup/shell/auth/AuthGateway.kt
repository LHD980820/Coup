package io.github.lhd980820.coup.shell.auth

import kotlinx.coroutines.flow.StateFlow

/** 로그인한 사용자. [email]은 소셜 로그인 등으로 없을 수 있다. */
public data class AuthUser(val uid: String, val email: String?)

/** 인증 실패 사유. 번역은 앱이 맡는다. */
public enum class AuthError {
    INVALID_EMAIL,
    WEAK_PASSWORD,
    WRONG_CREDENTIALS,
    EMAIL_IN_USE,
    ACCOUNT_NOT_FOUND,
    TOO_MANY_REQUESTS,
    NETWORK,
    UNKNOWN,
}

public sealed interface AuthResult {
    public data object Ok : AuthResult
    public data class Failed(val error: AuthError) : AuthResult
}

/**
 * 인증 통로(순수 인터페이스). Firebase Auth 구현은 `:data`에 있다. 로그인 상태는 [user]로 흘러나오고, 앱은 이것으로 화면을 가른다.
 * 실패는 예외가 아니라 [AuthResult.Failed]로 돌려준다(취소만 예외).
 */
public interface AuthGateway {
    /** 지금 로그인한 사용자(없으면 null). 앱을 다시 켜도 유지된다. */
    public val user: StateFlow<AuthUser?>

    public suspend fun signIn(email: String, password: String): AuthResult
    public suspend fun register(email: String, password: String): AuthResult
    public suspend fun sendPasswordReset(email: String): AuthResult
    public suspend fun signOut()
}
