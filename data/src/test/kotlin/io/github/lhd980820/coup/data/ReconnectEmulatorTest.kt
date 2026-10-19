package io.github.lhd980820.coup.data

import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.shell.lobby.FirestoreGameLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.random.Random

/** 방장 복구를 실제 Firebase SDK + 에뮬레이터 + 실제 규칙에서(ADR 0012): 권한자 백업 읽기, 같은 버전 다시 게시, 이어서 끝까지. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@Config(sdk = [35])
class ReconnectEmulatorTest {
    private val registry = BuiltinRules.registry()
    private fun rooms(u: TestUser) = RoomService(FirestoreRoomStore(u.store, u.uid), registry, ClientInfo(10, registry), Clock.System, Random(1))
    private fun launcher(u: TestUser, scope: CoroutineScope) = FirestoreGameLauncher(u.store, u.uid, GameEngines.create(), scope, joinPollMillis = 100)

    private fun CoroutineScope.drive(id: String, session: GameSession, untilVersion: Long = Long.MAX_VALUE): Job {
        val agent = RandomAgent(id.hashCode().toLong())
        return launch {
            var acted = -1L
            while (isActive) {
                val snap = session.snapshot.value
                if (snap != null && snap.view.version >= untilVersion) return@launch
                val request = snap?.view?.myDecision
                if (snap != null && request != null && snap.view.version != acted) {
                    acted = snap.view.version
                    if (session.submit(agent.decide(snap.view, request)) !is SubmitResult.Ok) acted = -1
                }
                delay(20)
            }
        }
    }

    @Test
    fun `방장 앱이 꺼졌다가 다시 켜지면 백업에서 이어 가고 끝까지 둔다`() = emulatorTest(timeoutMillis = 240_000) {
        val host = newUser("host")
        val guest = newUser("guest")
        listOf(host to "방장장", guest to "게스트").forEach { (u, n) -> FirestoreUserRepository(u.store, u.uid, u.email).ensureProfile(n) }
        val room = (rooms(host).createRoom(LobbyUser(host.uid, "방장장"), "복구", Visibility.PUBLIC, 2, BuiltinRules.classicConfig()) as RoomResult.Ok).value
        rooms(guest).join(room.id, LobbyUser(guest.uid, "게스트"))
        rooms(guest).setReady(room.id, guest.uid, true)
        val plan = (rooms(host).start(room.id, host.uid, seed = 3) as RoomResult.Ok).value

        val hostDevice = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val guestDevice = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            val hostSession = launcher(host, hostDevice).host(plan)
            val playing = rooms(guest).observe(room.id).first { it?.gameId == plan.gameId }!!
            val guestSession = launcher(guest, guestDevice).join(playing, PlayerId(guest.uid))
            guestDevice.drive(guest.uid, guestSession)
            val hostDriver = hostDevice.drive(host.uid, hostSession, untilVersion = 4)
            withTimeout(60_000) { while (hostDriver.isActive && hostSession.snapshot.value?.view?.result == null) delay(50) }
            if (hostSession.snapshot.value?.view?.result != null) {
                println("RECONNECT_EMU early-finish") // 그 전에 끝났다(드묾) — 이 판은 복구를 시험하지 못한다
                return@emulatorTest
            }
            hostDevice.cancel() // 방장 앱 종료
            delay(500)
            val backupVersion = host.store.get(FirestoreSchema.authority(plan.gameId))!![FirestoreSchema.Authority.VERSION].toString().toLong()

            // 다시 켠다: 새 앱 인스턴스(빈 캐시)에서 같은 계정으로 로그인 -> 복구
            val again = signInAgain(host)
            val restartedDevice = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val resumed = launcher(again, restartedDevice).resumeHost(playing)!!
            val first = withTimeout(10_000) { resumed.snapshot.first { it != null } }!!
            assertTrue("${first.view.version} >= $backupVersion", first.view.version >= backupVersion)
            println("RECONNECT_EMU resumed at ${first.view.version} (backup $backupVersion)")
            restartedDevice.drive(host.uid, resumed)

            val result = withTimeout(150_000) { resumed.snapshot.first { it?.view?.result != null } }!!.view.result!!
            val seen = withTimeout(30_000) { guestSession.snapshot.first { it?.view?.result != null } }!!.view.result!!
            assertEquals(result.ranking, seen.ranking)
            // 끝난 게임은 다시 열지 않는다
            withTimeout(20_000) { while (again.store.get(FirestoreSchema.result(plan.gameId)) == null) delay(100) }
            assertNull(launcher(again, restartedDevice).resumeHost(playing))
            restartedDevice.cancel()
        } finally {
            hostDevice.cancel()
            guestDevice.cancel()
        }
    }
}
