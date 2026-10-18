plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "io.github.lhd980820.coup.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests {
            // Firestore Android SDK를 Robolectric 위에서 에뮬레이터에 붙여 실행한다(docs/adr/0009)
            isIncludeAndroidResources = true
            all {
                // CI는 COUP_REQUIRE_EMULATOR=true로 돌려서 에뮬레이터가 없으면 건너뛰지 않고 실패하게 한다
                it.systemProperty("coup.requireEmulator", System.getenv("COUP_REQUIRE_EMULATOR") ?: "false")
            }
        }
    }
}

dependencies {
    // Firebase 위의 DocumentStore 구현(:data, 설계 §3.1). 규칙·도메인은 코어(:remote)에 있다.
    api("io.github.lhd980820.coup:remote")

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
