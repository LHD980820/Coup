package io.github.lhd980820.coup.account

/**
 * 공개 프로필(`users/{uid}`, 설계 §8.2). 로그인한 누구나 읽는다(랭킹·대기실 표시) — 그래서 이메일 같은 개인 정보는 담지 않는다.
 * [rating]·[plays]·[wins]는 **레이팅 반영 대상(rated) 게임만** 센다. 하한은 없다(기존 앱과 같다).
 */
public data class UserProfile(
    val uid: String,
    val nickname: String,
    val rating: Int,
    val plays: Int,
    val wins: Int,
    val createdAt: Long,
    /** 옛 `user/{email}` 문서에서 옮겨 온 계정인가(§14 R2). */
    val migratedFromLegacy: Boolean = false,
) {
    public companion object {
        /** 기존 앱의 시작 레이팅. */
        public const val INITIAL_RATING: Int = 1000

        public fun fresh(uid: String, nickname: String, now: Long): UserProfile =
            UserProfile(uid, nickname, INITIAL_RATING, plays = 0, wins = 0, createdAt = now)
    }
}

public enum class NicknameError { TOO_SHORT, TOO_LONG, INVALID_CHARACTERS }

/** 닉네임 규칙: 앞뒤 공백 제거 후 2~12자, 제어 문자·줄바꿈 금지. 보안 규칙(`firestore.rules`)도 같은 길이를 검사한다. */
public object Nicknames {
    public const val MIN_LENGTH: Int = 2
    public const val MAX_LENGTH: Int = 12

    public fun normalize(input: String): String = input.trim()

    /** 문제가 없으면 null. [normalize]한 값에 대해 판단한다. */
    public fun validate(input: String): NicknameError? {
        val name = normalize(input)
        return when {
            name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } -> NicknameError.INVALID_CHARACTERS
            name.codePointCount(0, name.length) < MIN_LENGTH -> NicknameError.TOO_SHORT
            name.codePointCount(0, name.length) > MAX_LENGTH -> NicknameError.TOO_LONG
            else -> null
        }
    }

    /**
     * 처음 로그인한 사용자의 기본 닉네임(기존 앱처럼 이메일의 @ 앞부분). 규칙에 맞지 않으면 다듬거나 [fallback]을 쓴다.
     */
    public fun defaultFor(email: String?, fallback: String): String {
        val local = email?.substringBefore('@')?.filter { !it.isISOControl() }?.trim().orEmpty()
        val cut = if (local.codePointCount(0, local.length) > MAX_LENGTH) local.substring(0, local.offsetByCodePoints(0, MAX_LENGTH)) else local
        return if (validate(cut) == null) cut else fallback
    }
}
