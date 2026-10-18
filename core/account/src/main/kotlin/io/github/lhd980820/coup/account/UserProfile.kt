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

/**
 * 닉네임 규칙: 앞뒤 공백 제거 후 길이 2~12, 제어·서식 문자 금지. 길이는 **UTF-16 단위**(`String.length`)로 센다 —
 * 보안 규칙(`firestore.rules`)의 `size()`가 그렇게 세기 때문이다(에뮬레이터로 확인: 이모지 한 개 = 2). 그래서 이모지는 최대 6개.
 */
public object Nicknames {
    public const val MIN_LENGTH: Int = 2
    public const val MAX_LENGTH: Int = 12

    public fun normalize(input: String): String = input.trim()

    /** 문제가 없으면 null. [normalize]한 값에 대해 판단한다. */
    public fun validate(input: String): NicknameError? {
        val name = normalize(input)
        return when {
            name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } -> NicknameError.INVALID_CHARACTERS
            name.length < MIN_LENGTH -> NicknameError.TOO_SHORT
            name.length > MAX_LENGTH -> NicknameError.TOO_LONG
            else -> null
        }
    }

    /**
     * 처음 로그인한 사용자의 기본 닉네임(기존 앱처럼 이메일의 @ 앞부분). 규칙에 맞지 않으면 다듬거나 [fallback]을 쓴다.
     */
    public fun defaultFor(email: String?, fallback: String): String {
        val local = email?.substringBefore('@')?.filter { !it.isISOControl() }?.trim().orEmpty()
        val cut = buildString {
            var i = 0
            while (i < local.length) {
                val cp = local.codePointAt(i)
                val units = Character.charCount(cp)
                if (length + units > MAX_LENGTH) break // 서로게이트 쌍을 가르지 않는다
                appendCodePoint(cp)
                i += units
            }
        }.trim()
        return if (validate(cut) == null) cut else fallback
    }
}
