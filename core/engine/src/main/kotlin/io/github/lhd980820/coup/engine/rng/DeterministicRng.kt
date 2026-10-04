package io.github.lhd980820.coup.engine.rng

import kotlinx.serialization.Serializable

/**
 * 직렬화 가능한 순수 값 타입 난수 생성기(SplitMix64).
 * 상태를 바꾸지 않고 항상 "(결과, 다음 RNG)" 쌍을 돌려주므로 같은 seed와 같은 호출 순서는 항상 같은 결과를 낸다.
 */
@Serializable
public data class DeterministicRng(public val state: Long) {

    /** `0 until bound` 범위의 정수와 다음 RNG. */
    public fun nextInt(bound: Int): Pair<Int, DeterministicRng> {
        require(bound > 0) { "bound must be positive: $bound" }
        val (raw, next) = nextLong()
        val value = ((raw ushr 1) % bound.toLong()).toInt()
        return value to next
    }

    /** Fisher-Yates 셔플. */
    public fun <T> shuffled(items: List<T>): Pair<List<T>, DeterministicRng> {
        val result = items.toMutableList()
        var rng = this
        for (i in result.size - 1 downTo 1) {
            val (j, next) = rng.nextInt(i + 1)
            rng = next
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result to rng
    }

    private fun nextLong(): Pair<Long, DeterministicRng> {
        val s = state + GOLDEN_GAMMA
        var z = s
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return z to DeterministicRng(s)
    }

    public companion object {
        private const val GOLDEN_GAMMA: Long = -7046029254386353131L

        public fun ofSeed(seed: Long): DeterministicRng = DeterministicRng(seed)
    }
}
