package io.github.lhd980820.coup.account

/** 옛 앱의 `user/{email}` 문서에서 옮겨 올 값. 옛 앱은 승수를 기록하지 않았다. */
public data class LegacyUser(val email: String, val nickname: String?, val rating: Int, val plays: Int)

public sealed interface ProfileSetup {
    /** 이미 프로필이 있다. 아무것도 하지 않는다. */
    public data class Existing(val profile: UserProfile) : ProfileSetup

    /** 새 사용자. */
    public data class Fresh(val profile: UserProfile) : ProfileSetup

    /** 옛 계정을 옮긴다. 저장소는 이메일 선점 표시(`legacyClaims/{email}`)와 프로필을 한 배치로 쓴다. */
    public data class Migrate(val profile: UserProfile, val email: String) : ProfileSetup
}

/**
 * 로그인 직후 프로필 준비(설계 §14 R2). 옛 문서가 있고 아직 아무도 옮겨 가지 않았으면 레이팅·판수·닉네임을 가져온다.
 * 옛 닉네임이 지금 규칙에 맞지 않으면 기본 닉네임을 쓴다.
 */
public object LegacyMigration {
    public fun plan(
        uid: String,
        email: String?,
        existing: UserProfile?,
        legacy: LegacyUser?,
        legacyAlreadyClaimed: Boolean,
        defaultNickname: String,
        now: Long,
    ): ProfileSetup {
        if (existing != null) return ProfileSetup.Existing(existing)
        val nickname = Nicknames.normalize(defaultNickname).takeIf { Nicknames.validate(it) == null }
            ?: throw IllegalArgumentException("default nickname must be valid: $defaultNickname")
        if (email == null || legacy == null || legacyAlreadyClaimed || !legacy.email.equals(email, ignoreCase = true)) {
            return ProfileSetup.Fresh(UserProfile.fresh(uid, nickname, now))
        }
        val legacyName = legacy.nickname?.let(Nicknames::normalize)?.takeIf { Nicknames.validate(it) == null }
        return ProfileSetup.Migrate(
            UserProfile(
                uid = uid,
                nickname = legacyName ?: nickname,
                rating = legacy.rating,
                plays = legacy.plays,
                wins = 0,
                createdAt = now,
                migratedFromLegacy = true,
            ),
            email = legacy.email,
        )
    }
}
