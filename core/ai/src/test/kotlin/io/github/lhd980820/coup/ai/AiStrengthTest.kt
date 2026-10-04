package io.github.lhd980820.coup.ai

import assertk.assertThat
import assertk.assertions.isGreaterThan
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** 설계 §12.8: 난이도 서열 RANDOM < EASY < NORMAL. 기준은 측정치보다 여유 있게 잡았다. */
class AiStrengthTest {
    private val random = { s: Long -> RandomAgent(s) as AiAgent }
    private val easy = { s: Long -> AiFactory.create(AiDifficulty.EASY, seed = s) }
    private val normal = { s: Long -> AiFactory.create(AiDifficulty.NORMAL, seed = s) }

    @Test
    fun `EASY는 무작위보다 강하다`() {
        assertThat(Arena.winRate(easy, random, players = 4, games = 200, seedBase = 100)).isGreaterThan(0.45) // 측정 ~0.68, 기준선 0.25
    }

    @Test
    fun `NORMAL은 무작위보다 강하다`() {
        assertThat(Arena.winRate(normal, random, players = 4, games = 200, seedBase = 200)).isGreaterThan(0.45)
    }

    @Test
    fun `NORMAL은 EASY보다 강하다`() {
        assertThat(Arena.winRate(normal, easy, players = 2, games = 300, seedBase = 300)).isGreaterThan(0.62) // 측정 ~0.79
        assertThat(Arena.winRate(normal, easy, players = 4, games = 200, seedBase = 400)).isGreaterThan(0.5) // 측정 ~0.80
    }
}

@Tag("slow")
class TournamentTest {
    @Test
    fun `난이도별 승률표`() {
        val kinds = mapOf(
            "RANDOM" to { s: Long -> RandomAgent(s) as AiAgent },
            "EASY" to { s: Long -> AiFactory.create(AiDifficulty.EASY, seed = s) },
            "NORMAL" to { s: Long -> AiFactory.create(AiDifficulty.NORMAL, seed = s) },
            "NORMAL-aggressive" to { s: Long -> AiFactory.create(AiDifficulty.NORMAL, Personality.AGGRESSIVE, s) },
            "NORMAL-cautious" to { s: Long -> AiFactory.create(AiDifficulty.NORMAL, Personality.CAUTIOUS, s) },
        )
        for (players in listOf(2, 4, 6)) {
            println("== $players players (baseline ${"%.2f".format(1.0 / players)}) hero(row) vs others(col)")
            for ((h, hero) in kinds) {
                val row = kinds.map { (_, villain) -> "%.2f".format(Arena.winRate(hero, villain, players, 500, players * 1000L)) }
                println("%-18s %s".format(h, row.joinToString("  ")))
            }
        }
    }
}
