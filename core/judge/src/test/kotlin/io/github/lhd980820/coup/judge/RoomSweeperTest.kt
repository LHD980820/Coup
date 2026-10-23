package io.github.lhd980820.coup.judge

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomLiveness
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** 버려진 방 정리(ADR 0016). */
class RoomSweeperTest {
    private var now = 10_000_000L
    private val fs = FakeFirestore().apply { now = { this@RoomSweeperTest.now } }
    private val registry = BuiltinRules.registry()
    private val stale = RoomLiveness.STALE_AFTER_MILLIS

    private fun service(uid: String) = RoomService(FirestoreRoomStore(fs.storeFor(uid), uid), registry, ClientInfo(10, registry), Clock { now }, Random(uid.hashCode()))
    private fun sweeper() = RoomSweeper(fs.admin(), Clock { now })
    private fun <T> RoomResult<T>.ok(): T = (this as RoomResult.Ok).value
    private fun status(id: String) = fs.docs.getValue(FirestoreSchema.room(id))[FirestoreSchema.Room.STATUS]!!.jsonPrimitive.content

    private suspend fun room(visibility: Visibility = Visibility.PUBLIC): Room =
        service("host").createRoom(LobbyUser("host", "방장"), "방", visibility, 4, BuiltinRules.classicConfig()).ok()

    /** 게스트 한 명과 게임을 시작한 방(게임 문서까지). */
    private suspend fun playing(): Pair<Room, String> {
        val r = room()
        service("b").join(r.id, LobbyUser("b", "비")).ok()
        service("b").setReady(r.id, "b", true).ok()
        val plan = service("host").start(r.id, "host").ok()
        FirestoreGameTransport(fs.storeFor("host")).createGame(plan)
        return r to plan.gameId
    }

    @Test
    fun `대기실 - 방장이 알리는 동안은 두고, 오래 조용하면 닫고 참가 코드를 푼다`() = runBlocking<Unit> {
        val r = room(Visibility.PRIVATE)
        val code = checkNotNull(r.joinCode)
        now += stale - 1_000
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.Active)
        service("host").keepAlive(r.id, "host").ok() // 방장이 아직 있다
        now += stale - 1_000
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.Active)
        now += 2_000
        assertThat(sweeper().sweepRoom(r.id)).isInstanceOf(RoomSweep.Closed::class)
        assertThat(status(r.id)).isEqualTo(RoomStatus.CLOSED.name)
        assertThat(fs.docs.containsKey(FirestoreSchema.joinCode(code))).isEqualTo(false)
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.NotOpen)
        assertThat(sweeper().sweepRoom("nope")).isEqualTo(RoomSweep.NotOpen)
    }

    @Test
    fun `정리하는 순간 방장이 알리면 방은 남는다`() = runBlocking<Unit> {
        val r = room()
        now += stale + 1_000
        fs.beforeNextCommit = { service("host").keepAlive(r.id, "host").ok() }
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.Active)
        assertThat(status(r.id)).isEqualTo(RoomStatus.WAITING.name)
    }

    @Test
    fun `게임 중인 방 - 판이 진행 중이면 오래돼도 두고, 판이 끝난 뒤 아무도 돌아오지 않으면 닫는다`() = runBlocking<Unit> {
        val (r, gameId) = playing()
        now += 3 * stale
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.Active) // 진행 중인 판은 게임 정리가 맡는다
        // 판이 끝나고 서버가 판정했다(판정 시각 = 지금)
        fs.admin().write(
            listOf(
                WriteOp.Merge(
                    FirestoreSchema.game(gameId),
                    buildJsonObject {
                        put(FirestoreSchema.Game.STATUS, JsonPrimitive(FirestoreSchema.GameStatus.FINISHED))
                        put(FirestoreSchema.Game.JUDGED_AT, JsonPrimitive(now))
                    },
                ),
            ),
        )
        assertThat(sweeper().sweepRoom(r.id)).isEqualTo(RoomSweep.Active) // 결과 화면을 보는 동안
        now += stale + 1_000
        assertThat(sweeper().sweepRoom(r.id)).isInstanceOf(RoomSweep.Closed::class)
        assertThat(status(r.id)).isEqualTo(RoomStatus.CLOSED.name)
    }

    @Test
    fun `게임 문서 없이 시작만 된 방도 오래되면 닫고, 전체 정리는 열린 방만 본다`() = runBlocking<Unit> {
        val r = room()
        service("b").join(r.id, LobbyUser("b", "비")).ok()
        service("b").setReady(r.id, "b", true).ok()
        service("host").start(r.id, "host").ok() // 방장 앱이 게임 문서를 만들기 전에 꺼졌다
        val fresh = service("h2").createRoom(LobbyUser("h2", "둘"), "새 방", Visibility.PUBLIC, 4, BuiltinRules.classicConfig()).ok()
        val closed = service("h3").createRoom(LobbyUser("h3", "셋"), "닫힌 방", Visibility.PUBLIC, 4, BuiltinRules.classicConfig()).ok()
        service("h3").leave(closed.id, "h3").ok()
        now += stale + 1_000
        service("h2").keepAlive(fresh.id, "h2").ok()

        // 한 방을 읽다가 저장소 오류가 나도 나머지는 정리한다
        val broken = service("h4").createRoom(LobbyUser("h4", "넷"), "고장", Visibility.PUBLIC, 4, BuiltinRules.classicConfig()).ok()
        val flaky = object : io.github.lhd980820.coup.remote.DocumentStore by fs.admin() {
            override suspend fun <T> transaction(block: suspend (io.github.lhd980820.coup.remote.DocumentTransaction) -> T): T = fs.admin().transaction { tx ->
                block(object : io.github.lhd980820.coup.remote.DocumentTransaction by tx {
                    override suspend fun get(path: String) =
                        if (path == FirestoreSchema.room(broken.id)) throw io.github.lhd980820.coup.runtime.transport.TransportException("boom") else tx.get(path)
                })
            }
        }
        val report = sweeper().sweepRooms() - broken.id
        assertThat(report.keys).isEqualTo(setOf(r.id, fresh.id))
        assertThat(report.getValue(r.id)).isEqualTo(RoomSweep.Closed("the game never started"))
        assertThat(report.getValue(fresh.id)).isEqualTo(RoomSweep.Active)
        val withFailure = RoomSweeper(flaky, Clock { now }).sweepRooms()
        assertThat(withFailure.getValue(broken.id)).isInstanceOf(RoomSweep.Failed::class)
        assertThat(withFailure.getValue(fresh.id)).isEqualTo(RoomSweep.Active)
    }
}
