plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    // 서버 판정(ADR 0015): 끝난 게임을 같은 엔진으로 다시 돌려 검증하고 레이팅을 반영한다. 클라이언트에는 들어가지 않는다.
    api(project(":engine"))
    api(project(":runtime"))
    api(project(":account"))
    api(project(":remote"))
    api(libs.kotlinx.coroutines.core)

    // 테스트용 서버(결과가 생기면 판정): 다른 모듈(:shell, 앱)의 테스트가 실제 서버 대신 쓴다
    testFixturesApi(project(":judge"))
    testFixturesApi(testFixtures(project(":remote")))
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":remote")))
    testImplementation(project(":ai"))
    testImplementation(project(":lobby"))
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
