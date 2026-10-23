package io.github.lhd980820.coup.server

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import io.cloudevents.core.builder.CloudEventBuilder
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.judge.ServerJudge
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.ServerValues
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.time.Duration

/**
 * 서버 판정 함수를 **Firestore 에뮬레이터 + 서버 클라이언트 라이브러리**로 돌린다(ADR 0015).
 * 실제 서버 시각·Timestamp·트랜잭션·쿼리로 한 판을 쓰고, 함수에 Firestore 이벤트를 넣어 레이팅이 반영되는지 본다.
 * 에뮬레이터가 없으면 건너뛴다(`COUP_REQUIRE_EMULATOR=true`면 실패).
 */
class JudgeFunctionTest {
    private lateinit var db: Firestore
    private lateinit var store: AdminDocumentStore
    private val engine = GameEngines.create()

    @BeforeEach
    fun connect() {
        val running = runCatching { Socket(HOST, PORT).close() }.isSuccess
        if (System.getProperty("coup.requireEmulator") == "true") check(running) { "Firestore emulator is not running (firebase/rules-test: npm run emulators)" }
        assumeTrue(running, "Firestore emulator is not running")
        clearEmulator()
        db = FirestoreOptions.newBuilder().setProjectId(PROJECT).setEmulatorHost("$HOST:$PORT").build().service
        store = AdminDocumentStore(db)
    }

    @AfterEach
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun event(subject: String) = CloudEventBuilder.v1()
        .withId("e-$subject")
        .withSource(URI.create("//firestore.googleapis.com/projects/$PROJECT/databases/(default)"))
        .withType("google.cloud.firestore.document.v1.created")
        .withSubject(subject)
        .build()

    private fun function() = JudgeFunction { ServerJudge(store) }

    @Test
    fun `저장소 - 필드 단위 병합, 서버 시각, 트랜잭션, 정렬·조건 쿼리`() = runBlocking<Unit> {
        store.write(listOf(WriteOp.Set("t/a", buildJsonObject { put("n", 1); put("m", buildJsonObject { put("x", 1); put("y", 2) }); put("s", "문자") })))
        // 최상위 필드 통째 교체: m은 {x:9}만 남는다(깊은 병합이 아니다)
        store.write(listOf(WriteOp.Merge("t/a", buildJsonObject { put("m", buildJsonObject { put("x", 9) }); put("at", ServerValues.TIMESTAMP) })))
        val a = store.get("t/a")!!
        assertThat(a["m"].toString()).isEqualTo("""{"x":9}""")
        assertThat(a["n"].toString()).isEqualTo("1")
        assertThat(a.getValue("at").jsonPrimitive.long > 1_600_000_000_000L).isEqualTo(true) // 밀리초
        assertThat(store.get("t/none")).isNull()

        val read = store.transaction { tx ->
            val n = tx.get("t/a")!!.getValue("n").jsonPrimitive.long
            tx.write(WriteOp.Merge("t/a", buildJsonObject { put("n", n + 1) }))
            tx.write(WriteOp.Set("t/b", buildJsonObject { put("n", 5); put("tag", "k") }))
            n
        }
        assertThat(read).isEqualTo(1L)
        assertThat(store.get("t/a")!!["n"].toString()).isEqualTo("2")
        assertThat(store.observeOrdered("t", "n", descending = true, limit = 10).first().map { it.id }).containsExactly("b", "a")
        assertThat(store.observeWhere("t", "tag", "k").first().map { it.id }).containsExactly("b")
        // 트랜잭션 블록의 저장소 오류는 그대로 올라온다
        assertThrows<TransportException> { runBlocking { store.transaction<Unit> { throw TransportException("boom") } } }
        store.write(listOf(WriteOp.Delete("t/a")))
        assertThat(store.get("t/a")).isNull()
    }

    @Test
    fun `한 판 - 결과 이벤트가 오면 검증하고 모두의 레이팅을 한 번 반영한다`() = runBlocking<Unit> {
        val gameId = playGame()
        val ranking = (store.get(FirestoreSchema.result(gameId))!![FirestoreSchema.Result.RANKING] as JsonArray).map { it.jsonPrimitive.content }

        function().accept(event("documents/results/$gameId"))
        assertThat(verdict(gameId)).isEqualTo(FirestoreSchema.Verdict.VERIFIED)
        val expected = TableRatingPolicy.ratingDeltas(ranking.map(::PlayerId))
        PLAYERS.forEach { uid ->
            assertThat(rating(uid), uid).isEqualTo(1000 + expected.getValue(PlayerId(uid)))
            assertThat(store.get(FirestoreSchema.appliedResult(uid, gameId)) != null, uid).isEqualTo(true)
        }
        // 같은 이벤트가 다시 와도(재전송) 두 번 반영하지 않는다
        function().accept(event("documents/results/$gameId"))
        PLAYERS.forEach { uid -> assertThat(rating(uid)).isEqualTo(1000 + expected.getValue(PlayerId(uid))) }
    }

    @Test
    fun `조작된 판 - 결과의 순위를 바꾸면 무효로 표시하고 아무도 반영하지 않는다`() = runBlocking<Unit> {
        val gameId = playGame()
        val ranking = (store.get(FirestoreSchema.result(gameId))!![FirestoreSchema.Result.RANKING] as JsonArray).map { it.jsonPrimitive.content }
        store.write(listOf(WriteOp.Merge(FirestoreSchema.result(gameId), buildJsonObject { put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.reversed().forEach { add(JsonPrimitive(it)) } }) })))
        function().accept(event("documents/results/$gameId"))
        assertThat(verdict(gameId)).isEqualTo(FirestoreSchema.Verdict.REJECTED)
        PLAYERS.forEach { assertThat(rating(it)).isEqualTo(1000) }
    }

    @Test
    fun `다른 문서의 이벤트는 무시하고, 아직 결과가 없으면 재시도하도록 실패한다`() = runBlocking<Unit> {
        function().accept(event("documents/users/u1"))
        assertThat(ResultEvents.gameIdOf("documents/results/g-1")).isEqualTo("g-1")
        assertThat(ResultEvents.gameIdOf("documents/results/g-1/extra")).isNull()
        assertThat(ResultEvents.gameIdOf(null)).isNull()
        assertThrows<IllegalStateException> { function().accept(event("documents/results/missing")) }
    }

    @Test
    fun `기본 생성자는 함수 프레임워크가 쓸 수 있다`() {
        assertThat(JudgeFunction()).isInstanceOf(com.google.cloud.functions.CloudEventsFunction::class)
    }

    // ---- 도우미 -------------------------------------------------------------------------------

    private suspend fun rating(uid: String) = store.get(FirestoreSchema.user(uid))!!.getValue(FirestoreSchema.User.RATING).jsonPrimitive.long.toInt()
    private suspend fun verdict(gameId: String) = store.get(FirestoreSchema.game(gameId))!![FirestoreSchema.Game.VERDICT]?.jsonPrimitive?.content

    /**
     * 방장 권위 세션 + 원격 게스트 둘이 무작위 에이전트로 끝까지 둔다. 모두 관리자 저장소를 쓴다(규칙은 이 테스트의 관심이 아니다 —
     * 규칙은 rules-test와 :data 통합 테스트가 본다). 기록·서버 시각은 실제 Firestore가 매긴다.
     */
    private suspend fun playGame(): String {
        val gameId = "g-${System.nanoTime()}"
        PLAYERS.forEach { FirestoreUserRepository(store, it, null).ensureProfile("player-$it") }
        val setup = GameSetup(gameId, PLAYERS.map(::PlayerId), BuiltinRules.classicConfig(), seed = 21, firstPlayer = PlayerId(PLAYERS.first()))
        val effective = checkNotNull(FirestoreGameTransport(store).createGame(gameId, "room-$gameId", PLAYERS.first(), PLAYERS, BuiltinRules.classicConfig(), rated = true, setup = setup))
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val seats = PLAYERS.mapIndexed { i, id -> if (i == 0) HostSeat.Local(PlayerId(id), id) else HostSeat.Remote(PlayerId(id), id) }
            val host = HostGameSession(engine, engine.newGame(effective), seats, FirestoreGameTransport(store).host, TimeoutPolicy.None, Clock.System, scope)
            val info = seats.associate { it.id to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
            val guests = PLAYERS.drop(1).map { RemoteGameSession(gameId, PlayerId(it), FirestoreGameTransport(store).guest(it), info, scope) }
            (listOf<GameSession>(host) + guests).forEach { drive(it, scope) }
            withTimeout(120_000) { while (store.get(FirestoreSchema.result(gameId)) == null) delay(100) }
        } finally {
            scope.cancel()
        }
        return gameId
    }

    private fun drive(session: GameSession, scope: CoroutineScope) {
        val agent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
        scope.launch {
            var acted = -1L
            session.snapshot.collect { snap ->
                val request = snap?.view?.myDecision ?: return@collect
                if (snap.view.version == acted) return@collect
                acted = snap.view.version
                session.submit(agent.decide(snap.view, request))
            }
        }
    }

    private fun clearEmulator() {
        val url = URI.create("http://$HOST:$PORT/emulator/v1/projects/$PROJECT/databases/(default)/documents").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = Duration.ofSeconds(5).toMillis().toInt()
        check(conn.responseCode in 200..299) { "could not clear the emulator: ${conn.responseCode}" }
        conn.disconnect()
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 8080
        const val PROJECT = "demo-coup"
        val PLAYERS = listOf("host", "b", "c")
    }
}
