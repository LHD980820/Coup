package io.github.lhd980820.coup.judge

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.Settlement
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.remote.seedStartedRoom
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** 버려진 판 정리(ADR 0016): 진행이 오래 멈춘 판을 끝내고, 레이팅 판이면 방장만 패배로 반영한다. */
class GameSweeperTest {
    private val engine = GameEngines.create()
    private val gameId = "g-sweep"

    private class Table(val fs: FakeFirestore, val host: HostGameSession, val guests: Map<String, RemoteGameSession>)

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private suspend fun TestScope.table(players: List<String>, rated: Boolean = true, id: String = gameId, fs: FakeFirestore? = null): Table {
        // 서버 시각이 0이 아닌 때 시작한다(저장소를 함께 쓰는 두 번째 판부터는 시간을 건너뛰지 않는다)
        if (fs == null) advanceTimeBy(1_000_000)
        val store = fs ?: FakeFirestore().apply { now = { testScheduler.currentTime } }
        players.forEach { FirestoreUserRepository(store.storeFor(it), it, null).ensureProfile("player-$it") }
        val roomId = store.seedStartedRoom(id, players.first(), players, rated)
        val config = BuiltinRules.classicConfig().let { if (rated) it else it.copy(houseRules = setOf("last_stand")) }
        val setup = GameSetup(id, players.map(::PlayerId), config, seed = 9, firstPlayer = PlayerId(players.first()))
        val transport = FirestoreGameTransport(store.storeFor(players.first()), clock())
        val effective = checkNotNull(transport.createGame(id, roomId, players.first(), players, config, rated, setup = setup))
        val seats = players.mapIndexed { i, p -> if (i == 0) HostSeat.Local(PlayerId(p), p) else HostSeat.Remote(PlayerId(p), p) }
        val host = HostGameSession(engine, engine.newGame(effective), seats, transport.host, TimeoutPolicy.None, clock(), backgroundScope)
        val info = seats.associate { it.id to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
        val guests = players.drop(1).associateWith {
            RemoteGameSession(id, PlayerId(it), FirestoreGameTransport(store.storeFor(it), clock()).guest(it), info, backgroundScope)
        }
        runCurrent()
        return Table(store, host, guests)
    }

    private fun TestScope.sweeper(fs: FakeFirestore) = GameSweeper(fs.admin(), engine, clock = clock())

    private fun FakeFirestore.rating(uid: String) = docs.getValue(FirestoreSchema.user(uid))[FirestoreSchema.User.RATING]!!.jsonPrimitive.long.toInt()
    private fun FakeFirestore.game(id: String = gameId) = docs.getValue(FirestoreSchema.game(id))
    private fun FakeFirestore.status(id: String = gameId) = game(id)[FirestoreSchema.Game.STATUS]!!.jsonPrimitive.content
    private fun FakeFirestore.verdict(id: String = gameId) = game(id)[FirestoreSchema.Game.VERDICT]?.jsonPrimitive?.content
    private fun delta(players: Int, rank: Int) = TableRatingPolicy.ratingDeltas(List(players) { PlayerId("#$it") }).getValue(PlayerId("#$rank"))

    /** 방장 앱이 사라진다(생존 신호·권위자가 모두 멈춘다). */
    private fun TestScope.hostVanishes(t: Table) {
        t.host.close()
        runCurrent()
    }

    @Test
    fun `진행이 최근이면 그대로 두고, 오래 멈추면 버려진 판 - 살아 있던 방장만 꼴찌로 반영하고 게스트는 그대로`() = runTest {
        val players = listOf("host", "b", "c")
        val t = table(players)
        t.host.submit(Command.DeclareAction(PlayerId("host"), io.github.lhd980820.coup.engine.model.ActionId("income")))
        runCurrent()
        hostVanishes(t)

        advanceTimeBy(9.minutes.inWholeMilliseconds)
        assertThat(sweeper(t.fs).sweepGame(gameId)).isEqualTo(GameSweep.Active)
        assertThat(t.fs.status()).isEqualTo(FirestoreSchema.GameStatus.PLAYING)

        advanceTimeBy(2.minutes.inWholeMilliseconds)
        val swept = sweeper(t.fs).sweepGame(gameId)
        assertThat(swept).isInstanceOf(GameSweep.Abandoned::class)
        assertThat((swept as GameSweep.Abandoned).hostRank).isEqualTo(2)
        assertThat(swept.applied).isEqualTo(true)
        assertThat(t.fs.rating("host")).isEqualTo(1000 + delta(3, 2))
        assertThat(t.fs.rating("b")).isEqualTo(1000)
        assertThat(t.fs.rating("c")).isEqualTo(1000)
        assertThat(t.fs.status()).isEqualTo(FirestoreSchema.GameStatus.ABANDONED)
        assertThat(t.fs.verdict()).isEqualTo(FirestoreSchema.Verdict.ABANDONED)
        // 방장에게는 반영된 결과, 게스트에게는 "버려진 판"
        assertThat(FirestoreUserRepository(t.fs.storeFor("host"), "host", null).observeSettlement(gameId).first()).isInstanceOf(Settlement.Applied::class)
        assertThat(FirestoreUserRepository(t.fs.storeFor("b"), "b", null).observeSettlement(gameId).first()).isEqualTo(Settlement.Abandoned)

        // 두 번 정리해도 한 번만 반영
        assertThat(sweeper(t.fs).sweepGame(gameId)).isEqualTo(GameSweep.NotPlaying)
        assertThat(t.fs.rating("host")).isEqualTo(1000 + delta(3, 2))
    }

    @Test
    fun `이미 탈락한 방장이 판을 버리면 그 탈락 순위대로, 기록이 조작돼 있으면 꼴찌로`() = runTest {
        val players = listOf("host", "b", "c", "d")
        val t = table(players)
        // b가 먼저 기권(4위), 그다음 방장이 기권(3위) — 방장은 탈락한 채 판을 계속 돌리다 사라진다
        eliminatedHostThenVanishFor(t, gameId)
        val swept = sweeper(t.fs).sweepGame(gameId) as GameSweep.Abandoned
        assertThat(swept.hostRank).isEqualTo(2)
        assertThat(t.fs.rating("host")).isEqualTo(1000 + delta(4, 2))
        listOf("b", "c", "d").forEach { assertThat(t.fs.rating(it)).isEqualTo(1000) }

        // 같은 상황에서 기록 하나를 지워 두면(방장이 손댔다) 다시 돌릴 수 없으므로 꼴찌로 처리한다
        val u = table(players, id = "g-tampered")
        eliminatedHostThenVanishFor(u, "g-tampered")
        u.fs.admin().write(listOf(WriteOp.Delete(FirestoreSchema.logEntry("g-tampered", 1))))
        val tampered = sweeper(u.fs).sweepGame("g-tampered") as GameSweep.Abandoned
        assertThat(tampered.hostRank).isEqualTo(3)
        assertThat(u.fs.rating("host")).isEqualTo(1000 + delta(4, 3))
    }

    private suspend fun TestScope.eliminatedHostThenVanishFor(t: Table, id: String) {
        assertThat(t.guests.getValue("b").submit(Command.Concede(PlayerId("b")))).isEqualTo(SubmitResult.Ok)
        runCurrent()
        assertThat(t.host.submit(Command.Concede(PlayerId("host")))).isEqualTo(SubmitResult.Ok)
        runCurrent()
        assertThat(t.fs.docs.containsKey(FirestoreSchema.logEntry(id, 2))).isEqualTo(true)
        hostVanishes(t)
        advanceTimeBy(11.minutes.inWholeMilliseconds)
    }

    @Test
    fun `기준은 마지막 기록의 서버 시각 - 오래전에 만든 판이라도 최근에 둔 수가 있으면 진행 중`() = runTest {
        val t = table(listOf("host", "b"))
        advanceTimeBy(9.minutes.inWholeMilliseconds)
        t.host.submit(Command.DeclareAction(PlayerId("host"), io.github.lhd980820.coup.engine.model.ActionId("income")))
        runCurrent()
        hostVanishes(t)
        advanceTimeBy(2.minutes.inWholeMilliseconds) // 만든 지 11분, 마지막 수로부터 2분
        assertThat(sweeper(t.fs).sweepGame(gameId)).isEqualTo(GameSweep.Active)
        advanceTimeBy(9.minutes.inWholeMilliseconds)
        assertThat(sweeper(t.fs).sweepGame(gameId)).isInstanceOf(GameSweep.Abandoned::class)
    }

    @Test
    fun `정리하는 순간 방장이 판을 끝내면 끝난 판이 이기고 아무도 반영하지 않는다, 옛 정산 대기는 지운다`() = runTest {
        val t = table(listOf("host", "b"))
        hostVanishes(t)
        advanceTimeBy(11.minutes.inWholeMilliseconds)
        // 정리 트랜잭션이 읽은 뒤 커밋하기 직전에 방장이 결과와 함께 판을 끝낸다(관리자 권한으로 흉내)
        t.fs.beforeNextCommit = {
            t.fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.game(gameId), kotlinx.serialization.json.buildJsonObject {
                put(FirestoreSchema.Game.STATUS, kotlinx.serialization.json.JsonPrimitive(FirestoreSchema.GameStatus.FINISHED))
            })))
        }
        assertThat(sweeper(t.fs).sweepGame(gameId)).isEqualTo(GameSweep.NotPlaying)
        assertThat(t.fs.status()).isEqualTo(FirestoreSchema.GameStatus.FINISHED)
        assertThat(t.fs.rating("host")).isEqualTo(1000)

        val u = table(listOf("host", "b"), id = "g-pending")
        u.fs.admin().write(listOf(WriteOp.Set(FirestoreSchema.pendingRating("host"), kotlinx.serialization.json.buildJsonObject {
            put(FirestoreSchema.Pending.GAME_ID, kotlinx.serialization.json.JsonPrimitive("g-pending"))
        })))
        hostVanishes(u)
        advanceTimeBy(11.minutes.inWholeMilliseconds)
        assertThat(sweeper(u.fs).sweepGame("g-pending")).isInstanceOf(GameSweep.Abandoned::class)
        assertThat(u.fs.docs.containsKey(FirestoreSchema.pendingRating("host"))).isEqualTo(false)
    }

    @Test
    fun `레이팅 없는 판은 버려진 판으로 표시만 한다`() = runTest {
        val t = table(listOf("host", "b"), rated = false)
        hostVanishes(t)
        advanceTimeBy(11.minutes.inWholeMilliseconds)
        assertThat(sweeper(t.fs).sweepGame(gameId)).isEqualTo(GameSweep.Abandoned(rated = false, hostRank = null, applied = false, note = "not rated"))
        assertThat(t.fs.verdict()).isEqualTo(FirestoreSchema.Verdict.UNRATED)
        assertThat(t.fs.status()).isEqualTo(FirestoreSchema.GameStatus.ABANDONED)
        assertThat(t.fs.rating("host")).isEqualTo(1000)
    }

    @Test
    fun `전체 정리 - 멈춘 판만 끝내고, 진행 중인 판과 끝난 판은 건드리지 않는다`() = runTest {
        advanceTimeBy(1_000_000)
        val fs = FakeFirestore().apply { now = { testScheduler.currentTime } }
        val stale = table(listOf("host", "b"), id = "g-stale", fs = fs)
        hostVanishes(stale)
        advanceTimeBy(11.minutes.inWholeMilliseconds)
        val live = table(listOf("h2", "b2"), id = "g-live", fs = fs)
        // 끝난 판: 방장이 기권해 결과를 쓴다
        val done = table(listOf("h3", "b3"), id = "g-done", fs = fs)
        assertThat(done.host.submit(Command.Concede(PlayerId("h3")))).isEqualTo(SubmitResult.Ok)
        runCurrent()
        assertThat(fs.status("g-done")).isEqualTo(FirestoreSchema.GameStatus.FINISHED)

        val report = sweeper(fs).sweepGames()
        assertThat(report.keys).isEqualTo(setOf("g-stale", "g-live"))
        assertThat(report.getValue("g-stale")).isInstanceOf(GameSweep.Abandoned::class)
        assertThat(report.getValue("g-live")).isEqualTo(GameSweep.Active)
        assertThat(fs.status("g-live")).isEqualTo(FirestoreSchema.GameStatus.PLAYING)
        assertThat(sweeper(fs).sweepGame("g-done")).isEqualTo(GameSweep.NotPlaying)
        assertThat(sweeper(fs).sweepGame("nope")).isEqualTo(GameSweep.NotPlaying)
        // 한 판을 읽다가 저장소 오류가 나도 나머지 판은 살핀다
        val flaky = object : io.github.lhd980820.coup.remote.DocumentStore by fs.admin() {
            override suspend fun get(path: String) =
                if (path == FirestoreSchema.game("g-live")) throw io.github.lhd980820.coup.runtime.transport.TransportException("boom") else fs.admin().get(path)
        }
        val withFailure = GameSweeper(flaky, engine, clock = clock()).sweepGames()
        assertThat(withFailure.getValue("g-live")).isInstanceOf(GameSweep.Failed::class)
        live.host.close()
    }
}
