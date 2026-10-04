plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    explicitApi()
}

// 설계 문서 Phase 1 완료 기준: 엔진 라인 커버리지 90% 이상 (./gradlew koverVerify)
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
}
