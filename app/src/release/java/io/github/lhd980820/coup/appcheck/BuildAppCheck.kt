package io.github.lhd980820.coup.appcheck

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** 릴리스 빌드의 증명 방식: Play Integrity. Play가 앱과 기기를 확인한 요청만 정상으로 본다. */
internal object BuildAppCheck {
    fun factory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
