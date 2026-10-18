plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
}

dependencies {
    // 로그인·계정·로비·대기실의 화면 상태와 사용자 의도(순수 Kotlin). Android·Firebase 의존 없음 — 어댑터는 :data(docs/adr/0010).
    api(project(":remote"))
    api(project(":lobby"))
    api(project(":account"))
    api(project(":presentation"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":remote")))
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
