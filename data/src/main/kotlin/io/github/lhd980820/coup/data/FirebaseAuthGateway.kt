package io.github.lhd980820.coup.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.AuthGateway
import io.github.lhd980820.coup.shell.auth.AuthResult
import io.github.lhd980820.coup.shell.auth.AuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * [AuthGateway]의 Firebase Auth 구현(이메일/비밀번호, 구글). 앱 전체에서 하나만 쓴다 — 기존 화면이 같은 [FirebaseAuth]로 로그인해도
 * [user]가 따라 바뀐다. 실패는 [AuthResult.Failed]로 바꾼다(취소만 예외).
 *
 * 이메일 열거 보호가 켜진 프로젝트(기본값)에서는 없는 계정과 틀린 비밀번호가 구분되지 않고 모두 [AuthError.WRONG_CREDENTIALS]다.
 */
public class FirebaseAuthGateway(private val auth: FirebaseAuth) : AuthGateway {
    private val _user = MutableStateFlow(auth.currentUser?.toUser())
    override val user: StateFlow<AuthUser?> = _user.asStateFlow()

    init {
        auth.addAuthStateListener { _user.value = it.currentUser?.toUser() }
    }

    override suspend fun signIn(email: String, password: String): AuthResult =
        attempt { auth.signInWithEmailAndPassword(email, password).await() }

    override suspend fun register(email: String, password: String): AuthResult =
        attempt { auth.createUserWithEmailAndPassword(email, password).await() }

    override suspend fun sendPasswordReset(email: String): AuthResult =
        attempt { auth.sendPasswordResetEmail(email).await() }

    /** 구글 ID 토큰 -> Firebase 자격 증명. 같은 이메일이 다른 방법으로 이미 가입돼 있으면 [AuthError.CREDENTIAL_CONFLICT]. */
    override suspend fun signInWithGoogle(idToken: String): AuthResult =
        attempt(collision = AuthError.CREDENTIAL_CONFLICT) { auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await() }

    override suspend fun signOut() {
        auth.signOut()
    }

    private suspend fun attempt(collision: AuthError = AuthError.EMAIL_IN_USE, block: suspend () -> Unit): AuthResult = try {
        block()
        AuthResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        AuthResult.Failed(errorOf(e).let { if (it == AuthError.EMAIL_IN_USE) collision else it })
    }

    private fun FirebaseUser.toUser() = AuthUser(uid, email)

    internal companion object {
        fun errorOf(e: Exception): AuthError = when (e) {
            is FirebaseNetworkException -> AuthError.NETWORK
            is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
            is FirebaseAuthWeakPasswordException -> AuthError.WEAK_PASSWORD
            is FirebaseAuthUserCollisionException -> AuthError.EMAIL_IN_USE
            is FirebaseAuthInvalidUserException -> AuthError.ACCOUNT_NOT_FOUND
            is FirebaseAuthInvalidCredentialsException -> if (e.errorCode == "ERROR_INVALID_EMAIL") AuthError.INVALID_EMAIL else AuthError.WRONG_CREDENTIALS
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_INVALID_EMAIL" -> AuthError.INVALID_EMAIL
                "ERROR_EMAIL_ALREADY_IN_USE" -> AuthError.EMAIL_IN_USE
                "ERROR_WEAK_PASSWORD" -> AuthError.WEAK_PASSWORD
                "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED" -> AuthError.ACCOUNT_NOT_FOUND
                "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> AuthError.WRONG_CREDENTIALS
                else -> AuthError.UNKNOWN
            }
            else -> AuthError.UNKNOWN
        }
    }
}
