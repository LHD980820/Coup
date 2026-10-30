package io.github.lhd980820.coup.server

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.google.cloud.firestore.Firestore
import io.cloudevents.core.builder.CloudEventBuilder
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.judge.GameSweeper
import io.github.lhd980820.coup.judge.RecordPurger
import io.github.lhd980820.coup.judge.RoomSweeper
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.HostGameSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.random.Random

/** 정리 함수를 Firestore 에뮬레이터 + 서버 클라이언트 라이브러리로 돌린다(ADR 0016). 시계만 한 시간 뒤로 둔다. */
class SweepFunctionTest {
    private lateinit var db: Firestore
    private lateinit var store: AdminDocumentStore
    private val engine = GameEngines.create()
    private val registry = BuiltinRules.registry()

    @BeforeEach
    fun connect() {
        db = EmulatorFirestore.connectFresh()
        store = AdminDocumentStore(db)
    }

    @AfterEach
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun rooms(uid: String) = RoomService(FirestoreRoomStore(store, uid), registry, ClientInfo(10, registry), Clock.System, Random(uid.hashCode()))
    private fun <T> RoomResult<T>.ok(): T = (this as RoomResult.Ok).value

    private val event = CloudEventBuilder.v1()
        .withId("sweep-1")
        .withSource(URI.create("//pubsub.googleapis.com/projects/${EmulatorFirestore.PROJECT}/topics/coup-sweep"))
        .withType("google.cloud.pubsub.topic.v1.messagePublished")
        .build()

    @Test
    fun `한 번 돌면 버려진 판은 방장만 꼴찌로 끝내고, 방장 없는 대기실과 그 판의 방을 닫는다`() = runBlocking<Unit> {
        listOf("host", "b").forEach { FirestoreUserRepository(store, it, null).ensureProfile("player-$it") }
        // 방장이 판을 열고 사라진 레이팅 판
        val room = rooms("host").createRoom(LobbyUser("host", "방장"), "판", Visibility.PUBLIC, 2, BuiltinRules.classicConfig()).ok()
        rooms("b").join(room.id, LobbyUser("b", "비")).ok()
        rooms("b").setReady(room.id, "b", true).ok()
        val plan = rooms("host").start(room.id, "host").ok()
        val setup = FirestoreGameTransport(store).createGame(plan)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val host = HostGameSession(engine, engine.newGame(setup), plan.hostSeats(), FirestoreGameTransport(store).host, TimeoutPolicy.None, Clock.System, scope)
            // 첫 게시(권한자 백업)가 서버에 닿을 때까지 — 그 뒤 방장 앱이 사라진다. 기준 시각은 게임 생성 시각이다(기록이 없다)
            withTimeout(20_000) { while (store.get(FirestoreSchema.authority(plan.gameId)) == null) delay(50) }
            check(host.snapshot.value != null)
        } finally {
            scope.cancel()
        }
        // 방장이 사라진 대기실
        val lonely = rooms("h2").createRoom(LobbyUser("h2", "둘"), "빈 방", Visibility.PUBLIC, 4, BuiltinRules.classicConfig()).ok()

        val later = Clock { System.currentTimeMillis() + 60 * 60_000L }
        SweepFunction { SweepFunction.Sweepers(GameSweeper(store, engine, clock = later), RoomSweeper(store, later), RecordPurger(store, later)) }.accept(event)

        val game = store.get(FirestoreSchema.game(plan.gameId))!!
        assertThat(game[FirestoreSchema.Game.STATUS]!!.jsonPrimitive.content).isEqualTo(FirestoreSchema.GameStatus.ABANDONED)
        assertThat(game[FirestoreSchema.Game.VERDICT]!!.jsonPrimitive.content).isEqualTo(FirestoreSchema.Verdict.ABANDONED)
        assertThat(rating("host")).isEqualTo(1000 - 20) // 2인 판 꼴찌
        assertThat(rating("b")).isEqualTo(1000)
        assertThat(roomStatus(lonely.id)).isEqualTo(RoomStatus.CLOSED.name)
        // 판정 시각은 방금(서버)이지만 시계가 한 시간 뒤이므로 그 판의 방도 같은 실행에서 닫힌다
        assertThat(roomStatus(room.id)).isEqualTo(RoomStatus.CLOSED.name)

        // 한 번 더 돌아도 바뀌는 것이 없다
        SweepFunction { SweepFunction.Sweepers(GameSweeper(store, engine, clock = later), RoomSweeper(store, later), RecordPurger(store, later)) }.accept(event)
        assertThat(rating("host")).isEqualTo(1000 - 20)
    }

    private fun pubsub(body: String?) = CloudEventBuilder.v1()
        .withId("sweep-$body")
        .withSource(URI.create("//pubsub.googleapis.com/projects/${EmulatorFirestore.PROJECT}/topics/coup-sweep"))
        .withType("google.cloud.pubsub.topic.v1.messagePublished")
        .apply {
            if (body != null) {
                val data = java.util.Base64.getEncoder().encodeToString(body.toByteArray())
                withData("application/json", """{"message":{"data":"$data","messageId":"1"},"subscription":"s"}""".toByteArray())
            }
        }
        .build()

    @Test
    fun `메시지 본문이 purge일 때만 기록 정리, 그 밖은 모두 판·방 정리`() {
        assertThat(SweepFunction.task(pubsub("purge"))).isEqualTo(SweepFunction.Task.PURGE)
        assertThat(SweepFunction.task(pubsub(" purge\n"))).isEqualTo(SweepFunction.Task.PURGE)
        assertThat(SweepFunction.task(pubsub("sweep"))).isEqualTo(SweepFunction.Task.SWEEP)
        assertThat(SweepFunction.task(pubsub(null))).isEqualTo(SweepFunction.Task.SWEEP)
        assertThat(SweepFunction.task(pubsub("PURGE-ALL"))).isEqualTo(SweepFunction.Task.SWEEP)
        val garbage = CloudEventBuilder.v1().withId("g").withSource(URI.create("//x")).withType("t").withData("application/json", "not json".toByteArray()).build()
        assertThat(SweepFunction.task(garbage)).isEqualTo(SweepFunction.Task.SWEEP)
    }

    @Test
    fun `하루 한 번 기록 정리 - 버려져 정리된 판과 닫힌 방을 보존 기간 뒤 실제 Firestore에서 지운다`() = runBlocking<Unit> {
        listOf("host", "b").forEach { FirestoreUserRepository(store, it, null).ensureProfile("player-$it") }
        val room = rooms("host").createRoom(LobbyUser("host", "방장"), "판", Visibility.PUBLIC, 2, BuiltinRules.classicConfig()).ok()
        rooms("b").join(room.id, LobbyUser("b", "비")).ok()
        rooms("b").setReady(room.id, "b", true).ok()
        val plan = rooms("host").start(room.id, "host").ok()
        val setup = FirestoreGameTransport(store).createGame(plan)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            HostGameSession(engine, engine.newGame(setup), plan.hostSeats(), FirestoreGameTransport(store).host, TimeoutPolicy.None, Clock.System, scope)
            withTimeout(20_000) { while (store.get(FirestoreSchema.authority(plan.gameId)) == null) delay(50) }
        } finally {
            scope.cancel()
        }
        fun at(offsetMillis: Long) = Clock { System.currentTimeMillis() + offsetMillis }
        fun function(clock: Clock) = SweepFunction { SweepFunction.Sweepers(GameSweeper(store, engine, clock = clock), RoomSweeper(store, clock), RecordPurger(store, clock)) }

        // 몇 분마다 작업: 판을 끝내고 방을 닫는다. 아무것도 지우지 않는다
        function(at(60 * 60_000L)).accept(pubsub("sweep"))
        assertThat(roomStatus(room.id)).isEqualTo(RoomStatus.CLOSED.name)
        val gameRef = db.document(FirestoreSchema.game(plan.gameId))
        assertThat(gameRef.get().get().exists()).isEqualTo(true)

        // 하루 한 번 작업이라도 보존 기간(7일) 안이면 그대로
        function(at(2 * 24 * 3_600_000L)).accept(pubsub("purge"))
        assertThat(gameRef.get().get().exists()).isEqualTo(true)

        // 8일 뒤: 게임 문서와 그 아래 문서(백업·실행 설정·화면), 닫힌 방이 사라진다. 프로필과 방장의 패배 반영 표시는 남는다
        function(at(8 * 24 * 3_600_000L)).accept(pubsub("purge"))
        assertThat(gameRef.get().get().exists()).isEqualTo(false)
        val leftovers = gameRef.listCollections().flatMap { it.listDocuments().filter { d -> d.get().get().exists() } }
        assertThat(leftovers.map { it.path }).isEqualTo(emptyList<String>())
        assertThat(store.get(FirestoreSchema.room(room.id))).isEqualTo(null)
        assertThat(store.get(FirestoreSchema.user("host")) != null).isEqualTo(true)
        assertThat(store.get(FirestoreSchema.appliedResult("host", plan.gameId)) != null).isEqualTo(true)
        assertThat(rating("host")).isEqualTo(1000 - 20)
    }

    @Test
    fun `기본 생성자는 함수 프레임워크가 쓸 수 있다`() {
        assertThat(SweepFunction()).isInstanceOf(com.google.cloud.functions.CloudEventsFunction::class)
    }

    private suspend fun rating(uid: String) = store.get(FirestoreSchema.user(uid))!!.getValue(FirestoreSchema.User.RATING).jsonPrimitive.long.toInt()
    private suspend fun roomStatus(id: String) = store.get(FirestoreSchema.room(id))!!.getValue(FirestoreSchema.Room.STATUS).jsonPrimitive.content
}
