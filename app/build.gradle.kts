import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

kotlin {
    jvmToolchain(17)
}

/**
 * 릴리스 서명 정보. 저장소 밖의 비밀이다: 루트의 `keystore.properties`(.gitignore, 견본은 `keystore.properties.example`)나
 * 환경 변수(CI: COUP_KEYSTORE_FILE, COUP_KEYSTORE_PASSWORD, COUP_KEY_ALIAS, COUP_KEY_PASSWORD). 파일이 환경 변수보다 우선한다.
 * 아무것도 없으면 서명하지 않는다 — 디버그·테스트·린트는 이것 없이 돈다. 일부만 있으면 조용히 서명 없는 앱이 나오지 않게 빌드를 멈춘다.
 */
val signingKeys = listOf("storeFile" to "COUP_KEYSTORE_FILE", "storePassword" to "COUP_KEYSTORE_PASSWORD", "keyAlias" to "COUP_KEY_ALIAS", "keyPassword" to "COUP_KEY_PASSWORD")
val releaseSigning: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
    signingKeys.mapNotNull { (key, env) -> (props.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
}
val missingSigning = signingKeys.map { it.first }.filter { it !in releaseSigning }
if (releaseSigning.isNotEmpty() && missingSigning.isNotEmpty()) {
    throw GradleException("릴리스 서명 정보가 일부만 있습니다. 빠진 항목: ${missingSigning.joinToString()} (keystore.properties 또는 환경 변수)")
}
val hasReleaseSigning = releaseSigning.isNotEmpty()

android {
    namespace = "io.github.lhd980820.coup"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lhd980820.coup"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric으로 Compose 화면을 JVM에서 실행한다(에뮬레이터 없이 버튼 흐름을 검증)
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 게임 코어(includeBuild("core")): 화면 상태·컨트롤러 + 엔진/AI/런타임
    implementation("io.github.lhd980820.coup:presentation")
    // 로그인·계정·로비·대기실의 화면 상태(코어)와 그 Firebase 어댑터(:data)
    implementation("io.github.lhd980820.coup:shell")
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    // App Check: 정상 앱에서 온 요청만 Firebase가 받게 한다(콘솔에서 "적용"을 켜야 효력이 생긴다 — docs/OWNER_CHECKLIST.md)
    implementation(libs.firebase.appcheck)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    // 새 로그인 화면의 구글 로그인(Credential Manager, ADR 0011)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // 규칙을 강제하는 메모리 속 Firestore: 로그인 -> 로비 -> 대기실 -> 게임 흐름을 에뮬레이터 없이 화면에서 확인한다
    testImplementation(testFixtures("io.github.lhd980820.coup:remote"))
    testImplementation(testFixtures("io.github.lhd980820.coup:judge"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
