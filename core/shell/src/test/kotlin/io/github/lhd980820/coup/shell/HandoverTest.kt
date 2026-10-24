package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.judge.GameVerifier
import io.github.lhd980820.coup.judge.ServerJudge
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.shell.lobby.FirestoreGameLauncher
import io.github.lhd980820.coup.shell.lobby.HandoverSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 방장 이어받기(ADR 0017)를 끝까지: 규칙이 켜진 메모리 Firestore 위에서 방장이 사라지거나 잠깐 끊기면 게스트가 이어받아 판을 끝내고,
 * 서버 판정이 방장이 바뀐 기록을 검증해 모두의 레이팅을 반영한다. 서버 시각과 기기 시계는 함께 61초 건너뛴다(규칙의 "60초 조용함").
 */
class HandoverTest {
    /** 사람 결정의 시간 제한(짧게). 서버 판정도 같은 정책으로 검증한다. */
    private val shortLimits = TimeoutPolicy { _, _ -> 400.milliseconds }

    @Volatile
    private var offset = 0L
    private val clock = Clock { System.currentTimeMillis() + offset }

    private fun Fixture.launcher(uid: String, scope: CoroutineScope, store: DocumentStore = store(uid)) = FirestoreGameLauncher(
        store, uid, GameEngines.create(), scope, clock = clock, timeoutPolicy = shortLimits, joinPollMillis = 20, joinGiveUpMillis = 5_000,
        connectionTick = 100.milliseconds, takeoverAfter = 200.milliseconds, takeoverStagger = 1.seconds, takeoverRetry = 100.milliseconds,
    )

    private suspend fun Fixture.readyRoom(): Room {
        listOf("host", "a", "b").forEach { signedUp(it) }
        val room = (roomService("host").createRoom(user("host"), "방", Visibility.PUBLIC, 3, classic) as RoomResult.Ok).value
        listOf("a", "b").forEach {
            roomService(it).join(room.id, user(it))
            roomService(it).setReady(room.id, it, true)
        }
        return room
    }

    /** 좌석을 무작위 에이전트가 둔다(세션이 안에서 방장·게스트로 바뀌어도 같은 세션을 계속 본다). */
    private fun CoroutineScope.drive(id: String, session: GameSession): Job {
        val agent = RandomAgent(id.hashCode().toLong())
        return launch {
            var acted = -1L
            while (isActive) {
                val snap = session.snapshot.value
                val request = snap?.view?.myDecision
                if (snap != null && request != null && snap.view.version != acted) {
                    acted = snap.view.version
                    if (session.submit(agent.decide(snap.view, request)) !is SubmitResult.Ok) acted = -1
                }
                delay(5)
            }
        }
    }

    private fun Fixture.gameHost(gameId: String) = fs.docs.getValue(FirestoreSchema.game(gameId))[FirestoreSchema.Game.HOST_UID]!!.jsonPrimitive.content
    private fun Fixture.roomHost(roomId: String) = fs.docs.getValue(FirestoreSchema.room(roomId))[FirestoreSchema.Room.HOST_UID]!!.jsonPrimitive.content
    private fun Fixture.rating(uid: String) = fs.docs.getValue(FirestoreSchema.user(uid))[FirestoreSchema.User.RATING]!!.jsonPrimitive.long.toInt()

    private suspend fun Fixture.until(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val ok = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { while (!condition()) delay(20); true }
        if (ok == null) {
            val games = fs.docs.filterKeys { it.startsWith("games/") && it.count { c -> c == '/' } == 1 }.values.map { g ->
                listOf(FirestoreSchema.Game.HOST_UID, FirestoreSchema.Game.STATUS, FirestoreSchema.Game.VERSION, FirestoreSchema.Game.HOST_HEARTBEAT_AT).associateWith { g[it] }
            }
            throw AssertionError("condition not reached in ${timeoutMillis}ms; games=$games offsetNow=${clock.nowMillis()}")
        }
    }

    /** 결과가 나오면 서버가 검증해 모두의 레이팅을 표대로 반영했는지(방장이 바뀐 기록도 검증된다). */
    private suspend fun Fixture.verifiedAndRated(gameId: String) {
        until(60_000) { fs.docs[FirestoreSchema.result(gameId)] != null }
        until { fs.docs.getValue(FirestoreSchema.game(gameId))[FirestoreSchema.Game.VERDICT] != null }
        assertThat(fs.docs.getValue(FirestoreSchema.game(gameId))[FirestoreSchema.Game.VERDICT]!!.jsonPrimitive.content, name = "판정")
            .isEqualTo(FirestoreSchema.Verdict.VERIFIED)
        val ranking = (fs.docs.getValue(FirestoreSchema.result(gameId))[FirestoreSchema.Result.RANKING] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
        val expected = TableRatingPolicy.ratingDeltas(ranking.map(::PlayerId))
        listOf("host", "a", "b").forEach { assertThat(rating(it), name = it).isEqualTo(1000 + expected.getValue(PlayerId(it))) }
    }

    @Test
    fun `방장 앱이 사라지면 첫 게스트가 이어받아 끝까지 두고, 서버가 방장이 바뀐 기록을 검증한다`() = fixture(timeoutMillis = 120_000) { f ->
        f.fs.now = { clock.nowMillis() }
        f.startJudge(ServerJudge(f.fs.admin(), verifier = GameVerifier(GameEngines.create(), shortLimits)))
        val room = f.readyRoom()
        val hostDevice = f.device()
        val plan = (f.roomService("host").start(room.id, "host") as RoomResult.Ok).value
        val host = f.launcher("host", hostDevice).host(plan)
        val joined = checkNotNull(f.roomService("a").get(room.id))
        val a = f.launcher("a", this).join(joined, PlayerId("a"))
        val b = f.launcher("b", this).join(joined, PlayerId("b"))
        hostDevice.drive("host", host)
        drive("a", a)
        drive("b", b)
        f.until { (host.snapshot.value?.view?.version ?: 0) >= 4 || host.snapshot.value?.view?.result != null }
        if (host.snapshot.value!!.view.result != null) return@fixture // 드물게 그 전에 끝나면 이 시나리오는 의미가 없다

        hostDevice.cancel() // 방장 앱이 사라졌다
        hostDevice.coroutineContext[Job]!!.join() // 그 순간 나가던 쓰기(생존 신호 등)까지 끝난 뒤에 시간을 건너뛴다
        offset += 61_000 // 서버 시각·기기 시계 모두 61초 뒤

        // 첫 게스트(a)가 게임과 방의 방장을 이어받는다
        f.until { f.gameHost(plan.gameId) == "a" }
        assertThat(f.roomHost(room.id)).isEqualTo("a")
        f.until { (a as HandoverSession).isHosting }
        assertThat((b as HandoverSession).isHosting).isEqualTo(false)
        f.until { b.connection.value == ConnectionState.CONNECTED } // 새 방장의 생존 신호를 받는다
        // 옛 방장의 좌석은 시간 초과로 두어지며 판이 끝나고, 서버가 검증해 모두의 레이팅을 반영한다
        f.verifiedAndRated(plan.gameId)
    }

    @Test
    fun `방장이 잠깐 끊긴 사이 이어받히면, 돌아온 옛 방장은 게스트로 같은 판을 이어 간다`() = fixture(timeoutMillis = 120_000) { f ->
        f.fs.now = { clock.nowMillis() }
        f.startJudge(ServerJudge(f.fs.admin(), verifier = GameVerifier(GameEngines.create(), shortLimits)))
        val room = f.readyRoom()
        val hostStore = FlakyStore(f.store("host"))
        val plan = (f.roomService("host").start(room.id, "host") as RoomResult.Ok).value
        val host = f.launcher("host", this, hostStore).host(plan)
        val joined = checkNotNull(f.roomService("a").get(room.id))
        val a = f.launcher("a", this).join(joined, PlayerId("a"))
        val b = f.launcher("b", this).join(joined, PlayerId("b"))
        drive("host", host)
        drive("a", a)
        drive("b", b)
        f.until { (host.snapshot.value?.view?.version ?: 0) >= 4 || host.snapshot.value?.view?.result != null }
        if (host.snapshot.value!!.view.result != null) return@fixture

        hostStore.fail = true // 방장의 쓰기가 닿지 않는다(구독은 살아 있다)
        delay(300) // 막기 직전에 이미 나간 쓰기가 끝난 뒤에 시간을 건너뛴다
        offset += 61_000
        f.until { f.gameHost(plan.gameId) == "a" }
        hostStore.fail = false // 방장이 돌아왔다 — 이미 a가 이어받았다

        // 옛 방장 기기는 방장이 아님을 알고 게스트로 다시 붙는다(같은 세션 객체 — 화면은 그대로)
        f.until { !(host as HandoverSession).isHosting && host.connection.value == ConnectionState.CONNECTED }
        assertThat((a as HandoverSession).isHosting).isTrue()
        f.verifiedAndRated(plan.gameId)
    }
}
