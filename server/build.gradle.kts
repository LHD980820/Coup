// 서버 판정 Cloud Function(ADR 0015): 결과 문서가 생기면 끝난 게임을 다시 돌려 검증하고 레이팅을 반영한다.
// Android 빌드와 따로 빌드한다(배포물 = 의존성을 모두 담은 JAR 하나). 배포 방법은 firebase/README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("com.gradleup.shadow") version "8.3.9"
}

group = "io.github.lhd980820.coup"
version = "0.1.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation("io.github.lhd980820.coup:judge")
    // 서버 클라이언트 라이브러리: 관리자 권한(보안 규칙을 거치지 않는다). FIRESTORE_EMULATOR_HOST가 있으면 에뮬레이터에 붙는다.
    implementation("com.google.cloud:google-cloud-firestore:3.48.0")
    // 함수 프레임워크는 실행 환경이 제공한다
    compileOnly("com.google.cloud.functions:functions-framework-api:2.0.2")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation("com.google.cloud.functions:functions-framework-api:2.0.2")
    testImplementation("io.cloudevents:cloudevents-core:4.0.2")
    testImplementation("io.github.lhd980820.coup:ai")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
    // 에뮬레이터가 없으면 통합 테스트를 건너뛴다. CI(firebase-emulator 작업)는 COUP_REQUIRE_EMULATOR=true로 실패하게 한다.
    systemProperty("coup.requireEmulator", System.getenv("COUP_REQUIRE_EMULATOR") ?: "false")
}

tasks.shadowJar {
    archiveFileName.set("coup-judge.jar")
    // gRPC 등이 META-INF/services로 구현을 찾으므로 합쳐야 한다
    mergeServiceFiles()
}

// 배포 원본 디렉터리: 함수 JAR 하나만 둔다(gcloud functions deploy --source=server/build/deploy)
val stageDeploy by tasks.registering(Copy::class) {
    from(tasks.shadowJar)
    into(layout.buildDirectory.dir("deploy"))
}
