plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":engine"))
    api(project(":ai"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// kotlinx-coroutines-test의 가상 시간 API(advanceTimeBy 등)는 실험 API다. 테스트 코드에만 허용한다.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}
