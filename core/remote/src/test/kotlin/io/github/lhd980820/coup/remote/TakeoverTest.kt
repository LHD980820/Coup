package io.github.lhd980820.coup.remote

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.runtime.transport.Publication
import io.github.lhd980820.coup.runtime.transport.ViewEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** 방장 이어받기(ADR 0017): 방장이 60초 넘게 조용한 진행 중인 판을 좌석의 다른 사람이 이어받는다. 규칙이 막는 것·허용하는 것. */
class TakeoverTest {
    private val fs = FakeFirestore()
    private val engine = GameEngines.create()
    private val gameId = "g-take"
    private val seats = listOf("host", "b", "c")
    private lateinit var roomId: String

    private fun transport(uid: String) = FirestoreGameTransport(fs.storeFor(uid))
    private fun game() = fs.docs.getValue(FirestoreSchema.game(gameId))
    private fun hostOfGame() = game()[FirestoreSchema.Game.HOST_UID]!!.jsonPrimitive.content
    private fun hostOfRoom() = fs.docs.getValue(FirestoreSchema.room(roomId))[FirestoreSchema.Room.HOST_UID]!!.jsonPrimitive.content

    private suspend fun newGame() {
        roomId = fs.seedStartedRoom(gameId, "host", seats, rated = true)
        val setup = GameSetup(gameId, seats.map(::PlayerId), BuiltinRules.classicConfig(), seed = 4, firstPlayer = PlayerId("host"))
        transport("host").createGame(gameId, roomId, "host", seats, BuiltinRules.classicConfig(), rated = true, setup = setup)
    }

    /** 방장이 사라진 지 오래다: 마지막 생존 신호를 아주 옛날로 둔다(관리자 권한 — 실제 규칙 재생에서도 "오래됨"이 되도록 숫자로). */
    private suspend fun hostWentQuiet() {
        fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.game(gameId), buildJsonObject { put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, 1L) })))
    }

    private fun publication(publisher: String): Publication {
        val state = engine.newGame(GameSetup(gameId, seats.map(::PlayerId), BuiltinRules.classicConfig(), seed = 4, firstPlayer = PlayerId("host")))
        val envelope = ViewEnvelope(0, engine.view(state, Viewer.Spectator), emptyList(), emptyMap())
        return Publication(0, emptyMap(), envelope, EngineJson.encodeState(state), publisher = publisher)
    }

    @Test
    fun `방장이 살아 있으면 이어받을 수 없다`() = runBlocking<Unit> {
        newGame() // 생존 신호 = 방금(서버 시각)
        assertThat(transport("b").takeOver(gameId, roomId, "b")).isEqualTo(false)
        assertThat(hostOfGame()).isEqualTo("host")
        assertThat(hostOfRoom()).isEqualTo("host")
    }

    @Test
    fun `방장이 오래 조용하면 좌석의 게스트가 게임과 방의 방장을 함께 이어받고, 이제 그가 백업을 읽고 판을 돌린다`() = runBlocking<Unit> {
        newGame()
        transport("host").host.publish(gameId, publication("host"))
        hostWentQuiet()
        val roomVersion = fs.docs.getValue(FirestoreSchema.room(roomId))[FirestoreSchema.Room.VERSION]!!.jsonPrimitive.content.toLong()

        assertThat(transport("b").takeOver(gameId, roomId, "b")).isEqualTo(true)
        assertThat(hostOfGame()).isEqualTo("b")
        assertThat(hostOfRoom()).isEqualTo("b")
        assertThat(fs.docs.getValue(FirestoreSchema.room(roomId))[FirestoreSchema.Room.VERSION]!!.jsonPrimitive.content.toLong()).isEqualTo(roomVersion + 1)
        // 새 방장은 백업(모든 패)을 읽고 게시할 수 있다. 옛 방장은 이제 못 한다.
        assertThat(fs.storeFor("b").get(FirestoreSchema.authority(gameId))).isNotNull()
        transport("b").host.publish(gameId, publication("b"))
        assertFailure { fs.storeFor("host").get(FirestoreSchema.authority(gameId)) }.isInstanceOf(PermissionDenied::class)
        assertFailure { transport("host").host.publish(gameId, publication("host")) }.isInstanceOf(io.github.lhd980820.coup.runtime.transport.TransportException::class)
        // 새 방장의 생존 신호가 이어받은 순간의 서버 시각이므로, 다른 게스트가 곧바로 다시 가져갈 수 없다
        assertThat(transport("c").takeOver(gameId, roomId, "c")).isEqualTo(false)
        // 옛 방장은 게스트로 돌아와 명령을 보낼 수 있다(방장이 아닌 좌석)
        fs.storeFor("host").write(
            listOf(
                WriteOp.Set(
                    FirestoreSchema.command(gameId, "from-old-host"),
                    buildJsonObject {
                        put(FirestoreSchema.Command.SENDER_UID, "host")
                        put(FirestoreSchema.Command.COMMAND_JSON, "{}")
                        put(FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.PENDING)
                    },
                ),
            ),
        )
        // 이어받으면 현재 방장 구독이 새 방장을 알린다(옛 방장 기기는 이것으로 멈춘다)
        assertThat(transport("host").host.currentHost(gameId).first()).isEqualTo("b")
    }

    @Test
    fun `이어받기 위조 - 좌석 밖 사람, 남을 방장으로, 다른 필드까지, 꾸민 시각, 끝난 판, 기록에 남의 이름`() = runBlocking<Unit> {
        newGame()
        hostWentQuiet()
        fun claim(uid: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            WriteOp.Merge(FirestoreSchema.game(gameId), buildJsonObject(block))
        val now = ServerValues.TIMESTAMP
        fun deny(uid: String, op: WriteOp) = assertFailure { runBlocking { fs.storeFor(uid).write(listOf(op)) } }.isInstanceOf(PermissionDenied::class)
        deny("stranger", claim("stranger") { put(FirestoreSchema.Game.HOST_UID, "stranger"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, now) })
        deny("b", claim("b") { put(FirestoreSchema.Game.HOST_UID, "c"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, now) })
        deny("b", claim("b") { put(FirestoreSchema.Game.HOST_UID, "b"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, now); put(FirestoreSchema.Game.RATED, false) })
        deny("b", claim("b") { put(FirestoreSchema.Game.HOST_UID, "b"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, 99L) })
        deny("b", claim("b") { put(FirestoreSchema.Game.HOST_UID, "b") }) // 생존 신호 없이
        // 방 문서만 먼저(게임 방장이 아직 아니다) / 대기 중인 방
        val roomDoc = fs.docs.getValue(FirestoreSchema.room(roomId))
        val room = checkNotNull(RoomDocuments.decode(roomId, roomDoc))
        deny("b", WriteOp.Set(FirestoreSchema.room(roomId), RoomDocuments.encodeUpdate(roomDoc, room, room.copy(hostUid = "b"), actorUid = "b")))
        // 게임만 이어받은 b가 방을 이어받으면서 다른 것까지 바꾸거나, 방장을 남(c)으로 정할 수 없다
        val gameOnly = claim("b") { put(FirestoreSchema.Game.HOST_UID, "b"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, now) }
        fs.storeFor("b").write(listOf(gameOnly))
        val r0 = fs.docs.getValue(FirestoreSchema.room(roomId))
        val room0 = checkNotNull(RoomDocuments.decode(roomId, r0))
        deny("b", WriteOp.Set(FirestoreSchema.room(roomId), RoomDocuments.encodeUpdate(r0, room0, room0.copy(hostUid = "b", title = "내 방"), actorUid = "b")))
        deny("b", WriteOp.Set(FirestoreSchema.room(roomId), RoomDocuments.encodeUpdate(r0, room0, room0.copy(hostUid = "c"), actorUid = "b")))
        // 정상 이어받기 뒤(방도 넘겨받음), 새 방장의 기록은 자기 이름이어야 한다
        assertThat(transport("b").takeOver(gameId, roomId, "b")).isEqualTo(true)
        assertThat(hostOfRoom()).isEqualTo("b")
        val forgedLog = buildJsonObject {
            put(FirestoreSchema.Log.VERSION, 1); put(FirestoreSchema.Log.AT, ServerValues.TIMESTAMP); put(FirestoreSchema.Log.HOST, "host")
        }
        deny("b", WriteOp.Set(FirestoreSchema.logEntry(gameId, 1), forgedLog))
        // 끝난(버려진) 판은 이어받을 수 없다
        fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.game(gameId), buildJsonObject {
            put(FirestoreSchema.Game.STATUS, FirestoreSchema.GameStatus.ABANDONED); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, 1L)
        })))
        deny("c", claim("c") { put(FirestoreSchema.Game.HOST_UID, "c"); put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, now) })
    }

    @Test
    fun `대기 중인 방의 방장은 이어받을 수 없다`() = runBlocking<Unit> {
        newGame()
        hostWentQuiet()
        assertThat(transport("b").takeOver(gameId, roomId, "b")).isEqualTo(true)
        // 판이 끝나 방이 대기실로 돌아간 뒤(새 방장 b가 돌렸다), c가 방장 자리를 노린다
        val doc = fs.docs.getValue(FirestoreSchema.room(roomId))
        fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.room(roomId), buildJsonObject { put(FirestoreSchema.Room.STATUS, "WAITING"); put(FirestoreSchema.Room.GAME_ID, kotlinx.serialization.json.JsonNull) })))
        val waiting = fs.docs.getValue(FirestoreSchema.room(roomId))
        val room = checkNotNull(RoomDocuments.decode(roomId, waiting))
        assertFailure {
            fs.storeFor("c").write(listOf(WriteOp.Set(FirestoreSchema.room(roomId), RoomDocuments.encodeUpdate(waiting, room, room.copy(hostUid = "c"), actorUid = "c"))))
        }.isInstanceOf(PermissionDenied::class)
        assertThat(doc[FirestoreSchema.Room.HOST_UID]).isEqualTo(JsonPrimitive("b"))
    }

    @Test
    fun `두 게스트가 동시에 이어받으려 하면 정확히 한 명만`() = runBlocking<Unit> {
        newGame()
        hostWentQuiet()
        val results = listOf("b", "c").map { uid -> async { transport(uid).takeOver(gameId, roomId, uid) } }.awaitAll()
        assertThat(results.count { it }).isEqualTo(1)
        val winner = if (results[0]) "b" else "c"
        assertThat(hostOfGame()).isEqualTo(winner)
        assertThat(hostOfRoom()).isEqualTo(winner)
    }
}
