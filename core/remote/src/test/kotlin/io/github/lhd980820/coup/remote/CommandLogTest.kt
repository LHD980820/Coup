package io.github.lhd980820.coup.remote

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.runtime.AppliedCommand
import io.github.lhd980820.coup.runtime.CommandSource
import io.github.lhd980820.coup.runtime.transport.Publication
import io.github.lhd980820.coup.runtime.transport.ViewEnvelope
import io.github.lhd980820.coup.engine.view.Viewer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** 명령 기록과 실행 설정(ADR 0015): 누가 쓰고 읽을 수 있는가, 서버 시각, 고칠 수 없음. */
class CommandLogTest {
    private val fs = FakeFirestore().apply { now = { 1_000_000L } }
    private val engine = GameEngines.create()
    private val gameId = "g-log"
    private val seats = listOf("host", "b")
    private val setup = GameSetup(gameId, seats.map(::PlayerId), BuiltinRules.classicConfig(), seed = 42, firstPlayer = PlayerId("host"))

    private fun transport(uid: String) = FirestoreGameTransport(fs.storeFor(uid))

    private suspend fun newGame() {
        val roomId = fs.seedStartedRoom(gameId, "host", seats, rated = true)
        transport("host").createGame(gameId, roomId, "host", seats, BuiltinRules.classicConfig(), rated = true, setup = setup)
    }

    private fun publication(version: Long, log: List<AppliedCommand>): Publication {
        val state = engine.newGame(setup)
        val envelope = ViewEnvelope(version, engine.view(state, Viewer.Player(PlayerId("b"))), emptyList(), emptyMap())
        return Publication(version, mapOf(PlayerId("b") to envelope), envelope, EngineJson.encodeState(state), log)
    }

    private val income = AppliedCommand(1, Command.DeclareAction(PlayerId("host"), ActionId("income")), CommandSource.SEAT)

    @Test
    fun `실행 설정 - 게임과 함께 만들어지고 방장만 읽으며 고칠 수 없다`() = runBlocking<Unit> {
        newGame()
        val stored = fs.storeFor("host").get(FirestoreSchema.setup(gameId))
        assertThat(stored).isNotNull()
        assertThat(stored!![FirestoreSchema.Setup.SEED].toString()).isEqualTo("42")
        assertFailure { fs.storeFor("b").get(FirestoreSchema.setup(gameId)) }.isInstanceOf(PermissionDenied::class)
        val other = buildJsonObject { put(FirestoreSchema.Setup.SEED, 7) }
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Set(FirestoreSchema.setup(gameId), other))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Delete(FirestoreSchema.setup(gameId)))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `실행 설정 - 게임이 생긴 뒤에는 새로 만들 수 없다(나중에 시드를 고를 수 없다)`() = runBlocking<Unit> {
        val roomId = fs.seedStartedRoom(gameId, "host", seats, rated = true)
        transport("host").createGame(gameId, roomId, "host", seats, BuiltinRules.classicConfig(), rated = true) // 설정 없이
        val late = buildJsonObject { put(FirestoreSchema.Setup.SEED, 99) }
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Set(FirestoreSchema.setup(gameId), late))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `기록 - 게시와 함께 서버 시각으로 남고, 방장만 읽으며, 고칠 수 없다`() = runBlocking<Unit> {
        newGame()
        transport("host").host.publish(gameId, publication(1, listOf(income)))
        val entry = fs.storeFor("host").get(FirestoreSchema.logEntry(gameId, 1))!!
        assertThat(entry[FirestoreSchema.Log.AT].toString()).isEqualTo("1000000")
        assertThat(entry[FirestoreSchema.Log.SOURCE].toString()).isEqualTo("\"SEAT\"")
        assertThat(entry[FirestoreSchema.Log.REF]).isNull()
        assertFailure { fs.storeFor("b").get(FirestoreSchema.logEntry(gameId, 1)) }.isInstanceOf(PermissionDenied::class)
        val changed = buildJsonObject { put(FirestoreSchema.Log.VERSION, 1); put(FirestoreSchema.Log.AT, ServerValues.TIMESTAMP); put(FirestoreSchema.Log.SOURCE, "TIMEOUT") }
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Set(FirestoreSchema.logEntry(gameId, 1), changed))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Delete(FirestoreSchema.logEntry(gameId, 1)))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `기록 - 시각을 꾸미거나 ID와 버전이 다르거나 게스트가 쓰면 거절`() = runBlocking<Unit> {
        newGame()
        val faked = buildJsonObject { put(FirestoreSchema.Log.VERSION, 1); put(FirestoreSchema.Log.AT, 5L) }
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Set(FirestoreSchema.logEntry(gameId, 1), faked))) }.isInstanceOf(PermissionDenied::class)
        val wrongId = buildJsonObject { put(FirestoreSchema.Log.VERSION, 2); put(FirestoreSchema.Log.AT, ServerValues.TIMESTAMP) }
        assertFailure { fs.storeFor("host").write(listOf(WriteOp.Set(FirestoreSchema.logEntry(gameId, 1), wrongId))) }.isInstanceOf(PermissionDenied::class)
        val byGuest = buildJsonObject { put(FirestoreSchema.Log.VERSION, 1); put(FirestoreSchema.Log.AT, ServerValues.TIMESTAMP) }
        assertFailure { fs.storeFor("b").write(listOf(WriteOp.Set(FirestoreSchema.logEntry(gameId, 1), byGuest))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `게시 응답이 유실돼 같은 기록을 다시 보내도, 이미 있는 기록은 빼고 게시한다(처음 시각 유지)`() = runBlocking<Unit> {
        newGame()
        transport("host").host.publish(gameId, publication(1, listOf(income)))
        fs.now = { 2_000_000L }
        // 같은 v1 기록을 다시 담은 게시(앞선 게시가 실패로 보였다) + 새 뷰
        transport("host").host.publish(gameId, publication(1, listOf(income)))
        assertThat(fs.storeFor("host").get(FirestoreSchema.logEntry(gameId, 1))!![FirestoreSchema.Log.AT].toString()).isEqualTo("1000000")
    }
}
