// 공통 설정은 최소한으로만 둔다. (설계 문서 §3.1: 별도 build-logic 플러그인 불필요)
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

allprojects {
    group = "io.github.lhd980820.coup"
    version = "0.1.0"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
