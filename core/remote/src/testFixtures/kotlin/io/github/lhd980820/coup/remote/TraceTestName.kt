package io.github.lhd980820.coup.remote

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** 현재 실행 중인 테스트 이름을 [RuleTrace]에 알려준다(규칙 불일치를 테스트 이름으로 보고하기 위해). */
class TraceTestName : BeforeEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        current = "${context.requiredTestClass.simpleName}.${context.displayName}"
    }

    companion object {
        @Volatile
        var current: String = "unknown"
    }
}
