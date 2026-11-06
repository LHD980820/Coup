import java.security.KeyStore
import java.security.MessageDigest
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

/**
 * `./gradlew :app:checkGoogleSignIn` — 이 컴퓨터의 **디버그 빌드에서 구글 로그인이 되는지** 미리 알려 준다.
 * 구글 로그인은 앱의 서명 지문(SHA-1)이 Firebase에 등록돼 있어야 하고, 안 돼 있으면 기기에서 "개발자 구성 오류"(28444)로만 보여서 원인을 찾기 어렵다.
 * 이 작업은 google-services.json에 (1) 웹 클라이언트, (2) 이 앱의 Android 클라이언트(지문)가 있는지, (3) 이 컴퓨터의 디버그 키스토어 SHA-1이 그중에 있는지 본다.
 * 기본은 결과만 알리고 성공으로 끝난다. `-PstrictGoogleSignIn`을 주면 문제가 있을 때 실패한다.
 * 확인용 속성: `-PgoogleServicesFile=<파일>`, `-PdebugKeystore=<디버그 키스토어>`.
 */
tasks.register("checkGoogleSignIn") {
    group = "verification"
    description = "google-services.json이 이 컴퓨터의 디버그 빌드에서 구글 로그인이 되도록 설정됐는지 본다"
    val servicesFile = providers.gradleProperty("googleServicesFile").map { file(it) }.orElse(layout.projectDirectory.file("google-services.json").asFile)
    val keystoreOverride = providers.gradleProperty("debugKeystore").map { file(it) }
    val strict = providers.gradleProperty("strictGoogleSignIn").isPresent
    val packageName = "io.github.lhd980820.coup"
    doLast {
        val problems = mutableListOf<String>()
        val out = StringBuilder("구글 로그인 점검 (패키지 $packageName)\n")
        fun ok(msg: String) = out.append("  [OK] $msg\n")
        fun bad(msg: String) { problems += msg; out.append("  [!!] $msg\n") }

        val file = servicesFile.get()
        if (!file.exists()) {
            bad("google-services.json이 없습니다(${file.path}). Firebase 콘솔 > 프로젝트 설정에서 내려받아 app/ 에 넣으세요.")
        } else {
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val clients = (root["client"] as? List<Map<String, Any?>>).orEmpty()
            val mine = clients.firstOrNull { (((it["client_info"] as? Map<*, *>)?.get("android_client_info")) as? Map<*, *>)?.get("package_name") == packageName }
            if (mine == null) {
                bad("패키지 $packageName 가 google-services.json에 없습니다. Firebase 콘솔에서 이 패키지의 Android 앱을 등록하고 파일을 다시 내려받으세요.")
            } else {
                @Suppress("UNCHECKED_CAST")
                val oauth = (mine["oauth_client"] as? List<Map<String, Any?>>).orEmpty()
                val web = oauth.filter { it["client_type"].toString().removeSuffix(".0") == "3" }
                val android = oauth.filter { it["client_type"].toString().removeSuffix(".0") == "1" }
                if (web.isEmpty()) bad("웹 클라이언트(client_type 3)가 없습니다. Authentication > 로그인 방법에서 Google을 켜세요.") else ok("웹 클라이언트 ID: ${web.first()["client_id"]}")
                val registered = android.mapNotNull { (it["android_info"] as? Map<*, *>)?.get("certificate_hash")?.toString()?.lowercase() }.toSet()
                if (registered.isEmpty()) {
                    bad("이 패키지의 Android 클라이언트(서명 지문)가 하나도 등록되지 않았습니다. 이대로는 기기에서 구글 로그인이 실패합니다(28444 \"Developer console is not set up correctly\").")
                } else {
                    ok("등록된 서명 지문(SHA-1) ${registered.size}개: ${registered.joinToString()}")
                }
                val store = keystoreOverride.orNull
                    ?: File(System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android", "debug.keystore")
                if (!store.exists()) {
                    out.append("  [--] 디버그 키스토어가 아직 없습니다(${store.path}). 디버그 빌드(./gradlew :app:assembleDebug)를 한 번 하면 생기고, 그 뒤 다시 점검하세요.\n")
                } else {
                    val sha1 = try {
                        val ks = KeyStore.getInstance(store, "android".toCharArray())
                        val cert = ks.getCertificate("androiddebugkey")
                        MessageDigest.getInstance("SHA-1").digest(cert.encoded).joinToString("") { "%02x".format(it) }
                    } catch (e: Exception) {
                        null
                    }
                    when {
                        sha1 == null -> out.append("  [--] 디버그 키스토어(${store.path})를 읽지 못했습니다(별칭 androiddebugkey / 비밀번호 android가 아닌 키스토어일 수 있습니다).\n")
                        sha1 in registered -> ok("이 컴퓨터의 디버그 키스토어 SHA-1이 등록돼 있습니다 — 디버그 빌드에서 구글 로그인이 됩니다.")
                        else -> bad(
                            "이 컴퓨터의 디버그 키스토어 SHA-1(${sha1.chunked(2).joinToString(":").uppercase()})이 등록돼 있지 않습니다. " +
                                "Firebase 콘솔 > 프로젝트 설정 > 내 앱 > $packageName 에 이 지문을 추가하고 google-services.json을 다시 내려받으세요.",
                        )
                    }
                }
            }
        }
        out.append(if (problems.isEmpty()) "결과: 문제 없음\n" else "결과: 문제 ${problems.size}건 — 위 [!!] 항목을 고치세요. 자세한 순서: docs/OWNER_CHECKLIST.md 1절\n")
        println(out)
        if (strict && problems.isNotEmpty()) throw GradleException("구글 로그인 설정에 문제가 있습니다(${problems.size}건)")
    }
}
