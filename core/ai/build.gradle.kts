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
