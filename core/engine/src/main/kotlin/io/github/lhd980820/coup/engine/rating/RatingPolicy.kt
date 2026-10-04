package io.github.lhd980820.coup.engine.rating

import io.github.lhd980820.coup.engine.model.PlayerId

/** 게임 결과(순위)에서 레이팅 변동을 계산한다(설계 §4.8). 기존 앱의 인원수 x 순위 고정 표를 한 곳으로 모았다. */
public fun interface RatingPolicy {
    /** @param ranking 승자부터 먼저 탈락한 순서로 전원. @throws IllegalArgumentException 인원이 지원 범위(2~6) 밖일 때 */
    public fun ratingDeltas(ranking: List<PlayerId>): Map<PlayerId, Int>
}

public object TableRatingPolicy : RatingPolicy {
    /** 인원수 -> 순위별 변동(1위부터). 기존 앱 `ratingChangeTable`과 같은 값. */
    private val table: Map<Int, List<Int>> = mapOf(
        2 to listOf(30, -20),
        3 to listOf(50, 20, -40),
        4 to listOf(70, 30, -30, -50),
        5 to listOf(100, 60, 30, -40, -70),
        6 to listOf(130, 70, 40, -30, -60, -90),
    )

    override fun ratingDeltas(ranking: List<PlayerId>): Map<PlayerId, Int> {
        val deltas = requireNotNull(table[ranking.size]) { "unsupported player count: ${ranking.size}" }
        require(ranking.toSet().size == ranking.size) { "duplicate players in ranking" }
        return ranking.zip(deltas).toMap()
    }
}
