package io.github.lhd980820.coup.server

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.time.Duration

/** Firestore 에뮬레이터(firebase/firebase.json의 8080)에 서버 클라이언트로 붙는다. 없으면 건너뛰고, `COUP_REQUIRE_EMULATOR=true`면 실패한다. */
internal object EmulatorFirestore {
    const val HOST = "127.0.0.1"
    const val PORT = 8080
    const val PROJECT = "demo-coup"

    /** 문서를 모두 지운 뒤 새 연결을 돌려준다. */
    fun connectFresh(): Firestore {
        val running = runCatching { Socket(HOST, PORT).close() }.isSuccess
        if (System.getProperty("coup.requireEmulator") == "true") check(running) { "Firestore emulator is not running (firebase/rules-test: npm run emulators)" }
        assumeTrue(running, "Firestore emulator is not running")
        clear()
        return FirestoreOptions.newBuilder().setProjectId(PROJECT).setEmulatorHost("$HOST:$PORT").build().service
    }

    private fun clear() {
        val url = URI.create("http://$HOST:$PORT/emulator/v1/projects/$PROJECT/databases/(default)/documents").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = Duration.ofSeconds(5).toMillis().toInt()
        check(conn.responseCode in 200..299) { "could not clear the emulator: ${conn.responseCode}" }
        conn.disconnect()
    }
}
