package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.ApplyOutcome
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * 재접속(ADR 0012). "기기"마다 따로 취소할 수 있는 범위를 두고, 범위를 취소해 앱이 꺼진 것을 흉내 낸 뒤
 * 새 모델로 같은 방에 돌아와 게임이 이어지는지 본다. 모든 것은 규칙이 켜진 메모리 Firestore 위에서 돈다.
 */
class ReconnectTest {
    private class Device(val scope: CoroutineScope, val model: WaitingRoomModel, val run: Job)

    private fun Fixture.device(roomId: String, uid: String): Device {
        val scope = device()
        val model = waitingRoom(roomId, uid, launcher = launcher(uid, scope = scope))
        return Device(scope, model, scope.launch { model.run() })
    }

    private suspend fun Fixture.readyRoom(host: String, vararg guests: String): Room {
        (listOf(host) + guests).forEach { signedUp(it) }
        val room = (roomService(host).createRoom(user(host), "방", Visibility.PUBLIC, guests.size + 1, classic) as RoomResult.Ok).value
        guests.forEach {
            roomService(it).join(room.id, user(it))
            roomService(it).setReady(room.id, it, true)
        }
        return room
    }

    private suspend fun started(model: WaitingRoomModel): GameSession {
        val event = withTimeout(10_000) { model.events.first() }
        assertThat(event).isInstanceOf(WaitingRoomEvent.GameStarted::class)
        return (event as WaitingRoomEvent.GameStarted).session
    }

    /**
     * 좌석을 무작위 에이전트가 둔다. 보낸 수가 받아들여지지 않으면(방장이 없는 동안의 시간 초과, 옛 상태) 지금 상태로 다시 판단한다 —
     * 사람이 "연결이 불안정해요"를 보고 다시 누르는 것과 같다.
     */
    private fun CoroutineScope.drive(id: String, session: GameSession, untilVersion: Long = Long.MAX_VALUE): Job {
        val agent = RandomAgent(id.hashCode().toLong())
        return launch {
            var acted = -1L
            while (isActive) {
                val snap = session.snapshot.value
                val request = snap?.view?.myDecision
                if (snap != null && snap.view.version >= untilVersion) return@launch
                if (snap != null && request != null && snap.view.version != acted) {
                    acted = snap.view.version
                    if (session.submit(agent.decide(snap.view, request)) !is SubmitResult.Ok) acted = -1
                }
                delay(5)
            }
        }
    }

    @Test
    fun `방장 앱이 꺼졌다가 다시 켜지면 마지막 게시 상태에서 이어 가고, 끝까지 두고 정산한다`() = fixture(timeoutMillis = 60_000) { f ->
        val room = f.readyRoom("host", "a", "b")
        var host = f.device(room.id, "host")
        val a = f.device(room.id, "a")
        val b = f.device(room.id, "b")
        a.model.state.await { it?.seats?.size == 3 }
        host.model.state.await { it?.canStart == true }
        host.model.start()
        val hostSession = started(host.model)
        val guests = mapOf("a" to started(a.model), "b" to started(b.model))
        guests.forEach { (id, s) -> drive(id, s) }

        // 몇 수 둔 뒤 방장 앱이 꺼진다
        val hostDriver = drive("host", hostSession, untilVersion = 6)
        hostSession.snapshot.await(20_000) { (it?.view?.version ?: 0) >= 6 || it?.view?.result != null }
        hostDriver.cancel()
        if (hostSession.snapshot.value!!.view.result != null) return@fixture // 드물게 그 전에 끝나면 이 시나리오는 의미가 없다
        host.scope.cancel() // 프로세스 종료: 권위자·수신·생존 신호가 모두 멈춘다
        delay(200)
        // 꺼지기 직전까지 게시된 마지막 상태(게스트가 받은 가장 새 버전)
        val publishedBeforeCrash = guests.values.maxOf { it.snapshot.value!!.view.version }

        // 복구 기준점: 권한자 백업은 게스트에게 마지막으로 게시한 상태와 같다(같은 배치로 쓴다)
        val backup = f.store("host").get(io.github.lhd980820.coup.remote.FirestoreSchema.authority(hostSession.gameId))!!
        assertThat(backup["version"].toString().toLong()).isEqualTo(publishedBeforeCrash)

        // 다시 켜서 같은 방으로 돌아오면 저절로 복구된다
        host = f.device(room.id, "host")
        val resumed = started(host.model)
        assertThat(resumed.gameId).isEqualTo(hostSession.gameId)
        // 꺼진 동안 게스트가 보낸 수는 대기열에 남아 있다가 복구 직후 처리될 수 있으므로, 복구된 상태는 기준점이거나 그 뒤다
        val restored = resumed.snapshot.await { it != null }!!
        assertThat(restored.view.version >= publishedBeforeCrash, name = "${restored.view.version} >= $publishedBeforeCrash").isTrue()
        assertThat(host.model.state.value!!.gameInProgress).isTrue()
        drive("host", resumed)

        val result = resumed.snapshot.await(40_000) { it?.view?.result != null }!!.view.result!!
        // 게스트도 같은 결과를 본다(방장이 사라진 동안 보낸 수는 복구 뒤에 처리되거나 거절되고, 게스트는 다시 둔다)
        guests.values.forEach { g -> assertThat(g.snapshot.await(10_000) { it?.view?.result != null }!!.view.result!!.ranking).isEqualTo(result.ranking) }
        val expected = TableRatingPolicy.ratingDeltas(result.ranking)
        listOf("host", "a", "b").forEach { id ->
            val account = f.account(id).also { it.start() } // 정산 대기가 있으면 시작할 때 반영한다
            val outcome = account.settleGame(resumed.gameId, retryDelayMillis = 20)
            assertThat(outcome is ApplyOutcome.Applied || outcome == ApplyOutcome.Skipped(io.github.lhd980820.coup.account.SkipReason.ALREADY_APPLIED), name = "$outcome").isTrue()
            assertThat(account.profile.await { it?.plays == 1 }!!.rating).isEqualTo(1000 + expected.getValue(PlayerId(id)))
        }
    }

    @Test
    fun `게스트 앱이 꺼졌다가 다시 켜지면 같은 게임에 다시 들어가 지금 상태부터 본다`() = fixture(timeoutMillis = 60_000) { f ->
        val room = f.readyRoom("host", "a")
        val host = f.device(room.id, "host")
        var a = f.device(room.id, "a")
        host.model.state.await { it?.canStart == true }
        host.model.start()
        val hostSession = started(host.model)
        val first = started(a.model)
        drive("host", hostSession)
        val firstDriver = drive("a", first, untilVersion = 4)
        first.snapshot.await(20_000) { (it?.view?.version ?: 0) >= 4 || it?.view?.result != null }
        firstDriver.cancel()
        val seenBeforeCrash = first.snapshot.value!!.view.version
        a.scope.cancel()

        a = f.device(room.id, "a")
        val again = started(a.model) // 방이 PLAYING이므로 저절로 다시 들어간다
        assertThat(again.gameId).isEqualTo(hostSession.gameId)
        // 다시 들어오면 지난번에 본 상태 이후의 최신 상태부터 본다(그사이 방장은 계속 둘 수 있다)
        val view = again.snapshot.await { it != null }!!.view
        assertThat(view.version >= seenBeforeCrash, name = "${view.version} >= $seenBeforeCrash").isTrue()
        drive("a", again)
        val result = again.snapshot.await(40_000) { it?.view?.result != null }!!.view.result!!
        assertThat(result.ranking).isEqualTo(hostSession.snapshot.await { it?.view?.result != null }!!.view.result!!.ranking)
    }

    @Test
    fun `게임 화면을 나온 게스트는 대기실에서 다시 들어갈 수 있다`() = fixture { f ->
        val room = f.readyRoom("host", "a")
        val host = f.device(room.id, "host")
        val a = f.device(room.id, "a")
        host.model.state.await { it?.canStart == true }
        host.model.start()
        started(host.model)
        val first = started(a.model)
        first.close() // 방장 연결이 끊긴 줄 알고 나왔다
        assertThat(a.model.state.value!!.gameInProgress).isTrue()
        a.model.rejoin()
        val again = started(a.model)
        assertThat(again.gameId).isEqualTo(first.gameId)
        assertThat(again.snapshot.await { it != null }).isNotNull()
    }

    @Test
    fun `진행 중인 게임이 없으면 다시 들어가기는 아무 일도 하지 않는다`() = fixture { f ->
        val room = f.readyRoom("host", "a")
        val a = f.device(room.id, "a")
        a.model.state.await { it != null }
        assertThat(a.model.state.value!!.gameInProgress).isFalse()
        a.model.rejoin()
        assertThat(kotlinx.coroutines.withTimeoutOrNull(300) { a.model.events.first() }).isNull()
    }

    @Test
    fun `게임이 끝난 뒤 대기실로 돌리기 전에 방장 앱이 꺼졌으면, 다시 켤 때 방을 대기 상태로 돌린다`() = fixture(timeoutMillis = 60_000) { f ->
        val room = f.readyRoom("host", "a")
        var host = f.device(room.id, "host")
        val a = f.device(room.id, "a")
        host.model.state.await { it?.canStart == true }
        host.model.start()
        val hostSession = started(host.model)
        val guest = started(a.model)
        hostSession.concede() // 방장 기권 -> 바로 끝난다
        guest.snapshot.await(10_000) { it?.view?.result != null }
        f.store("host").let { s -> withTimeout(5_000) { while (s.get("results/${hostSession.gameId}") == null) delay(20) } }
        host.scope.cancel() // gameFinished()를 부르기 전에 꺼졌다

        host = f.device(room.id, "host")
        f.roomService("a").observe(room.id).await { it?.status == RoomStatus.WAITING }
        assertThat(host.model.state.await { it?.gameInProgress == false }!!.problem).isNull()
    }

    @Test
    fun `방장이 시작하는 동안 방이 PLAYING으로 보여도 복구로 착각하지 않는다`() = fixture { f ->
        val room = f.readyRoom("host", "a")
        val real = f.launcher("host")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var resumes = 0
        val slow = object : io.github.lhd980820.coup.shell.lobby.GameLauncher by real {
            override suspend fun host(plan: io.github.lhd980820.coup.lobby.GameStartPlan): GameSession {
                gate.await() // 게임 문서를 만들기 전에 방은 이미 PLAYING이다
                return real.host(plan)
            }

            override suspend fun resumeHost(room: Room): GameSession? {
                resumes++
                return real.resumeHost(room)
            }
        }
        // 방 시작 트랜잭션이 커밋된 뒤 응답이 늦게 온다: 그 사이 구독은 이미 PLAYING을 본다(시작한 쪽은 아직 게임 ID를 모른다)
        val inner = f.store("host")
        val lagging = object : io.github.lhd980820.coup.remote.DocumentStore by inner {
            override suspend fun <T> transaction(block: suspend (io.github.lhd980820.coup.remote.DocumentTransaction) -> T): T =
                inner.transaction(block).also { delay(300) }
        }
        val host = f.waitingRoom(room.id, "host", launcher = slow, store = lagging)
        launch { host.run() }
        host.state.await { it?.canStart == true }
        val starting = launch { host.start() }
        f.roomService("a").observe(room.id).await { it?.status == RoomStatus.PLAYING }
        host.state.await { it?.gameInProgress == true } // 방장 모델도 PLAYING을 보았다
        gate.complete(Unit)
        starting.join()
        started(host)
        assertThat(resumes).isEqualTo(0)
        assertThat(host.state.value!!.problem).isNull()
        assertThat(kotlinx.coroutines.withTimeoutOrNull(300) { host.events.first() }).isNull() // 세션은 하나만
    }
}
