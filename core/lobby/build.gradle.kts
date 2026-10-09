plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    // 방/로비 규칙(순수 Kotlin). 게임 시작 계획이 엔진 설정과 호스트 좌석으로 이어지도록 :runtime, :ai에 의존한다. (docs/adr/0006)
    api(project(":runtime"))
    api(project(":ai"))
    api(project(":engine"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
