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
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // 반환형이 Unit이 아닌 @Test(예: `= runBlocking { assertThat(..) }`)는 JUnit이 조용히 건너뛴다 -> 발견 경고도 실패로 처리한다
            systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
            // 규칙 추적(-Pruletrace=<dir>): 모델의 접근 판단을 기록해 firebase/rules-test가 실제 규칙과 비교한다
            providers.gradleProperty("ruletrace").orNull?.let {
                systemProperty("coup.rules.trace", it)
                outputs.upToDateWhen { false }
            }
        }
    }
}
