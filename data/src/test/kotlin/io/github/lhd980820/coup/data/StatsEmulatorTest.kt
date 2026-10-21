package io.github.lhd980820.coup.data

import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.shell.stats.HistoryModel
import io.github.lhd980820.coup.shell.stats.MyStanding
import io.github.lhd980820.coup.shell.stats.RankingModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 랭킹·전적 조회를 실제 Firebase SDK + 실제 규칙으로: 정렬 쿼리(레이팅 내림차순), 남의 전적 거부, 모델이 그 위에서 도는지. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@Config(sdk = [35])
class StatsEmulatorTest {
    private fun profile(rating: Int, plays: Int, wins: Int) = buildJsonObject {
        put("nickname", "닉네임"); put("rating", rating); put("plays", plays); put("wins", wins); put("createdAt", 1L); put("migratedFromLegacy", false)
    }

    @Test
    fun `랭킹 - 정렬 쿼리가 실제 규칙을 통과하고 모델이 내 자리를 찾는다`() = emulatorTest {
        val me = newUser("me")
        Emulator.seed("users/x1", profile(1300, 9, 7))
        Emulator.seed("users/x2", profile(1100, 5, 2))
        Emulator.seed("users/x3", profile(1000, 0, 0))
        FirestoreUserRepository(me.store, me.uid, me.email).ensureProfile("나나")
        Emulator.seed("users/${me.uid}", profile(1200, 4, 3))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val model = RankingModel(FirestoreUserRepository(me.store, me.uid, me.email), me.uid)
            scope.launch { model.run() }
            // 첫 값은 로컬 캐시(내 프로필만)일 수 있다 — 서버 결과가 올 때까지 기다린다
            val state = withTimeout(20_000) { model.state.first { it.entries?.size == 3 && it.me != null } }
            assertEquals(listOf("x1", me.uid, "x2"), state.entries!!.map { it.uid }) // 안 한 x3은 빠진다
            assertEquals(listOf(1, 2, 3), state.entries!!.map { it.rank })
            assertEquals(2, (state.me as MyStanding.InTop).entry.rank)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `전적 - 내 기록은 최근 것부터 읽고, 남의 기록은 규칙이 거부한다`() = emulatorTest {
        val me = newUser("me")
        val other = newUser("other")
        FirestoreUserRepository(me.store, me.uid, me.email).ensureProfile("나나")
        fun applied(rank: Int, delta: Int, at: Long) = buildJsonObject { put("rank", rank); put("delta", delta); put("playerCount", 3); put("finishedAt", at); put("appliedAt", at + 1) }
        Emulator.seed("users/${me.uid}/appliedResults/g1", applied(0, 10, 1_000))
        Emulator.seed("users/${me.uid}/appliedResults/g2", applied(2, -6, 2_000))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val model = HistoryModel(FirestoreUserRepository(me.store, me.uid, me.email))
            scope.launch { model.run() }
            val state = withTimeout(20_000) { model.state.first { it.results?.size == 2 && it.profile != null } }
            assertEquals(listOf("g2", "g1"), state.results!!.map { it.gameId })
            assertEquals(4, state.netDelta)
        } finally {
            scope.cancel()
        }
        try {
            other.store.observeOrdered("users/${me.uid}/appliedResults", "appliedAt", descending = true, limit = 10).first()
            fail("남의 전적은 읽을 수 없어야 한다")
        } catch (e: TransportException) {
            assertTrue("PERMISSION_DENIED여야 한다: $e", e is FirestoreFailure)
        }
    }
}
