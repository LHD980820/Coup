package io.github.lhd980820.coup.engine.rules

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RatingPolicyTest {
    private fun ranking(n: Int) = List(n) { PlayerId("p$it") }

    @Test
    fun `기존 앱의 표와 같은 값`() {
        assertThat(TableRatingPolicy.ratingDeltas(ranking(2)).values.toList()).isEqualTo(listOf(30, -20))
        assertThat(TableRatingPolicy.ratingDeltas(ranking(4)).values.toList()).isEqualTo(listOf(70, 30, -30, -50))
        assertThat(TableRatingPolicy.ratingDeltas(ranking(6)).values.toList()).isEqualTo(listOf(130, 70, 40, -30, -60, -90))
    }

    @Test
    fun `순위가 높을수록 변동이 크고 꼴찌는 음수`() {
        (2..6).forEach { n ->
            val deltas = TableRatingPolicy.ratingDeltas(ranking(n)).values.toList()
            assertThat(deltas.zipWithNext().all { (a, b) -> a > b }).isEqualTo(true)
            assertThat(deltas.last()).isLessThan(0)
        }
    }

    @Test
    fun `지원하지 않는 인원이나 중복은 거절`() {
        assertThrows<IllegalArgumentException> { TableRatingPolicy.ratingDeltas(ranking(1)) }
        assertThrows<IllegalArgumentException> { TableRatingPolicy.ratingDeltas(ranking(7)) }
        assertThrows<IllegalArgumentException> { TableRatingPolicy.ratingDeltas(listOf(PlayerId("a"), PlayerId("a"))) }
    }
}
