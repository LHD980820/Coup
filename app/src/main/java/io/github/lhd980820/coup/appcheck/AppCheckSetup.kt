package io.github.lhd980820.coup.appcheck

import android.util.Log
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck

/** App Check 제공자를 설치하는 대상. 실제로는 [FirebaseAppCheck]이고, 테스트는 가짜로 바꾼다. */
internal fun interface AppCheckTarget {
    fun install(factory: AppCheckProviderFactory)
}

/**
 * Firebase App Check를 켠다. 설치하면 Firestore·Auth 요청에 "정상 앱에서 왔다"는 증명 토큰이 자동으로 붙는다.
 * 증명 방식([AppCheckProviderFactory])은 빌드가 정한다: 릴리스는 Play Integrity, 디버그는 디버그 제공자([BuildAppCheck]).
 *
 * 설치가 실패해도 앱은 멈추지 않는다 — 토큰 없이 요청이 나가고, 콘솔에서 "적용"을 켠 서비스만 그 요청을 거절한다.
 */
internal object AppCheckSetup {
    sealed interface Result {
        data object Installed : Result
        data class Failed(val error: Throwable) : Result
    }

    private val real = AppCheckTarget { FirebaseAppCheck.getInstance().installAppCheckProviderFactory(it) }

    fun install(
        factory: AppCheckProviderFactory,
        target: AppCheckTarget = real,
        log: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
    ): Result = try {
        target.install(factory)
        Result.Installed
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        log("App Check could not be installed; requests will go out without a token", e)
        Result.Failed(e)
    }

    private const val TAG = "AppCheck"
}
