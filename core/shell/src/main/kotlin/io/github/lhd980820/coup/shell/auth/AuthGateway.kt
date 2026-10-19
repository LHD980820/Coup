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

    /** 이 이메일은 이미 다른 방법(예: 이메일/비밀번호)으로 가입되어 있다 — 그 방법으로 로그인해야 한다. */
    CREDENTIAL_CONFLICT,

    /** 기기에 구글 계정이 없다(계정을 추가해야 한다). */
    GOOGLE_NO_ACCOUNT,

    /** 이 기기에서 구글 로그인을 쓸 수 없다(Play 서비스 없음·구성 오류). */
    GOOGLE_UNAVAILABLE,
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

    /** 구글이 발급한 ID 토큰으로 로그인한다. 처음이면 계정이 만들어진다(기존 앱에서 구글로 가입한 사용자는 같은 계정이다). */
    public suspend fun signInWithGoogle(idToken: String): AuthResult
    public suspend fun signOut()
}

/** 기기의 구글 계정 선택 결과. 플랫폼(Credential Manager)이 만든다. */
public sealed interface GoogleToken {
    public data class Ok(val idToken: String) : GoogleToken

    /** 사용자가 선택 창을 닫았다(오류가 아니다). */
    public data object Cancelled : GoogleToken
    public data object NoAccount : GoogleToken
    public data object Unavailable : GoogleToken
    public data object Failed : GoogleToken
}

/** 구글 ID 토큰을 얻는 통로. 앱은 Credential Manager로 구현하고, 테스트는 정해진 결과를 돌려준다. 예외를 던지지 않는다. */
public fun interface GoogleTokenSource {
    public suspend fun requestIdToken(): GoogleToken
}
