plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    // Firestore 모양의 문서 저장소 위에 구현한 GameTransport. Firebase SDK에는 의존하지 않는다 — 실제 어댑터는 Android 쪽(:data)이
    // DocumentStore를 구현한다(docs/adr/0005).
    api(project(":runtime"))
    api(project(":engine"))
    api(project(":account"))
    api(project(":lobby"))
    api(libs.kotlinx.coroutines.core)

    // 규칙을 강제하는 메모리 속 Firestore(FakeFirestore)와 규칙 추적은 다른 모듈의 테스트(:shell)도 쓴다
    testFixturesApi(project(":remote"))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":ai"))
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
