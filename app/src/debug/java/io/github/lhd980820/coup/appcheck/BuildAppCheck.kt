package io.github.lhd980820.coup.appcheck

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * 디버그 빌드의 증명 방식: 디버그 제공자. 처음 실행하면 로그(`DebugAppCheckProvider`)에 비밀 토큰이 한 번 찍힌다 —
 * Firebase 콘솔 > App Check > 앱 > "디버그 토큰 관리"에 그 값을 등록하면 이 기기가 통과한다.
 */
internal object BuildAppCheck {
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
