package io.github.lhd980820.coup.account

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import org.junit.jupiter.api.Test

class AccountTest {
    private val me = UserProfile("me", "나", rating = 1000, plays = 3, wins = 1, createdAt = 0)

    private fun result(ranking: List<String>, rated: Boolean = true, deltas: Map<String, Int>? = null) = GameResult(
        gameId = "g1",
        ranking = ranking,
        playerCount = ranking.size,
        rated = rated,
        ratingDeltas = deltas ?: mapOf(
            2 to listOf(30, -20), 3 to listOf(50, 20, -40), 4 to listOf(70, 30, -30, -50),
        ).getValue(ranking.size).let { ranking.zip(it).toMap() },
        finishedAt = 50,
    )

    // ---- 레이팅 반영 -------------------------------------------------------------------------

    @Test
    fun `1위면 레이팅·판수·승수가 오르고 전적이 남는다`() {
        val d = RatingLedger.decide(me, result(listOf("me", "a", "b")), alreadyApplied = false, now = 99) as RatingDecision.Apply
        assertThat(d.updated).isEqualTo(me.copy(rating = 1050, plays = 4, wins = 2))
        assertThat(d.record).isEqualTo(AppliedResult("g1", rank = 0, delta = 50, playerCount = 3, finishedAt = 50, appliedAt = 99))
    }

    @Test
    fun `꼴찌면 레이팅이 내려가고 승수는 그대로`() {
        val d = RatingLedger.decide(me, result(listOf("a", "b", "c", "me")), false, 0) as RatingDecision.Apply
        assertThat(d.updated).isEqualTo(me.copy(rating = 950, plays = 4))
    }

    @Test
    fun `하한은 없다(기존 앱과 같음)`() {
        val low = me.copy(rating = 10)
        val d = RatingLedger.decide(low, result(listOf("a", "me")), false, 0) as RatingDecision.Apply
        assertThat(d.updated.rating).isEqualTo(-10)
    }

    @Test
    fun `반영하지 않는 경우들`() {
        fun skip(r: GameResult, applied: Boolean = false) = (RatingLedger.decide(me, r, applied, 0) as RatingDecision.Skip).reason
        assertThat(skip(result(listOf("me", "a")), applied = true)).isEqualTo(SkipReason.ALREADY_APPLIED)
        assertThat(skip(result(listOf("me", "a"), rated = false))).isEqualTo(SkipReason.NOT_RATED)
        assertThat(skip(result(listOf("a", "b")))).isEqualTo(SkipReason.NOT_A_PARTICIPANT)
    }

    @Test
    fun `결과 문서의 변동량이 표와 다르면 반영하지 않는다(방장 조작 방어)`() {
        fun skip(r: GameResult) = (RatingLedger.decide(me, r, false, 0) as RatingDecision.Skip).reason
        assertThat(skip(result(listOf("a", "me"), deltas = mapOf("a" to 30, "me" to 500)))).isEqualTo(SkipReason.INCONSISTENT)
        assertThat(skip(result(listOf("a", "me")).copy(playerCount = 3))).isEqualTo(SkipReason.INCONSISTENT)
        assertThat(skip(result(listOf("me", "me")).copy(ratingDeltas = mapOf("me" to 30)))).isEqualTo(SkipReason.INCONSISTENT)
        // 지원하지 않는 인원(7명)
        val seven = List(7) { "p$it" } + "me"
        assertThat(skip(GameResult("g", seven, seven.size, true, seven.associateWith { 0 }, 0))).isEqualTo(SkipReason.INCONSISTENT)
        // 내 항목이 없음
        assertThat(skip(result(listOf("me", "a"), deltas = mapOf("a" to -20)))).isEqualTo(SkipReason.INCONSISTENT)
    }

    // ---- 닉네임 -----------------------------------------------------------------------------

    @Test
    fun `닉네임 규칙`() {
        assertThat(Nicknames.validate("  쿠  ")).isEqualTo(NicknameError.TOO_SHORT)
        assertThat(Nicknames.validate("가나")).isNull()
        assertThat(Nicknames.validate("a".repeat(12))).isNull()
        assertThat(Nicknames.validate("a".repeat(13))).isEqualTo(NicknameError.TOO_LONG)
        assertThat(Nicknames.validate("줄\n바꿈")).isEqualTo(NicknameError.INVALID_CHARACTERS)
        assertThat(Nicknames.validate("보이지​않음")).isEqualTo(NicknameError.INVALID_CHARACTERS)
        // 길이는 UTF-16 단위다(규칙의 size()와 같다): 이모지 하나가 2
        assertThat(Nicknames.validate("😀")).isNull()
        assertThat(Nicknames.validate("😀".repeat(6))).isNull()
        assertThat(Nicknames.validate("😀".repeat(7))).isEqualTo(NicknameError.TOO_LONG)
    }

    @Test
    fun `기본 닉네임은 이메일 앞부분이고 규칙에 맞게 다듬는다`() {
        assertThat(Nicknames.defaultFor("coupking@gmail.com", "Player")).isEqualTo("coupking")
        assertThat(Nicknames.defaultFor("averyveryverylongname@x.com", "Player")).isEqualTo("averyveryver")
        assertThat(Nicknames.defaultFor("a@x.com", "Player")).isEqualTo("Player")
        // 서로게이트 쌍을 가르지 않고 12 단위 안에서 자른다
        assertThat(Nicknames.defaultFor("abcdefghijk😀😀@x.com", "Player")).isEqualTo("abcdefghijk")
        assertThat(Nicknames.defaultFor(null, "Player")).isEqualTo("Player")
    }

    // ---- 랭킹 -------------------------------------------------------------------------------

    @Test
    fun `랭킹은 레이팅순이고 동점은 공동 순위, 판수 0은 빠진다`() {
        val list = listOf(
            UserProfile("a", "A", 1100, 5, 2, 0),
            UserProfile("b", "B", 1200, 1, 1, 0),
            UserProfile("c", "C", 1100, 9, 3, 0),
            UserProfile("d", "D", 1000, 0, 0, 0),
            UserProfile("e", "E", 900, 2, 0, 0),
        )
        val ranked = Rankings.rank(list)
        assertThat(ranked.map { it.uid to it.rank }).containsExactly("b" to 1, "c" to 2, "a" to 2, "e" to 4)
        assertThat(Rankings.rank(list, includeUnplayed = true).map { it.uid }).containsExactly("b", "c", "a", "d", "e")
        assertThat(Rankings.winRate(list[0])).isEqualTo(0.4)
        assertThat(Rankings.winRate(list[3])).isNull()
    }

    // ---- 옛 계정 이관 ------------------------------------------------------------------------

    @Test
    fun `옛 계정 이관 계획`() {
        val legacy = LegacyUser("me@x.com", "옛닉", rating = 1230, plays = 40)
        fun plan(existing: UserProfile? = null, l: LegacyUser? = legacy, claimed: Boolean = false, email: String? = "me@x.com") =
            LegacyMigration.plan("uid1", email, existing, l, claimed, "기본닉", now = 7)

        assertThat(plan(existing = me)).isEqualTo(ProfileSetup.Existing(me))
        val migrated = plan() as ProfileSetup.Migrate
        assertThat(migrated.profile).isEqualTo(UserProfile("uid1", "옛닉", 1230, 40, 0, 7, migratedFromLegacy = true))
        assertThat(migrated.email).isEqualTo("me@x.com")
        assertThat(plan(claimed = true)).isInstanceOf(ProfileSetup.Fresh::class).prop(ProfileSetup.Fresh::profile)
            .isEqualTo(UserProfile.fresh("uid1", "기본닉", 7))
        assertThat(plan(l = null)).isInstanceOf(ProfileSetup.Fresh::class)
        assertThat(plan(email = null)).isInstanceOf(ProfileSetup.Fresh::class)
        assertThat(plan(email = "other@x.com")).isInstanceOf(ProfileSetup.Fresh::class)
        // 옛 닉네임이 지금 규칙에 맞지 않으면 기본 닉네임
        assertThat((plan(l = legacy.copy(nickname = "x")) as ProfileSetup.Migrate).profile.nickname).isEqualTo("기본닉")
        assertThat((plan(l = legacy.copy(nickname = null)) as ProfileSetup.Migrate).profile.migratedFromLegacy).isTrue()
        assertThat(runCatching { LegacyMigration.plan("u", null, null, null, false, "x", 0) }).isFailure()
    }
}
