package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.shell.stats.HistoryModel
import io.github.lhd980820.coup.shell.stats.MyStanding
import io.github.lhd980820.coup.shell.stats.RankingModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class StatsModelsTest {
    /** 관리자 권한으로 프로필을 직접 심는다(레이팅을 만들려고 게임을 두지 않는다). */
    private suspend fun Fixture.seedProfile(uid: String, nickname: String, rating: Int, plays: Int, wins: Int) {
        fs.admin().write(
            listOf(
                WriteOp.Set(
                    "users/$uid",
                    buildJsonObject {
                        put("nickname", nickname); put("rating", rating); put("plays", plays); put("wins", wins)
                        put("createdAt", 1L); put("migratedFromLegacy", false)
                    },
                ),
            ),
        )
    }

    @Test
    fun `랭킹 - 레이팅 순으로 세우고 같은 레이팅은 공동 순위, 한 판도 안 한 사람은 뺀다`() = fixture { f ->
        f.seedProfile("a", "에이", 1200, 10, 6)
        f.seedProfile("b", "비", 1100, 8, 3)
        f.seedProfile("c", "씨", 1100, 4, 1)
        f.seedProfile("d", "디", 1000, 0, 0) // 안 한 사람
        f.signedUp("me")
        val model = RankingModel(f.users("me"), "me")
        launch { model.run() }
        val s = model.state.await { it.entries != null }
        assertThat(s.entries!!.map { it.uid }).isEqualTo(listOf("a", "b", "c"))
        assertThat(s.entries.map { it.rank }).isEqualTo(listOf(1, 2, 2))
        assertThat(s.me is MyStanding.Unranked).isTrue() // 나는 아직 안 했다
    }

    @Test
    fun `랭킹 - 내가 상위에 있으면 그 줄이고, 밖이면 내 프로필만`() = fixture { f ->
        f.seedProfile("a", "에이", 1500, 20, 15)
        f.seedProfile("b", "비", 1400, 20, 12)
        f.seedProfile("me", "나", 900, 5, 1)
        val inTop = RankingModel(f.users("me"), "me", limit = 10)
        launch { inTop.run() }
        val s1 = inTop.state.await { it.me != null }
        assertThat(s1.me is MyStanding.InTop).isTrue()
        assertThat((s1.me as MyStanding.InTop).entry.rank).isEqualTo(3)

        val below = RankingModel(f.users("me"), "me", limit = 2) // 상위 2명만 본다
        launch { below.run() }
        val s2 = below.state.await { it.me != null }
        assertThat(s2.entries!!.map { it.uid }).isEqualTo(listOf("a", "b"))
        assertThat(s2.me is MyStanding.BelowTop).isTrue()
        assertThat((s2.me as MyStanding.BelowTop).profile.rating).isEqualTo(900)
    }

    @Test
    fun `랭킹 - 레이팅이 바뀌면 화면 상태가 따라 바뀐다`() = fixture { f ->
        f.seedProfile("a", "에이", 1200, 10, 6)
        f.signedUp("me")
        val model = RankingModel(f.users("me"), "me")
        launch { model.run() }
        model.state.await { it.entries != null }
        f.seedProfile("me", "나", 1300, 3, 3)
        val s = model.state.await { it.me is MyStanding.InTop }
        assertThat(s.entries!!.first().uid).isEqualTo("me")
        assertThat((s.me as MyStanding.InTop).entry.rank).isEqualTo(1)
    }

    @Test
    fun `랭킹 - 읽기가 실패하면 오프라인으로 표시하고 받은 목록은 두고, 돌아오면 다시 구독한다`() = fixture { f ->
        f.seedProfile("a", "에이", 1200, 10, 6)
        f.signedUp("me")
        val flaky = FlakyObserveStore(f.store("me"))
        val model = RankingModel(f.users("me", flaky), "me", retryMillis = 20)
        launch { model.run() }
        model.state.await { it.entries != null }
        flaky.breakNext = true
        f.seedProfile("b", "비", 1100, 2, 1) // 변경이 구독을 깨운다
        val offline = model.state.await { it.offline }
        assertThat(offline.entries).isNotNull() // 이미 받은 목록은 그대로
        val back = model.state.await { !it.offline && it.entries?.any { e -> e.uid == "b" } == true }
        assertThat(back.offline).isFalse()
    }

    @Test
    fun `전적 - 최근 것부터, 승률과 레이팅 변화 합을 계산한다`() = fixture { f ->
        f.seedProfile("me", "나", 1010, 4, 1)
        val rows = listOf(
            Triple("g1", 0, 12), // 1위
            Triple("g2", 2, -8),
            Triple("g3", 1, -2),
            Triple("g4", 3, -9),
        )
        rows.forEachIndexed { i, (id, rank, delta) ->
            f.fs.admin().write(
                listOf(
                    WriteOp.Set(
                        "users/me/appliedResults/$id",
                        buildJsonObject { put("rank", rank); put("delta", delta); put("playerCount", 4); put("finishedAt", 1000L + i); put("appliedAt", 2000L + i) },
                    ),
                ),
            )
        }
        val model = HistoryModel(f.users("me"))
        launch { model.run() }
        val s = model.state.await { it.results != null && it.profile != null }
        assertThat(s.results!!.map { it.gameId }).isEqualTo(listOf("g4", "g3", "g2", "g1"))
        assertThat(s.netDelta).isEqualTo(12 - 8 - 2 - 9)
        assertThat(s.winRate).isEqualTo(0.25)
    }

    @Test
    fun `전적 - 기록이 없으면 빈 목록, 승률은 없음`() = fixture { f ->
        f.signedUp("me")
        val model = HistoryModel(f.users("me"))
        launch { model.run() }
        val s = model.state.await { it.results != null && it.profile != null }
        assertThat(s.results!!.isEmpty()).isTrue()
        assertThat(s.winRate).isNull()
        assertThat(s.netDelta).isEqualTo(0)
    }

    @Test
    fun `전적 - 남의 기록은 섞이지 않는다`() = fixture { f ->
        f.signedUp("me")
        f.signedUp("other")
        f.fs.admin().write(listOf(WriteOp.Set("users/other/appliedResults/g1", buildJsonObject { put("rank", 0); put("delta", 5); put("playerCount", 2); put("finishedAt", 1L); put("appliedAt", 2L) })))
        val mine = HistoryModel(f.users("me"))
        launch { mine.run() }
        assertThat(mine.state.await { it.results != null }.results!!.isEmpty()).isTrue()
    }
}
