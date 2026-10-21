package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.GameStartPlan
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.Settlement
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.shell.lobby.GameLauncher
import io.github.lhd980820.coup.shell.lobby.LobbyProblem
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

class WaitingRoomModelTest {
    private suspend fun Fixture.room(host: String, vararg guests: String, max: Int = 4): Room {
        (listOf(host) + guests).forEach { signedUp(it) }
        val room = (roomService(host).createRoom(user(host), "방", Visibility.PUBLIC, max, classic) as RoomResult.Ok).value
        guests.forEach { roomService(it).join(room.id, user(it)) }
        return room
    }

    /** 모델의 방 구독을 시작한다. */
    private fun CoroutineScope.watch(model: WaitingRoomModel): Job = launch { model.run() }

    @Test
    fun `대기실 상태 - 좌석, 내 표시, 방장 표시, 빈 자리, 시작 불가 이유`() = fixture { f ->
        val room = f.room("host", "a")
        val model = f.waitingRoom(room.id, "a")
        watch(model)
        val s = model.state.await { it != null }!!
        assertThat(s.seats.map { it.id }).containsExactly("host", "a")
        assertThat(s.seats.first { it.id == "host" }.isHost).isTrue()
        assertThat(s.seats.first { it.id == "a" }.isMe).isTrue()
        assertThat(s.emptySeats).isEqualTo(2)
        assertThat(s.isHost).isFalse()
        assertThat(s.canStart).isFalse() // 게스트는 시작 버튼이 없다
        assertThat(s.myReady).isFalse()
        assertThat(s.rated).isTrue()

        val host = f.waitingRoom(room.id, "host")
        watch(host)
        val hs = host.state.await { it != null }!!
        assertThat(hs.isHost).isTrue()
        assertThat(hs.blockers).contains(RoomError.NOT_ALL_READY)
        assertThat(hs.canStart).isFalse()
    }

    @Test
    fun `준비 - 게스트가 누르면 방장 화면에서 시작할 수 있게 된다`() = fixture { f ->
        val room = f.room("host", "a")
        val host = f.waitingRoom(room.id, "host")
        val guest = f.waitingRoom(room.id, "a")
        watch(host); watch(guest)
        guest.state.await { it != null }
        guest.toggleReady()
        assertThat(guest.state.await { it?.myReady == true }!!.busy).isFalse()
        val hs = host.state.await { it?.canStart == true }!!
        assertThat(hs.blockers).isEqualTo(emptyList())
        guest.toggleReady()
        host.state.await { it?.canStart == false }
    }

    @Test
    fun `봇 - 추가하면 게스트 준비가 풀리고 방장만 뺄 수 있다`() = fixture { f ->
        val room = f.room("host", "a")
        val host = f.waitingRoom(room.id, "host")
        val guest = f.waitingRoom(room.id, "a")
        watch(host); watch(guest)
        guest.state.await { it != null }
        guest.toggleReady()
        host.state.await { it?.canStart == true }
        host.addBot(BotLevel.EASY)
        val hs = host.state.await { s -> s?.seats?.any { it.kind.name == "BOT" } == true }!!
        assertThat(hs.canStart).isFalse() // 준비가 풀렸다
        assertThat(hs.rated).isFalse()
        val bot = hs.seats.first { it.kind.name == "BOT" }
        assertThat(bot.removable).isTrue()
        assertThat(guest.state.await { it?.seats?.size == 3 }!!.seats.first { it.id == bot.id }.removable).isFalse()
        host.removeBot(bot.id)
        host.state.await { it?.seats?.size == 2 }
    }

    @Test
    fun `게스트 규칙 위반 - 방장 전용 일은 이유를 알려준다`() = fixture { f ->
        val room = f.room("host", "a")
        val guest = f.waitingRoom(room.id, "a")
        watch(guest)
        guest.state.await { it != null }
        guest.addBot()
        assertThat(guest.state.value!!.problem).isEqualTo(LobbyProblem.Room(RoomError.NOT_HOST))
        guest.dismissProblem()
        assertThat(guest.state.value!!.problem).isNull()
    }

    @Test
    fun `내보내기 - 내보내진 게스트는 알림을 받고, 방은 계속된다`() = fixture { f ->
        val room = f.room("host", "a", "b")
        val host = f.waitingRoom(room.id, "host")
        val a = f.waitingRoom(room.id, "a")
        watch(host); val aRun = launch { a.run() }
        a.state.await { it?.seats?.size == 3 }
        host.state.await { it?.seats?.size == 3 }
        host.kick("a")
        assertThat(a.events.first()).isEqualTo(WaitingRoomEvent.Kicked)
        aRun.join() // 내보내지면 구독이 끝난다
        assertThat(host.state.await { it?.seats?.size == 2 }!!.seats.map { it.id }).doesNotContain("a")
    }

    @Test
    fun `나가기 - 게스트가 나가면 자리만 비고, 방장이 나가면 방이 닫힌다`() = fixture { f ->
        val room = f.room("host", "a")
        val host = f.waitingRoom(room.id, "host")
        val a = f.waitingRoom(room.id, "a")
        watch(host); val aRun = launch { a.run() }
        a.state.await { it?.seats?.size == 2 }
        a.leave()
        assertThat(a.events.first()).isEqualTo(WaitingRoomEvent.Left)
        aRun.cancel()
        host.state.await { it?.seats?.size == 1 }

        val b = f.waitingRoom(room.id, "host")
        val bRun = launch { b.run() }
        b.state.await { it != null }
        host.leave()
        assertThat(host.events.first()).isEqualTo(WaitingRoomEvent.Left)
        assertThat(b.events.first()).isEqualTo(WaitingRoomEvent.RoomClosed)
        bRun.join()
    }

    @Test
    fun `시작 - 게임을 열지 못하면 방을 대기실로 되돌리고 알린다`() = fixture { f ->
        val room = f.room("host", "a")
        val failing = object : GameLauncher {
            override suspend fun host(plan: GameStartPlan): GameSession = throw TransportException("offline")
            override suspend fun join(room: Room, me: PlayerId): GameSession = error("unused")
            override suspend fun resumeHost(room: Room): GameSession? = error("unused")
        }
        val host = f.waitingRoom(room.id, "host", launcher = failing)
        val guest = f.waitingRoom(room.id, "a")
        watch(host); watch(guest)
        guest.state.await { it != null }
        guest.toggleReady()
        host.state.await { it?.canStart == true }
        host.start()
        assertThat(host.state.value!!.problem).isEqualTo(LobbyProblem.Network)
        assertThat(f.roomService("host").observe(room.id).first()!!.status).isEqualTo(RoomStatus.WAITING)
        assertThat(host.state.value!!.launching).isFalse()
    }

    @Test
    fun `시작 불가 - 아직 준비하지 않았으면 이유를 알려주고 방은 그대로`() = fixture { f ->
        val room = f.room("host", "a")
        val host = f.waitingRoom(room.id, "host")
        watch(host)
        host.state.await { it != null }
        host.start()
        assertThat(host.state.value!!.problem).isEqualTo(LobbyProblem.Room(RoomError.NOT_ALL_READY))
        assertThat(f.roomService("host").observe(room.id).first()!!.status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `멀티플레이 한 판 - 대기실에서 시작해 세 기기가 끝까지 두고 정산하고 대기실로 돌아온다`() = fixture(timeoutMillis = 60_000) { f ->
        val ids = listOf("host", "a", "b")
        val room = f.room("host", "a", "b", max = 3)
        val models = ids.associateWith { f.waitingRoom(room.id, it) }
        val accounts = ids.associateWith { f.account(it).also { acc -> acc.start() } }
        models.values.forEach { watch(it) }
        models.getValue("a").state.await { it?.seats?.size == 3 }
        models.getValue("a").toggleReady()
        models.getValue("b").toggleReady()
        models.getValue("host").state.await { it?.canStart == true }

        models.getValue("host").start()
        val sessions = ids.associateWith { id ->
            val started = models.getValue(id).events.first()
            assertThat(started).isInstanceOf(WaitingRoomEvent.GameStarted::class)
            (started as WaitingRoomEvent.GameStarted).session
        }
        val gameId = sessions.getValue("host").gameId
        assertThat(sessions.values.map { it.gameId }.toSet()).isEqualTo(setOf(gameId))
        sessions.forEach { (id, session) -> drive(id, session) }

        // 끝까지: 방장 기기의 결과가 나올 때까지
        val result = sessions.getValue("host").snapshot.await(45_000) { it?.view?.result != null }!!.view.result!!
        assertThat(result.ranking.size).isEqualTo(3)

        // 정산: 서버 판정이 기록을 다시 돌려 검증하고 모두의 레이팅에 한 번 반영한다
        f.startJudge()
        ids.forEach { id ->
            accounts.getValue(id).watchSettlement(gameId, retryDelayMillis = 20)
            assertThat(accounts.getValue(id).lastSettlement.await(10_000) { it?.isFinal == true }).isNotNull().isInstanceOf(Settlement.Applied::class)
        }
        val expected = TableRatingPolicy.ratingDeltas(result.ranking)
        ids.forEach { id -> assertThat(accounts.getValue(id).profile.first { it?.plays == 1 }!!.rating).isEqualTo(1000 + expected.getValue(PlayerId(id))) }

        // 대기실로 돌아오면 방장이 방을 다시 대기 상태로 돌리고, 게스트는 다시 준비해야 한다
        sessions.values.forEach { it.close() }
        models.getValue("host").gameFinished()
        assertThat(models.getValue("host").state.value?.problem, name = "대기실 복귀 중 문제").isNull()
        f.roomService("a").observe(room.id).await { it?.status == RoomStatus.WAITING }
        assertThat(models.getValue("a").state.await { it?.myReady == false }!!.myReady).isFalse()
    }

    /** 각 좌석을 무작위 에이전트가 둔다(자기 시점의 뷰와 결정만 보고). */
    private fun CoroutineScope.drive(id: String, session: GameSession) {
        val agent = RandomAgent(id.hashCode().toLong())
        launch {
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
