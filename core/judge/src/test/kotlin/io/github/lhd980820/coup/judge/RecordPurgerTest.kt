package io.github.lhd980820.coup.judge

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomLiveness
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.remote.seedStartedRoom
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * 끝난 판·닫힌 방 기록 정리(ADR 0021). 규칙을 강제하는 FakeFirestore 위에서 실제로 한 판을 끝내고 서버가 판정한 뒤,
 * 보존 기간이 지나면 그 판의 문서가 모두 지워지고 전적·프로필은 남는지 본다. 지우면 안 되는 판(진행 중·판정 전·보존 기간 안)도.
 */
class RecordPurgerTest {
    private val engine = GameEngines.create()
    private val uids = listOf("host", "b", "c")
    private val shortLimits = TimeoutPolicy { _, _ -> 10.seconds }

    private val Int.seconds get() = kotlin.time.Duration.parse("${this}s")

    private class Table(val fs: FakeFirestore, val gameId: String)

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    /** 한 판을 끝까지(결과 문서가 생길 때까지) 둔다. */
    private suspend fun TestScope.finishedGame(gameId: String = "g-purge", rated: Boolean = true, fs: FakeFirestore? = null): Table {
        advanceTimeBy(1_000_000)
        val store = fs ?: FakeFirestore().apply { now = { testScheduler.currentTime } }
        uids.forEach { FirestoreUserRepository(store.storeFor(it), it, null).ensureProfile("player-$it") }
        val roomId = store.seedStartedRoom(gameId, uids.first(), uids, rated)
        val config = BuiltinRules.classicConfig().let { if (rated) it else it.copy(houseRules = setOf("last_stand")) }
        val setup = GameSetup(gameId, uids.map(::PlayerId), config, 11, firstPlayer = PlayerId(uids.first()))
        val hostTransport = FirestoreGameTransport(store.storeFor(uids.first()), clock())
        val effective = checkNotNull(hostTransport.createGame(gameId, roomId, uids.first(), uids, config, rated, setup = setup))
        val seats = uids.mapIndexed { i, id -> if (i == 0) HostSeat.Local(PlayerId(id), id) else HostSeat.Remote(PlayerId(id), id) }
        val host = HostGameSession(engine, engine.newGame(effective), seats, hostTransport.host, TimeoutPolicy.None, clock(), backgroundScope)
        val info = seats.associate { it.id to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
        val guests = uids.drop(1).map { id -> RemoteGameSession(gameId, PlayerId(id), FirestoreGameTransport(store.storeFor(id), clock()).guest(id), info, backgroundScope) }
        (listOf<GameSession>(host) + guests).forEach { session ->
            val agent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
            backgroundScope.launch {
                var acted = -1L
                session.snapshot.collect { snap ->
                    val request = snap?.view?.myDecision ?: return@collect
                    if (snap.view.version == acted) return@collect
                    acted = snap.view.version
                    session.submit(agent.decide(snap.view, request))
                }
            }
        }
        runCurrent()
        while (store.docs[FirestoreSchema.result(gameId)] == null) {
            advanceTimeBy(500)
            runCurrent()
        }
        return Table(store, gameId)
    }

    private fun TestScope.judge(t: Table) = ServerJudge(t.fs.admin(), engine, GameVerifier(engine, TimeoutPolicy.None), clock())

    private fun TestScope.purger(t: Table, store: DocumentStore = t.fs.admin(), batchSize: Int = RecordPurger.DEFAULT_BATCH_SIZE, max: Int = RecordPurger.DEFAULT_MAX_GAMES_PER_RUN) =
        RecordPurger(store, clock(), batchSize = batchSize, maxGamesPerRun = max)

    private fun Table.gameDocs() = fs.docs.keys.filter { it == FirestoreSchema.game(gameId) || it.startsWith(FirestoreSchema.game(gameId) + "/") }
    private fun Table.verdict() = fs.docs[FirestoreSchema.game(gameId)]?.get(FirestoreSchema.Game.VERDICT)?.jsonPrimitive?.content

    // ---- 판 ----

    @Test
    fun `판정 뒤 7일이 지나면 그 판의 문서와 결과가 모두 지워지고, 전적과 프로필은 남는다`() = runTest {
        val t = finishedGame()
        judge(t).judge(t.gameId)
        assertThat(t.verdict()).isEqualTo(FirestoreSchema.Verdict.VERIFIED)
        // 화면·백업·기록·명령이 모두 있다
        listOf("views/", "authority/state", "authority/setup", "log/", "commands/").forEach { part ->
            assertThat(t.gameDocs().filter { it.startsWith(FirestoreSchema.game(t.gameId) + "/" + part) }, name = part).isNotEmpty()
        }
        val history = uids.associateWith { FirestoreUserRepository(t.fs.storeFor(it), it, null).observeHistory(10).first() }

        advanceTimeBy(7.days.inWholeMilliseconds - 60_000)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Kept::class) // 아직 7일이 안 됐다
        assertThat(t.gameDocs()).isNotEmpty()

        advanceTimeBy(120_000)
        val outcome = purger(t).purgeGame(t.gameId)
        assertThat(outcome).isInstanceOf(Purge.Purged::class)
        assertThat((outcome as Purge.Purged).deletes).isGreaterThan(10)
        assertThat(t.gameDocs()).isEmpty()
        assertThat(t.fs.docs.containsKey(FirestoreSchema.result(t.gameId))).isEqualTo(false)

        // 전적·프로필은 그대로 — 그 문서만으로 보인다
        uids.forEach { uid ->
            assertThat(t.fs.docs.containsKey(FirestoreSchema.user(uid)), name = uid).isEqualTo(true)
            assertThat(FirestoreUserRepository(t.fs.storeFor(uid), uid, null).observeHistory(10).first(), name = uid).isEqualTo(history.getValue(uid))
            assertThat(history.getValue(uid).size, name = uid).isEqualTo(1)
        }
        // 다시 돌려도 안전하다
        assertThat(purger(t).purgeGame(t.gameId)).isEqualTo(Purge.Gone)
    }

    @Test
    fun `판정이 없는 끝난 판은 두 달이 지나도 지우지 않는다 - 판정 함수가 기록을 읽어야 한다`() = runTest {
        val t = finishedGame()
        advanceTimeBy(60.days.inWholeMilliseconds)
        assertThat(purger(t).purgeGame(t.gameId)).isEqualTo(Purge.Kept("not judged yet"))
        assertThat(t.fs.docs.containsKey(FirestoreSchema.result(t.gameId))).isEqualTo(true)
        // 판정이 나면 그때부터 7일
        judge(t).judge(t.gameId)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Kept::class)
        advanceTimeBy(7.days.inWholeMilliseconds + 1)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Purged::class)
    }

    @Test
    fun `위조로 거부된 판은 조사할 수 있게 30일 남긴다`() = runTest {
        val t = finishedGame()
        val ranking = (t.fs.docs.getValue(FirestoreSchema.result(t.gameId))[FirestoreSchema.Result.RANKING] as JsonArray).map { it.jsonPrimitive.content }
        t.fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.result(t.gameId), buildJsonObject { put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.reversed().forEach { add(JsonPrimitive(it)) } }) })))
        judge(t).judge(t.gameId)
        assertThat(t.verdict()).isEqualTo(FirestoreSchema.Verdict.REJECTED)
        advanceTimeBy(8.days.inWholeMilliseconds)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Kept::class)
        advanceTimeBy(22.days.inWholeMilliseconds)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Purged::class)
        assertThat(t.gameDocs()).isEmpty()
    }

    @Test
    fun `레이팅 없는 판도 판정(UNRATED) 뒤 7일이 지나면 지운다`() = runTest {
        val t = finishedGame(rated = false)
        judge(t).judge(t.gameId)
        assertThat(t.verdict()).isEqualTo(FirestoreSchema.Verdict.UNRATED)
        advanceTimeBy(7.days.inWholeMilliseconds + 1)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Purged::class)
        assertThat(t.gameDocs()).isEmpty()
    }

    @Test
    fun `진행 중인 판은 지우지 않고, 버려져 정리된 판은 7일 뒤 지운다`() = runTest {
        advanceTimeBy(1_000_000)
        val fs = FakeFirestore().apply { now = { testScheduler.currentTime } }
        val roomId = fs.seedStartedRoom("g-stuck", "host", uids, rated = true)
        uids.forEach { FirestoreUserRepository(fs.storeFor(it), it, null).ensureProfile("player-$it") }
        val setup = GameSetup("g-stuck", uids.map(::PlayerId), BuiltinRules.classicConfig(), 3, firstPlayer = PlayerId("host"))
        FirestoreGameTransport(fs.storeFor("host"), clock()).createGame("g-stuck", roomId, "host", uids, BuiltinRules.classicConfig(), rated = true, setup = setup)
        val t = Table(fs, "g-stuck")
        advanceTimeBy(30.days.inWholeMilliseconds)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Kept::class) // 아직 PLAYING
        GameSweeper(fs.admin(), engine, clock = clock()).sweepGame(t.gameId)
        assertThat(t.verdict()).isEqualTo(FirestoreSchema.Verdict.ABANDONED)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Kept::class) // 방금 정리됐다
        advanceTimeBy(7.days.inWholeMilliseconds + 1)
        assertThat(purger(t).purgeGame(t.gameId)).isInstanceOf(Purge.Purged::class)
        assertThat(t.gameDocs()).isEmpty()
        // 방장의 패배 반영 표시(전적)는 남는다
        assertThat(t.fs.docs.containsKey(FirestoreSchema.appliedResult("host", t.gameId))).isEqualTo(true)
    }

    @Test
    fun `기록이 묶음 크기보다 많아도 여러 번에 나눠 모두 지운다`() = runTest {
        val t = finishedGame()
        judge(t).judge(t.gameId)
        val logs = t.gameDocs().count { "/log/" in it }
        assertThat(logs).isGreaterThan(6)
        var writes = 0
        val admin = t.fs.admin()
        val counting = object : DocumentStore by admin {
            override suspend fun write(ops: List<WriteOp>) {
                check(ops.size <= 3) { "batch of ${ops.size}" }
                writes++
                admin.write(ops)
            }
        }
        advanceTimeBy(8.days.inWholeMilliseconds)
        assertThat(purger(t, counting, batchSize = 3).purgeGame(t.gameId)).isInstanceOf(Purge.Purged::class)
        assertThat(t.gameDocs()).isEmpty()
        assertThat(writes).isGreaterThan(logs / 3)
    }

    @Test
    fun `중간에 저장소 오류가 나면 게임 문서가 남아 다음 실행이 마저 지운다`() = runTest {
        val t = finishedGame()
        judge(t).judge(t.gameId)
        advanceTimeBy(8.days.inWholeMilliseconds)
        val admin = t.fs.admin()
        var calls = 0
        val flaky = object : DocumentStore by admin {
            override suspend fun write(ops: List<WriteOp>) {
                if (++calls == 2) throw TransportException("unavailable")
                admin.write(ops)
            }
        }
        val first = purger(t, flaky).purgeGames()
        assertThat(first.getValue(t.gameId)).isInstanceOf(Purge.Failed::class)
        assertThat(t.fs.docs.containsKey(FirestoreSchema.game(t.gameId))).isEqualTo(true) // 표식이 남았다
        assertThat(t.fs.docs.containsKey(FirestoreSchema.result(t.gameId))).isEqualTo(true)
        assertThat(purger(t).purgeGames().getValue(t.gameId)).isInstanceOf(Purge.Purged::class)
        assertThat(t.gameDocs()).isEmpty()
    }

    @Test
    fun `한 번에 지우는 판 수에 상한이 있고 남은 판은 다음 실행에서 지운다`() = runTest {
        val shared = FakeFirestore().apply { now = { testScheduler.currentTime } }
        val ids = listOf("g1", "g2", "g3")
        ids.forEach { id -> finishedGame(id, fs = shared).also { judge(it).judge(id) } }
        advanceTimeBy(8.days.inWholeMilliseconds)
        val t = Table(shared, "g1")
        val first = purger(t, max = 2).purgeGames()
        assertThat(first.values.count { it is Purge.Purged }).isEqualTo(2)
        val second = purger(t, max = 2).purgeGames()
        assertThat(second.values.count { it is Purge.Purged }).isEqualTo(1)
        ids.forEach { id -> assertThat(shared.docs.keys.filter { it.startsWith("games/$id") }, name = id).isEmpty() }
    }

    @Test
    fun `지우는 순간 판정이 바뀌면(판정 시각이 새로워지면) 게임 문서는 남긴다`() = runTest {
        val t = finishedGame()
        judge(t).judge(t.gameId)
        advanceTimeBy(8.days.inWholeMilliseconds)
        t.fs.beforeNextCommit = {
            // 마지막 트랜잭션 직전에 누가 판정 시각을 새로 썼다고 가정
            t.fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.game(t.gameId), buildJsonObject { put(FirestoreSchema.Game.JUDGED_AT, testScheduler.currentTime) })))
        }
        val outcome = purger(t).purgeGame(t.gameId)
        assertThat(outcome).isEqualTo(Purge.Kept("changed while purging"))
        assertThat(t.fs.docs.containsKey(FirestoreSchema.game(t.gameId))).isEqualTo(true)
    }

    // ---- 방 ----

    private var roomNow = 50_000_000L
    private val roomFs = FakeFirestore().apply { now = { roomNow } }
    private val registry = BuiltinRules.registry()
    private fun service(uid: String) = RoomService(FirestoreRoomStore(roomFs.storeFor(uid), uid), registry, ClientInfo(10, registry), Clock { roomNow }, Random(uid.hashCode()))
    private fun roomPurger() = RecordPurger(roomFs.admin(), Clock { roomNow })
    private fun <T> RoomResult<T>.ok(): T = (this as RoomResult.Ok).value

    @Test
    fun `닫힌 방은 7일 뒤 지우고, 열린 방과 최근에 닫힌 방은 둔다`() = runBlocking<Unit> {
        val open = service("a").createRoom(LobbyUser("a", "에이"), "열림", Visibility.PUBLIC, 4, BuiltinRules.classicConfig()).ok()
        val closing = service("h").createRoom(LobbyUser("h", "에이치"), "닫힘", Visibility.PRIVATE, 4, BuiltinRules.classicConfig()).ok()
        val code = checkNotNull(closing.joinCode)
        roomNow += RoomLiveness.STALE_AFTER_MILLIS + 1_000
        service("a").keepAlive(open.id, "a").ok()
        RoomSweeper(roomFs.admin(), Clock { roomNow }).sweepRoom(closing.id) // 닫는다(참가 코드도 푼다)
        assertThat(roomFs.docs.containsKey(FirestoreSchema.joinCode(code))).isEqualTo(false)

        assertThat(roomPurger().purgeRoom(closing.id)).isEqualTo(Purge.Kept("closed recently"))
        roomNow += 7.days.inWholeMilliseconds + 1
        service("a").keepAlive(open.id, "a").ok()
        val outcomes = roomPurger().purgeRooms()
        assertThat(outcomes.keys).isEqualTo(setOf(closing.id)) // 닫힌 방만 살핀다
        assertThat(outcomes.getValue(closing.id)).isEqualTo(Purge.Purged(1))
        assertThat(roomFs.docs.containsKey(FirestoreSchema.room(closing.id))).isEqualTo(false)
        assertThat(roomFs.docs.containsKey(FirestoreSchema.room(open.id))).isEqualTo(true)
        assertThat(roomPurger().purgeRoom(open.id)).isEqualTo(Purge.Kept("open"))
        assertThat(roomPurger().purgeRoom(closing.id)).isEqualTo(Purge.Gone)
    }

    @Test
    fun `닫힌 방에 참가 코드가 남아 있으면 그 방을 가리킬 때만 함께 지운다`() = runBlocking<Unit> {
        val r = service("h").createRoom(LobbyUser("h", "에이치"), "방", Visibility.PRIVATE, 4, BuiltinRules.classicConfig()).ok()
        val code = checkNotNull(r.joinCode)
        val other = service("o").createRoom(LobbyUser("o", "오"), "다른 방", Visibility.PRIVATE, 4, BuiltinRules.classicConfig()).ok()
        val otherCode = checkNotNull(other.joinCode)
        // 닫혔는데 코드 문서가 남은 방(옛 정리 이전 데이터 등)과, 코드가 다른 방으로 넘어간 경우를 만든다
        roomFs.admin().write(
            listOf(
                WriteOp.Merge(FirestoreSchema.room(r.id), buildJsonObject { put(FirestoreSchema.Room.STATUS, "CLOSED") }),
                WriteOp.Merge(FirestoreSchema.room(other.id), buildJsonObject { put(FirestoreSchema.Room.STATUS, "CLOSED"); put(FirestoreSchema.Room.JOIN_CODE, code) }),
            ),
        )
        roomNow += 8.days.inWholeMilliseconds
        assertThat(roomPurger().purgeRoom(other.id)).isEqualTo(Purge.Purged(1)) // 코드 [code]는 r을 가리킨다 — 남긴다
        assertThat(roomFs.docs.containsKey(FirestoreSchema.joinCode(code))).isEqualTo(true)
        assertThat(roomPurger().purgeRoom(r.id)).isEqualTo(Purge.Purged(2)) // 이제 r과 그 코드
        assertThat(roomFs.docs.containsKey(FirestoreSchema.joinCode(code))).isEqualTo(false)
        assertThat(roomFs.docs.containsKey(FirestoreSchema.joinCode(otherCode))).isEqualTo(true) // 아무도 안 지운 코드
    }

    @Test
    fun `설정 검사 - 묶음 크기는 Firestore 한도 안이어야 한다`() {
        assertThat(runCatching { RecordPurger(FakeFirestore().admin(), batchSize = 501) }.isFailure).isEqualTo(true)
        assertThat(runCatching { RecordPurger(FakeFirestore().admin(), batchSize = 0) }.isFailure).isEqualTo(true)
        assertThat(runCatching { RecordPurger(FakeFirestore().admin(), maxGamesPerRun = 0) }.isFailure).isEqualTo(true)
        assertThat(RecordPurger.DEFAULT_KEEP_REJECTED > RecordPurger.DEFAULT_KEEP_JUDGED).isEqualTo(true)
        assertThat(RecordPurger.DEFAULT_KEEP_JUDGED >= 1.days).isEqualTo(true)
        assertThat(RecordPurger.DEFAULT_KEEP_JUDGED > GameSweeper.DEFAULT_ABANDON_AFTER + 15.minutes + 1.hours).isEqualTo(true)
    }
}
