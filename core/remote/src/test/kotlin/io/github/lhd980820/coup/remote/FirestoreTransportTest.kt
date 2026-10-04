package io.github.lhd980820.coup.remote

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.runtime.transport.AckResult
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class FirestoreTransportTest {
    private val engine = GameEngines.create()
    private val gameId = "g1"
    private fun uid(n: Int) = "u$n"
    private fun pid(n: Int) = PlayerId(uid(n))

    private class Table(
        val backend: FakeFirestore,
        val host: HostGameSession,
        val guests: Map<PlayerId, RemoteGameSession>,
        val hostTransport: FirestoreGameTransport,
    ) {
        fun all(): List<GameSession> = listOf<GameSession>(host) + guests.values
    }

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private fun TestScope.table(
        humans: Int,
        seed: Long = 5,
        backend: FakeFirestore = FakeFirestore(),
        initial: GameState? = null,
        policy: TimeoutPolicy = TimeoutPolicy.None,
        createDoc: Boolean = true,
    ): Table {
        val ids = List(humans) { pid(it) }
        val setup = GameSetup(gameId, ids, BuiltinRules.classicConfig(), seed, firstPlayer = ids.first())
        val state = initial ?: engine.newGame(setup)
        val hostTransport = FirestoreGameTransport(backend.storeFor(uid(0)), clock())
        if (createDoc) {
            runCatching {
                // 이미 있는 게임 문서면(호스트 재시작) 만들지 않는다
                if (!backend.docs.containsKey(FirestoreSchema.game(gameId))) {
                    kotlinx.coroutines.runBlocking {
                        hostTransport.createGame(gameId, uid(0), ids.map { it.value }, state.ruleSetConfig, rated = true)
                    }
                }
            }.getOrThrow()
        }
        val seats = ids.mapIndexed { i, id -> if (i == 0) HostSeat.Local(id, "방장") else HostSeat.Remote(id, "게스트$i") }
        val host = HostGameSession(engine, state, seats, hostTransport.host, policy, clock(), backgroundScope)
        val info = seats.associate { it.id to SeatInfo(it.displayName, io.github.lhd980820.coup.runtime.SeatKind.REMOTE_HUMAN) }
        val guests = ids.drop(1).associateWith { id ->
            RemoteGameSession(gameId, id, FirestoreGameTransport(backend.storeFor(id.value), clock()).guest(id.value), info, backgroundScope)
        }
        return Table(backend, host, guests, hostTransport)
    }

    private fun TestScope.drive(sessions: List<GameSession>) {
        sessions.forEach { session ->
            val agent: AiAgent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
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
    }

    private fun TestScope.runUntil(limitMillis: Long = 3_600_000L, done: () -> Boolean) {
        runCurrent()
        val start = testScheduler.currentTime
        while (!done()) {
            check(testScheduler.currentTime - start < limitMillis) { "condition not reached" }
            advanceTimeBy(500)
            runCurrent()
        }
    }

    private fun FakeFirestore.doc(path: String): JsonObject = checkNotNull(docs[path]) { "missing $path" }

    // ---- 전체 흐름 -----------------------------------------------------------------------------

    @Test
    fun `Firestore 위에서 호스트 1 + 게스트 3이 끝까지 게임을 마친다`() = runTest {
        val t = table(humans = 4)
        drive(t.all())
        runUntil { t.guests.values.all { it.snapshot.value?.view?.result != null } }

        val game = t.backend.doc(FirestoreSchema.game(gameId))
        assertThat(game[FirestoreSchema.Game.STATUS]!!.jsonPrimitive.content).isEqualTo(FirestoreSchema.GameStatus.FINISHED)
        val result = t.backend.doc(FirestoreSchema.result(gameId))
        assertThat(result[FirestoreSchema.Result.RATED]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat((result[FirestoreSchema.Result.RANKING] as JsonArray)).hasSize(4)
        val deltas = (result[FirestoreSchema.Result.RATING_DELTAS] as JsonObject).values.map { it.jsonPrimitive.int }
        assertThat(deltas.sortedDescending()).isEqualTo(listOf(70, 30, -30, -50))
        t.guests.values.forEach { assertThat(it.connection.value).isEqualTo(ConnectionState.CLOSED) }
    }

    @Test
    fun `게시는 좌석별 뷰 + 관전 뷰 + 권한자 백업 + 메타를 한 번에 쓰고 백업으로 복구할 수 있다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        assertThat(t.backend.docs.keys.sorted()).isEqualTo(
            listOf(
                FirestoreSchema.authority(gameId),
                FirestoreSchema.game(gameId),
                FirestoreSchema.view(gameId, FirestoreSchema.SPECTATOR_VIEW),
                FirestoreSchema.view(gameId, uid(1)),
                FirestoreSchema.view(gameId, uid(2)),
            ).sorted(),
        )
        // 방장 본인의 뷰는 문서로 나가지 않는다(로컬 세션이 직접 본다)
        assertThat(t.backend.docs.containsKey(FirestoreSchema.view(gameId, uid(0)))).isEqualTo(false)

        val backup = t.backend.doc(FirestoreSchema.authority(gameId))[FirestoreSchema.Authority.BACKUP_JSON]!!.jsonPrimitive.content
        val restored = EngineJson.decodeState(backup)
        assertThat(restored.gameId).isEqualTo(gameId)
        assertThat(restored.version).isEqualTo(0L)
    }

    @Test
    fun `호스트를 저장된 백업에서 복구하면 같은 게임이 이어진다`() = runTest {
        val first = table(humans = 3)
        runCurrent()
        first.host.submit(Command.DeclareAction(pid(0), ActionId("income")))
        runCurrent()
        val backup = first.backend.doc(FirestoreSchema.authority(gameId))[FirestoreSchema.Authority.BACKUP_JSON]!!.jsonPrimitive.content
        val version = first.host.snapshot.value!!.view.version
        first.host.close()

        val second = table(humans = 3, backend = first.backend, initial = EngineJson.decodeState(backup))
        runCurrent()
        assertThat(second.host.snapshot.value!!.view.version).isEqualTo(version) // 복구 직후에는 저장된 시점 그대로
        assertThat(first.guests.getValue(pid(1)).snapshot.value!!.view.version).isEqualTo(version)
        drive(listOf<GameSession>(second.host) + first.guests.values)
        runUntil { first.guests.values.all { it.snapshot.value?.view?.result != null } }
    }

    // ---- 명령 수명주기 -------------------------------------------------------------------------

    @Test
    fun `명령 문서는 PENDING에서 APPLIED(적용 버전 포함) 또는 REJECTED(사유 포함)로 바뀐다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        t.host.submit(Command.DeclareAction(pid(0), ActionId("tax")))
        runCurrent()
        val g1 = t.guests.getValue(pid(1))
        val v = g1.snapshot.value!!.view.version

        assertThat(g1.submit(Command.Pass(pid(1), expectedVersion = v))).isEqualTo(SubmitResult.Ok)
        val applied = t.backend.docs.filterKeys { it.startsWith("games/$gameId/commands/") }.values
            .single { it[FirestoreSchema.Command.STATUS]!!.jsonPrimitive.content == FirestoreSchema.CommandStatus.APPLIED }
        assertThat(applied[FirestoreSchema.Command.SENDER_UID]!!.jsonPrimitive.content).isEqualTo(uid(1))
        assertThat(applied[FirestoreSchema.Command.APPLIED_VERSION]!!.jsonPrimitive.int).isEqualTo((v + 1).toInt())

        // 이미 통과한 사람의 두 번째 통과는 거절된다
        assertThat(g1.submit(Command.Pass(pid(1), expectedVersion = v + 1)))
            .isEqualTo(SubmitResult.Rejected(Rejection.NOT_YOUR_DECISION))
    }

    @Test
    fun `사람 응답자 둘이 동시에 허용을 보내도 모두 적용된다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        t.host.submit(Command.DeclareAction(pid(0), ActionId("tax")))
        runCurrent()
        val v = t.guests.getValue(pid(1)).snapshot.value!!.view.version
        val results = mutableListOf<SubmitResult>()
        t.guests.values.forEach { g -> launch { results += g.submit(Command.Pass(g.me, expectedVersion = v)) } }
        runCurrent()
        assertThat(results).isEqualTo(listOf(SubmitResult.Ok, SubmitResult.Ok))
    }

    @Test
    fun `형식이 잘못된 명령 문서는 방장이 거절 표시하고 게임은 계속된다`() = runTest {
        val t = table(humans = 2)
        runCurrent()
        val rogue = t.backend.storeFor(uid(1))
        rogue.write(
            listOf(
                WriteOp.Set(
                    FirestoreSchema.command(gameId, "bad"),
                    kotlinx.serialization.json.buildJsonObject {
                        put(FirestoreSchema.Command.SENDER_UID, JsonPrimitive(uid(1)))
                        put(FirestoreSchema.Command.COMMAND_JSON, JsonPrimitive("{ this is not a command"))
                        put(FirestoreSchema.Command.CREATED_AT, JsonPrimitive(0))
                        put(FirestoreSchema.Command.STATUS, JsonPrimitive(FirestoreSchema.CommandStatus.PENDING))
                    },
                ),
            ),
        )
        runCurrent()
        assertThat(t.backend.doc(FirestoreSchema.command(gameId, "bad"))[FirestoreSchema.Command.STATUS]!!.jsonPrimitive.content)
            .isEqualTo(FirestoreSchema.CommandStatus.REJECTED)
        assertThat(t.host.submit(Command.DeclareAction(pid(0), ActionId("income")))).isEqualTo(SubmitResult.Ok)
    }

    @Test
    fun `다른 사람 이름으로 보낸 명령 문서는 방장이 거절한다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val guest = FirestoreGameTransport(t.backend.storeFor(uid(1)), clock()).guest(uid(1))
        val ack = guest.sendCommand(gameId, Command.DeclareAction(pid(0), ActionId("income")))
        assertThat(ack).isEqualTo(AckResult.Rejected(Rejection.NOT_YOUR_DECISION))
        assertThat(t.host.snapshot.value!!.view.version).isEqualTo(0L)
    }

    // ---- 접근 규칙 -----------------------------------------------------------------------------

    @Test
    fun `게스트는 다른 좌석의 뷰와 권한자 상태를 읽을 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val g1 = t.backend.storeFor(uid(1))
        assertThrows<PermissionDenied> { g1.observe(FirestoreSchema.view(gameId, uid(2))).first() }
        assertThrows<PermissionDenied> { g1.observe(FirestoreSchema.view(gameId, uid(0))).first() }
        assertThrows<PermissionDenied> { g1.observe(FirestoreSchema.authority(gameId)).first() }
        // 자기 뷰와 관전 뷰는 읽을 수 있다
        assertThat(g1.observe(FirestoreSchema.view(gameId, uid(1))).first()).isNotNull()
        assertThat(g1.observe(FirestoreSchema.view(gameId, FirestoreSchema.SPECTATOR_VIEW)).first()).isNotNull()
    }

    @Test
    fun `게스트는 뷰와 권한자 상태와 게임 문서를 쓸 수 없고 명령 목록을 조회할 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val g1 = t.backend.storeFor(uid(1))
        val junk = JsonObject(mapOf("x" to JsonPrimitive(1)))
        listOf(
            FirestoreSchema.view(gameId, uid(1)),
            FirestoreSchema.view(gameId, uid(2)),
            FirestoreSchema.authority(gameId),
            FirestoreSchema.game(gameId),
            FirestoreSchema.result(gameId),
        ).forEach { path ->
            assertThrows<PermissionDenied> { g1.write(listOf(WriteOp.Set(path, junk))) }
            assertThrows<PermissionDenied> { g1.write(listOf(WriteOp.Merge(path, junk))) }
        }
        assertThrows<PermissionDenied> {
            g1.observeWhere(FirestoreSchema.commands(gameId), FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.PENDING).first()
        }
    }

    @Test
    fun `게스트는 보낸 사람 uid를 속이거나 이미 처리된 상태로 명령을 만들 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val g1 = t.backend.storeFor(uid(1))
        fun command(sender: String, status: String) = WriteOp.Set(
            FirestoreSchema.command(gameId, "x-$sender-$status"),
            JsonObject(
                mapOf(
                    FirestoreSchema.Command.SENDER_UID to JsonPrimitive(sender),
                    FirestoreSchema.Command.COMMAND_JSON to JsonPrimitive("{}"),
                    FirestoreSchema.Command.STATUS to JsonPrimitive(status),
                ),
            ),
        )
        assertThrows<PermissionDenied> { g1.write(listOf(command(uid(2), FirestoreSchema.CommandStatus.PENDING))) }
        assertThrows<PermissionDenied> { g1.write(listOf(command(uid(1), FirestoreSchema.CommandStatus.APPLIED))) }
        g1.write(listOf(command(uid(1), FirestoreSchema.CommandStatus.PENDING))) // 정상
    }

    @Test
    fun `게스트는 남의 명령 문서를 읽거나 자기 명령을 스스로 승인할 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val g1 = t.backend.storeFor(uid(1))
        val g2 = t.backend.storeFor(uid(2))
        val path = FirestoreSchema.command(gameId, "mine")
        g1.write(
            listOf(
                WriteOp.Set(
                    path,
                    JsonObject(
                        mapOf(
                            FirestoreSchema.Command.SENDER_UID to JsonPrimitive(uid(1)),
                            FirestoreSchema.Command.COMMAND_JSON to JsonPrimitive("{}"),
                            FirestoreSchema.Command.STATUS to JsonPrimitive(FirestoreSchema.CommandStatus.PENDING),
                        ),
                    ),
                ),
            ),
        )
        assertThrows<PermissionDenied> { g2.observe(path).first() }
        assertThrows<PermissionDenied> {
            g1.write(listOf(WriteOp.Merge(path, JsonObject(mapOf(FirestoreSchema.Command.STATUS to JsonPrimitive(FirestoreSchema.CommandStatus.APPLIED))))))
        }
    }

    @Test
    fun `게임 문서 생성자는 방장이어야 하고 외부인은 아무것도 읽을 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val outsider = t.backend.storeFor("stranger")
        assertThrows<PermissionDenied> { outsider.observe(FirestoreSchema.game(gameId)).first() }
        assertThrows<PermissionDenied> { outsider.observe(FirestoreSchema.view(gameId, FirestoreSchema.SPECTATOR_VIEW)).first() }
        val forged = FirestoreGameTransport(t.backend.storeFor(uid(1)), clock())
        assertThrows<TransportException> {
            forged.createGame("g2", hostUid = uid(0), seats = listOf(uid(0), uid(1)), ruleSetConfig = BuiltinRules.classicConfig(), rated = true)
        }
    }

    @Test
    fun `저장소 오류는 TransportException으로 통일되어 세션에서 NetworkError가 된다`() = runTest {
        val t = table(humans = 2)
        runCurrent()
        // 게스트의 쓰기가 규칙에 막히는 상황을 만든다: 좌석에 없는 uid로 세션을 만든다
        val stranger = RemoteGameSession(
            gameId, PlayerId("stranger"), FirestoreGameTransport(t.backend.storeFor("stranger"), clock()).guest("stranger"), emptyMap(), backgroundScope,
        )
        assertThat(stranger.submit(Command.Concede(PlayerId("stranger")))).isEqualTo(SubmitResult.NetworkError)
    }

    // ---- 연결 상태 -----------------------------------------------------------------------------

    @Test
    fun `호스트가 살아 있으면 오래 가만히 있어도 연결됨 - 신호가 끊기면 HOST_LOST - 끝나면 CLOSED`() = runTest {
        val t = table(humans = 2)
        val guest = t.guests.getValue(pid(1))
        runCurrent()
        assertThat(guest.connection.value).isEqualTo(ConnectionState.CONNECTED)
        advanceTimeBy(5 * 60_000) // 5분 동안 아무 일도 없다(사람 시간 제한 없음) — 생존 신호가 계속 간다
        runCurrent()
        assertThat(guest.connection.value).isEqualTo(ConnectionState.CONNECTED)

        t.host.close() // 방장이 사라짐 = 신호 중단
        advanceTimeBy(29_000)
        runCurrent()
        assertThat(guest.connection.value).isEqualTo(ConnectionState.CONNECTED)
        advanceTimeBy(8_000)
        runCurrent()
        assertThat(guest.connection.value).isEqualTo(ConnectionState.HOST_LOST)
    }

    @Test
    fun `게스트 뷰 구독은 자기 좌석이 아니면 거절된다`() = runTest {
        val t = table(humans = 3)
        val transport = FirestoreGameTransport(t.backend.storeFor(uid(1)), clock()).guest(uid(1))
        assertThat(runCatching { transport.observeMyView(gameId, pid(2)) }.exceptionOrNull()).isNotNull().isInstanceOf(IllegalArgumentException::class)
        assertThat(t.guests).hasSize(2)
        assertThat(t.hostTransport).isNotNull()
        assertThat(t.backend.docs.isEmpty()).isEqualTo(false)
    }
}
