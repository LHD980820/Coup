package io.github.lhd980820.coup.data

import com.google.firebase.firestore.FirebaseFirestoreException
import io.github.lhd980820.coup.account.SkipReason
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.ApplyOutcome
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.random.Random

/**
 * 코어의 저장소·전송·방·레이팅을 **실제 Firebase SDK + 에뮬레이터 + 실제 `firestore.rules`** 위에서 돌린다(ADR 0009).
 * 모델(FakeFirestore) 테스트가 확인한 것을 실제 SDK 동작(배치, 트랜잭션, 리스너, 숫자 형식)으로 다시 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@Config(sdk = [35])
class FirebaseIntegrationTest {
    private val registry = BuiltinRules.registry()
    private val client = ClientInfo(appVersion = 10, registry = registry)

    private fun rooms(u: TestUser, seed: Int = 1) = RoomService(FirestoreRoomStore(u.store, u.uid), registry, client, Clock.System, Random(seed))
    private fun users(u: TestUser) = FirestoreUserRepository(u.store, u.uid, u.email)
    private fun lobbyUser(u: TestUser, name: String) = LobbyUser(u.uid, name)
    private fun <T> RoomResult<T>.ok(): T = (this as? RoomResult.Ok)?.value ?: error("expected Ok but was $this")

    private suspend fun expectDenied(block: suspend () -> Unit) {
        try {
            block()
            fail("규칙이 거절해야 한다")
        } catch (e: TransportException) {
            assertTrue("PERMISSION_DENIED여야 한다: $e", e is FirestoreFailure && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
        }
    }

    @Test
    fun `프로필 - 만들기, 서버에서 읽기, 구독, 남의 문서 거부`() = emulatorTest {
        val a = newUser("a")
        val profile = users(a).ensureProfile("에이")
        assertEquals(1000, profile.rating)
        // get은 서버에서, 정수는 정수로
        val raw = a.store.get(FirestoreSchema.user(a.uid))!!
        assertEquals("1000", raw[FirestoreSchema.User.RATING].toString())
        assertNull(a.store.get(FirestoreSchema.user("nobody")))
        // 구독은 변경을 따라온다
        val seen = async { users(a).observeProfile().first { it?.nickname == "바뀐닉" } }
        users(a).updateNickname("바뀐닉")
        withTimeout(10_000) { seen.await() }
        // 남의 프로필 쓰기는 규칙이 막고, 어댑터는 TransportException으로 알린다
        expectDenied { a.store.write(listOf(WriteOp.Merge(FirestoreSchema.user("someone"), buildJsonObject { put("nickname", "해킹") }))) }
        // 레이팅 직접 수정도
        expectDenied { a.store.write(listOf(WriteOp.Merge(FirestoreSchema.user(a.uid), buildJsonObject { put("rating", 5000) }))) }
    }

    @Test
    fun `옛 계정 이관 - 한 번만`() = emulatorTest {
        val a = newUser("legacy")
        Emulator.seed("user/${a.email}", buildJsonObject { put("nickname", "옛닉"); put("rating", 1234); put("plays", 20) })
        val migrated = users(a).ensureProfile("기본")
        assertEquals(1234, migrated.rating)
        assertEquals("옛닉", migrated.nickname)
        assertTrue(migrated.migratedFromLegacy)
    }

    @Test
    fun `방 - 실제 트랜잭션으로 마지막 자리에 둘이 동시에 들어오면 한 명만`() = emulatorTest {
        val host = newUser("host")
        val b = newUser("b")
        val c = newUser("c")
        val room = rooms(host).createRoom(lobbyUser(host, "방장"), "방", Visibility.PUBLIC, 2, BuiltinRules.classicConfig()).ok()
        val results = listOf(b to "비비", c to "씨씨").map { (u, n) -> async { rooms(u, seed = n.hashCode()).join(room.id, lobbyUser(u, n)) } }.awaitAll()
        assertEquals("정확히 한 명만 들어간다: $results", 1, results.count { it is RoomResult.Ok })
        assertEquals(RoomError.ROOM_FULL, (results.single { it is RoomResult.Err } as RoomResult.Err).error)
        assertEquals(2, rooms(host).observe(room.id).first()!!.seats.size)
        // 공개 목록(쿼리 규칙)도 실제로 읽힌다
        assertEquals(listOf(room.id), rooms(c).listOpen().map { it.id })
    }

    @Test
    fun `방장은 동의하지 않은 사람을 게임 좌석에 넣을 수 없다`() = emulatorTest {
        val host = newUser("host")
        val b = newUser("b")
        val victim = newUser("victim")
        val room = rooms(host).createRoom(lobbyUser(host, "방장"), "방", Visibility.PUBLIC, 3, BuiltinRules.classicConfig()).ok()
        rooms(b).join(room.id, lobbyUser(b, "비")).ok()
        rooms(b).setReady(room.id, b.uid, true).ok()
        val plan = rooms(host).start(room.id, host.uid).ok()
        val transport = FirestoreGameTransport(host.store)
        expectDenied { transport.createGame(plan.gameId, room.id, host.uid, listOf(host.uid, b.uid, victim.uid), plan.ruleSetConfig, rated = true) }
        transport.createGame(plan)
    }

    @Test
    fun `멀티플레이 한 판 - 방, 게임, 좌석별 뷰, 결과, 정산까지 실제 Firebase에서`() = emulatorTest(timeoutMillis = 240_000) {
        val host = newUser("host")
        val b = newUser("b")
        val c = newUser("c")
        listOf(host to "방장", b to "비비", c to "씨씨").forEach { (u, n) -> users(u).ensureProfile(n) }

        // 방: 만들기 -> 입장 -> 준비 -> 시작 -> 게임 문서
        val room = rooms(host).createRoom(lobbyUser(host, "방장"), "한 판", Visibility.PUBLIC, 3, BuiltinRules.classicConfig()).ok()
        rooms(b).join(room.id, lobbyUser(b, "비")).ok()
        rooms(c).join(room.id, lobbyUser(c, "씨")).ok()
        rooms(b).setReady(room.id, b.uid, true).ok()
        rooms(c).setReady(room.id, c.uid, true).ok()
        val plan = rooms(host).start(room.id, host.uid, seed = 7).ok()
        assertTrue(plan.rated)
        FirestoreGameTransport(host.store).createGame(plan)

        // 세션: 방장은 권위자, 게스트는 자기 뷰만 구독
        val engine = GameEngines.create()
        // 세션과 좌석 에이전트는 끝없이 구독하므로 별도 Job에서 돌리고 마지막에 취소한다(안 그러면 테스트 스코프가 끝나지 않는다)
        val sessionScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val hostSession = HostGameSession(engine, engine.newGame(plan.setup()), plan.hostSeats(), FirestoreGameTransport(host.store).host, TimeoutPolicy.None, Clock.System, sessionScope)
        val info = plan.seats.associate { it.playerId to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
        val guests = listOf(b, c).map { u -> RemoteGameSession(plan.gameId, PlayerId(u.uid), FirestoreGameTransport(u.store).guest(u.uid), info, sessionScope) }
        (listOf<GameSession>(hostSession) + guests).forEach { drive(it, sessionScope) }

        withTimeout(200_000) { hostSession.snapshot.first { it?.view?.result != null } }
        val result = hostSession.snapshot.value!!.view.result!!
        assertEquals(3, result.ranking.size)

        // 보안: 게스트는 남의 좌석 뷰를 읽을 수 없다
        expectDenied { b.store.get(FirestoreSchema.view(plan.gameId, c.uid)) }

        // 결과 문서와 정산 대기가 생길 때까지(방장 기기가 쓴다)
        withTimeout(20_000) { while (host.store.get(FirestoreSchema.result(plan.gameId)) == null) delay(100) }
        val expected = TableRatingPolicy.ratingDeltas(result.ranking)
        listOf(host, b, c).forEach { u ->
            assertEquals(plan.gameId, users(u).observePending().first())
            val outcome = users(u).applyResult(plan.gameId)
            assertTrue("반영되어야 한다: $outcome", outcome is ApplyOutcome.Applied)
            assertEquals(1000 + expected.getValue(PlayerId(u.uid)), users(u).observeProfile().first()!!.rating)
            assertNull(users(u).observePending().first())
            // 두 번째 반영은 하지 않는다
            assertEquals(ApplyOutcome.Skipped(SkipReason.ALREADY_APPLIED), users(u).applyResult(plan.gameId))
        }
        // 랭킹(정렬 쿼리)이 실제로 읽힌다
        val top = users(b).observeTop(10).first()
        assertEquals(result.ranking.first().value, top.first().uid)
        // 방은 대기실로 돌아가고 다음 판을 다시 시작할 수 있다(정산을 마쳤으므로)
        rooms(host).endGame(room.id, host.uid).ok()
        hostSession.close()
        guests.forEach { it.close() }
        sessionScope.cancel()
    }

    /** 각 좌석을 무작위 에이전트가 둔다(자기 시점의 뷰와 결정만 보고). */
    private fun drive(session: GameSession, scope: CoroutineScope) {
        val agent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
        scope.launch {
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
