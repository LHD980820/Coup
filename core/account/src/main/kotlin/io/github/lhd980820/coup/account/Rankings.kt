package io.github.lhd980820.coup.account

/** 랭킹 한 줄. 같은 레이팅은 같은 순위(1, 1, 3 ...). */
public data class RankingEntry(
    val rank: Int,
    val uid: String,
    val nickname: String,
    val rating: Int,
    val plays: Int,
    val wins: Int,
)

public object Rankings {
    /**
     * 레이팅 내림차순 → (같으면) 판수 많은 순 → uid 순으로 세운다. 순위 번호는 레이팅만으로 정한다(동점 공동 순위).
     * 한 번도 rated 게임을 하지 않은 사용자는 [includeUnplayed]가 false면 뺀다.
     */
    public fun rank(profiles: List<UserProfile>, includeUnplayed: Boolean = false): List<RankingEntry> {
        val sorted = profiles
            .filter { includeUnplayed || it.plays > 0 }
            .sortedWith(compareByDescending<UserProfile> { it.rating }.thenByDescending { it.plays }.thenBy { it.uid })
        var previousRating: Int? = null
        var currentRank = 0
        return sorted.mapIndexed { i, p ->
            if (p.rating != previousRating) {
                currentRank = i + 1
                previousRating = p.rating
            }
            RankingEntry(currentRank, p.uid, p.nickname, p.rating, p.plays, p.wins)
        }
    }

    /** 승률(0.0~1.0). 판수가 0이면 null. */
    public fun winRate(profile: UserProfile): Double? = if (profile.plays == 0) null else profile.wins.toDouble() / profile.plays
}
