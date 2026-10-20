package io.github.lhd980820.coup.multiplayer

import android.content.Context
import android.content.res.Configuration
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.BotStyle
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.FieldError
import io.github.lhd980820.coup.shell.lobby.LobbyProblem
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** 코어가 내는 모든 실패·상태 값에 한국어와 영어 문구가 있고, 서로 다르다(번역 누락 방지). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiplayerTextsTest {
    private fun context(locale: Locale): Context {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private val ko = context(Locale.KOREAN)
    private val en = context(Locale.ENGLISH)

    private fun check(what: String, id: Int) {
        val k = ko.getString(id)
        val e = en.getString(id)
        assertTrue("$what: 한국어 문구가 비었다", k.isNotBlank())
        assertTrue("$what: 영어 문구가 비었다", e.isNotBlank())
        assertNotEquals("$what: 영어가 한국어와 같다(번역 누락)", k, e)
        assertTrue("$what: 영어 문구에 한글이 섞였다: $e", e.none { it in '가'..'힣' })
    }

    @Test
    fun `인증 실패 사유`() = AuthError.entries.forEach { check("auth $it", MultiplayerTexts.auth(it)) }

    @Test
    fun `입력칸 오류`() = FieldError.entries.forEach { check("field $it", MultiplayerTexts.field(it)) }

    @Test
    fun `닉네임 오류`() = NicknameError.entries.forEach { check("nickname $it", MultiplayerTexts.nickname(it)) }

    @Test
    fun `방 오류 - 코어의 모든 값`() = RoomError.entries.forEach { check("room $it", MultiplayerTexts.room(it)) }

    @Test
    fun `로비 문제`() {
        check("network", MultiplayerTexts.problem(LobbyProblem.Network))
        RoomError.entries.forEach { check("problem $it", MultiplayerTexts.problem(LobbyProblem.Room(it))) }
    }

    @Test
    fun `봇 난이도와 성격 - 기본값 포함`() {
        BotLevel.entries.forEach { check("level $it", MultiplayerTexts.botLevel(it)) }
        BotStyle.entries.forEach { check("style $it", MultiplayerTexts.botStyle(it)) }
        check("level default", MultiplayerTexts.botLevel(null))
        check("style default", MultiplayerTexts.botStyle(null))
    }

    @Test
    fun `방에서 사라진 이유`() = RoomNotice.entries.forEach { check("notice $it", MultiplayerTexts.notice(it)) }

    @Test
    fun `랭킹·전적 문구`() {
        listOf(
            R.string.stats_ranking_open, R.string.stats_history_open, R.string.stats_ranking_title, R.string.stats_history_title,
            R.string.stats_ranking_loading, R.string.stats_ranking_empty, R.string.stats_offline, R.string.stats_me_in_top, R.string.stats_me_below,
            R.string.stats_me_unranked, R.string.stats_rank, R.string.stats_row_me, R.string.stats_row_record, R.string.stats_history_loading,
            R.string.stats_history_empty, R.string.stats_history_summary, R.string.stats_history_net, R.string.stats_history_row, R.string.stats_history_delta_desc,
        ).forEach { check(ko.resources.getResourceEntryName(it), it) }
    }
}
