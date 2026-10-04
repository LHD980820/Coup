package io.github.lhd980820.coup.engine.rng

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeterministicRngTest {
    @Test
    fun `같은 seed는 같은 수열을 만든다`() {
        fun draw(seed: Long): List<Int> {
            var rng = DeterministicRng.ofSeed(seed)
            return List(20) { rng.nextInt(100).let { (v, next) -> rng = next; v } }
        }
        assertThat(draw(42)).isEqualTo(draw(42))
        assertThat(draw(42)).isNotEqualTo(draw(43))
    }

    @Test
    fun `nextInt는 범위 안의 값만 낸다`() {
        var rng = DeterministicRng.ofSeed(7)
        repeat(1000) {
            val (v, next) = rng.nextInt(6)
            rng = next
            assertThat(v in 0 until 6).isEqualTo(true)
        }
    }

    @Test
    fun `bound가 0 이하이면 예외`() {
        assertThrows<IllegalArgumentException> { DeterministicRng.ofSeed(1).nextInt(0) }
    }

    @Test
    fun `셔플은 원소를 보존하고 결정적이다`() {
        val items = (1..15).toList()
        val (a, _) = DeterministicRng.ofSeed(5).shuffled(items)
        val (b, _) = DeterministicRng.ofSeed(5).shuffled(items)
        assertThat(a).isEqualTo(b)
        assertThat(a).containsExactlyInAnyOrder(*items.toTypedArray())
        assertThat(a).isNotEqualTo(items)
    }

    @Test
    fun `직렬화 후 이어서 같은 수열이 나온다`() {
        val (_, mid) = DeterministicRng.ofSeed(9).nextInt(10)
        val restored = Json.decodeFromString(DeterministicRng.serializer(), Json.encodeToString(DeterministicRng.serializer(), mid))
        assertThat(restored.nextInt(1000).first).isEqualTo(mid.nextInt(1000).first)
    }
}
