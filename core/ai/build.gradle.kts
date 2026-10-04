plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
}

dependencies {
    // AI는 엔진의 공개 API(PlayerView, DecisionRequest, Command)만 쓴다. GameState는 쓰지 않는다(설계 §7.2).
    api(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
}

// 대규모 대결(@Tag("slow"))은 기본 테스트에서 제외하고 `./gradlew :ai:tournament`로 따로 돌린다.
tasks.test {
    useJUnitPlatform { excludeTags("slow") }
}
tasks.register<Test>("tournament") {
    description = "AI 난이도별 대규모 대결 측정 (느림)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    testLogging { showStandardStreams = true }
}
