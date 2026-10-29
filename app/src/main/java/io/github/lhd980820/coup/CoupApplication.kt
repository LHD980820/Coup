package io.github.lhd980820.coup

import android.app.Application
import io.github.lhd980820.coup.appcheck.AppCheckSetup
import io.github.lhd980820.coup.appcheck.BuildAppCheck

/** 앱이 시작될 때 한 번 하는 일: Firebase 요청에 증명 토큰을 붙이는 App Check 설치. */
class CoupApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCheckSetup.install(BuildAppCheck.factory())
    }
}
