package io.github.lhd980820.coup.multiplayer

import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.lhd980820.coup.shell.auth.GoogleToken
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource
import kotlinx.coroutines.CancellationException

/**
 * Credential Manager로 구글 ID 토큰을 얻는다(ADR 0011). "구글로 계속하기" 버튼 흐름(`GetSignInWithGoogleOption`)이라
 * 기기에 로그인한 계정이 없으면 계정 추가 화면까지 안내한다. [serverClientId]는 Firebase 프로젝트의 웹 클라이언트 ID
 * (`default_web_client_id`, google-services 플러그인이 만든다)다 — 기존 앱과 같은 값이라 기존 구글 사용자는 같은 계정으로 들어온다.
 */
internal class CredentialManagerGoogleTokenSource(private val activity: Activity, private val serverClientId: String) : GoogleTokenSource {
    override suspend fun requestIdToken(): GoogleToken {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        return try {
            GoogleTokens.fromCredential(CredentialManager.create(activity).getCredential(activity, request).credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialException) {
            GoogleTokens.fromException(e)
        }
    }
}

/** Credential Manager의 결과·오류 -> [GoogleToken]. 플랫폼 호출과 떼어 놓아 기기 없이 시험한다. */
internal object GoogleTokens {
    fun fromCredential(credential: Credential): GoogleToken {
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return GoogleToken.Failed
        return try {
            GoogleToken.Ok(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } catch (e: GoogleIdTokenParsingException) {
            GoogleToken.Failed
        }
    }

    fun fromException(e: GetCredentialException): GoogleToken = when (e) {
        is GetCredentialCancellationException -> GoogleToken.Cancelled
        is NoCredentialException -> GoogleToken.NoAccount
        is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException -> GoogleToken.Unavailable
        else -> GoogleToken.Failed
    }
}
