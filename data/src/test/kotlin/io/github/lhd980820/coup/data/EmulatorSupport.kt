package io.github.lhd980820.coup.data

import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assume
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Firestore·Auth 에뮬레이터(`firebase/firebase.json`, 실제 `firestore.rules`)에 붙는 테스트 도구.
 * 에뮬레이터가 없으면 테스트를 건너뛴다. `COUP_REQUIRE_EMULATOR=true`(CI)면 건너뛰지 않고 실패한다.
 */
internal object Emulator {
    const val HOST = "127.0.0.1"
    const val FIRESTORE_PORT = 8080
    const val AUTH_PORT = 9099
    const val PROJECT = "demo-coup"

    private fun open(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(HOST, port), 300) } }.isSuccess

    fun requireRunning() {
        val up = open(FIRESTORE_PORT) && open(AUTH_PORT)
        if (System.getProperty("coup.requireEmulator") == "true") {
            check(up) { "Firestore/Auth emulator is not running (firebase/rules-test: npm run emulators)" }
        }
        Assume.assumeTrue("Firestore/Auth emulator is not running", up)
    }

    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build()

    private fun http(method: String, url: String, body: String? = null) {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("Authorization", "Bearer owner") // 에뮬레이터 관리자: 규칙 우회
            .header("Content-Type", "application/json")
            .method(method, if (body == null) java.net.http.HttpRequest.BodyPublishers.noBody() else java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "$method $url -> ${response.statusCode()} ${response.body()}" }
    }

    /** 모든 문서와 계정을 지운다(테스트 사이 격리). */
    fun clear() {
        http("DELETE", "http://$HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT/databases/(default)/documents")
        http("DELETE", "http://$HOST:$AUTH_PORT/emulator/v1/projects/$PROJECT/accounts")
    }

    /** 규칙을 건너뛰고 문서를 쓴다(옛 앱 문서 등 테스트 준비용). */
    fun seed(path: String, data: JsonObject) {
        val encoded = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        http("PATCH", "http://$HOST:$FIRESTORE_PORT/v1/projects/$PROJECT/databases/(default)/documents/$encoded", buildJsonObject { put("fields", typed(data)) }.toString())
    }

    private fun typed(obj: JsonObject): JsonObject = JsonObject(obj.mapValues { (_, v) -> typedValue(v) })

    private fun typedValue(v: JsonElement): JsonObject = when (v) {
        is JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
        is JsonObject -> buildJsonObject { put("mapValue", buildJsonObject { put("fields", typed(v)) }) }
        is JsonArray -> buildJsonObject { put("arrayValue", buildJsonObject { put("values", JsonArray(v.map { typedValue(it) })) }) }
        is JsonPrimitive -> when {
            v.isString -> buildJsonObject { put("stringValue", v.content) }
            v.booleanOrNull != null -> buildJsonObject { put("booleanValue", v.booleanOrNull!!) }
            v.longOrNull != null -> buildJsonObject { put("integerValue", v.content) }
            else -> buildJsonObject { put("doubleValue", v.content.toDouble()) }
        }
    }
}

/** 에뮬레이터 Auth에 가입·로그인한 한 사용자. 사용자마다 별도 FirebaseApp(=별도 인증 상태)을 쓴다. */
internal class TestUser(val uid: String, val email: String, val db: FirebaseFirestore) {
    val store = FirebaseDocumentStore(db)
}

/** 이름이 유일한 FirebaseApp 하나(Auth + Firestore가 에뮬레이터를 가리킨다). */
internal fun newApp(name: String): Pair<FirebaseAuth, FirebaseFirestore> {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val options = FirebaseOptions.Builder().setProjectId(Emulator.PROJECT).setApplicationId("1:1:android:1").setApiKey("fake-api-key").build()
    val app = FirebaseApp.initializeApp(ctx, options, "$name-${UUID.randomUUID()}")
    val auth = FirebaseAuth.getInstance(app).apply { useEmulator(Emulator.HOST, Emulator.AUTH_PORT) }
    val db = FirebaseFirestore.getInstance(app).apply {
        useEmulator(Emulator.HOST, Emulator.FIRESTORE_PORT)
        // 앱과 같은 설정: 메모리 캐시(세션 밖의 오래된 캐시로 판단하지 않게)
        firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
    }
    return auth to db
}

internal suspend fun newUser(name: String): TestUser {
    val (auth, db) = newApp(name)
    val email = "$name-${UUID.randomUUID().toString().take(8)}@coup.test"
    val user = checkNotNull(auth.createUserWithEmailAndPassword(email, "secret-123").await().user)
    return TestUser(user.uid, email, db)
}

/**
 * 에뮬레이터 테스트 본문을 실행한다. 테스트 클래스는 `@LooperMode(INSTRUMENTATION_TEST)`를 써야 한다 — 메인 루퍼가 별도 스레드에서
 * **실제 시간으로** 돌아서(기기의 계측 테스트와 같다) Firebase 콜백·지연이 그대로 동작한다. 기본(PAUSED) 모드의 가상 시계에서는
 * Firebase 콜백과 지연이 제때 돌지 않아 긴 게임이 멈췄다(ADR 0009).
 */
internal fun <T> emulatorTest(timeoutMillis: Long = 120_000, block: suspend CoroutineScope.() -> T): T {
    Emulator.requireRunning()
    Emulator.clear()
    return runBlocking(Dispatchers.IO) { withTimeout(timeoutMillis) { coroutineScope { block() } } }
}
