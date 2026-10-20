package io.github.lhd980820.coup.multiplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.remote.WriteOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class StatsScreenTest : MpScreenTest() {
    private fun seedProfile(uid: String, nickname: String, rating: Int, plays: Int, wins: Int) = runBlocking {
        fx.fs.admin().write(
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

    private fun seedResult(uid: String, gameId: String, rank: Int, delta: Int, players: Int, finishedAt: Long) = runBlocking {
        fx.fs.admin().write(
            listOf(
                WriteOp.Set(
                    "users/$uid/appliedResults/$gameId",
                    buildJsonObject { put("rank", rank); put("delta", delta); put("playerCount", players); put("finishedAt", finishedAt); put("appliedAt", finishedAt + 1) },
                ),
            ),
        )
    }

    /** 화면에 그려진 행들의 태그를 위에서부터. */
    private fun rowTags(prefix: String): List<String> = tags().filter { it.startsWith(prefix) }

    private fun signInHome() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        click(LobbyTags.BACK)
        waitFor(ProfileTags.CARD)
    }

    @Test
    fun `로그인 전 홈에는 랭킹·전적 버튼이 없다`() {
        show()
        waitFor(ShellTags.HOME_MULTI)
        assertTrue(!exists(ProfileTags.RANKING))
        assertTrue(!exists(ProfileTags.HISTORY))
    }

    @Test
    fun `랭킹 - 레이팅 순, 공동 순위, 한 판도 안 한 사람은 빠지고, 안 한 나에게는 안내한다`() {
        seedProfile("a", "에이", 1200, 10, 6)
        seedProfile("b", "비", 1100, 8, 3)
        seedProfile("c", "씨", 1100, 4, 1)
        seedProfile("d", "디", 1000, 0, 0)
        signInHome()
        click(ProfileTags.RANKING)
        waitFor(StatsTags.rank("a"))
        assertEquals(listOf("rank_row_a", "rank_row_b", "rank_row_c"), rowTags("rank_row_"))
        compose.onNodeWithText(string(R.string.stats_rank, 1)).assertIsDisplayed()
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.stats_rank, 2))).fetchSemanticsNodes().size) // 비·씨 공동 2위
        waitFor(StatsTags.ME)
        compose.onNodeWithText(string(R.string.stats_me_unranked)).assertIsDisplayed()
        click(StatsTags.BACK)
        waitFor(ProfileTags.CARD)
    }

    @Test
    fun `랭킹 - 내가 상위에 있으면 내 순위와 강조된 줄이 보이고, 레이팅이 바뀌면 따라 바뀐다`() {
        seedProfile("a", "에이", 1200, 10, 6)
        signInHome()
        click(ProfileTags.RANKING)
        waitFor(StatsTags.rank("a"))
        seedProfile("kim", "kim", 1300, 3, 3)
        waitFor(StatsTags.rank("kim"))
        assertEquals(listOf("rank_row_kim", "rank_row_a"), rowTags("rank_row_"))
        compose.onNodeWithText(string(R.string.stats_me_in_top, 1, 1300)).assertIsDisplayed()
        compose.onNodeWithText("kim ${string(R.string.stats_row_me)}").assertIsDisplayed()
    }

    @Test
    fun `랭킹 - 아무도 없으면 안내한다`() {
        signInHome()
        click(ProfileTags.RANKING)
        waitFor(StatsTags.EMPTY)
        compose.onNodeWithText(string(R.string.stats_ranking_empty)).assertIsDisplayed()
    }

    @Test
    fun `전적 - 최근 것부터 한 줄씩, 요약과 레이팅 변화 합이 보인다`() {
        signInHome()
        seedProfile("kim", "kim", 1010, 4, 1)
        seedResult("kim", "g1", 0, 12, 4, 1_000)
        seedResult("kim", "g2", 2, -8, 4, 2_000)
        seedResult("kim", "g3", 1, -2, 3, 3_000)
        click(ProfileTags.HISTORY)
        waitFor(StatsTags.history("g3"))
        assertEquals(listOf("history_row_g3", "history_row_g2", "history_row_g1"), rowTags("history_row_"))
        compose.onNodeWithText(string(R.string.stats_history_summary, 4, 1, 25)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.stats_history_net, 3, 12 - 8 - 2)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.stats_history_row, 4, 1)).assertIsDisplayed() // g1: 4인전 1위
        compose.onNodeWithText("+12").assertIsDisplayed()
        compose.onNodeWithText("-8").assertIsDisplayed()
        click(StatsTags.BACK)
        waitFor(ProfileTags.CARD)
    }

    @Test
    fun `전적 - 기록이 없으면 안내한다`() {
        signInHome()
        click(ProfileTags.HISTORY)
        waitFor(StatsTags.EMPTY)
        compose.onNodeWithText(string(R.string.stats_history_empty)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.stats_history_summary, 0, 0, 0)).assertIsDisplayed()
    }

    @Test
    fun `로그아웃하면 랭킹 화면에서 홈으로 돌아간다`() {
        signInHome()
        click(ProfileTags.RANKING)
        waitFor(StatsTags.EMPTY)
        compose.runOnIdle { fx.shell.signOut() }
        waitFor(ShellTags.HOME_MULTI)
        assertTrue(!exists(ProfileTags.CARD))
    }
}
