package io.github.lhd980820.coup.ai

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("slow")
class HardProbe {
    @Test
    fun probe() {
        val samples = (System.getProperty("probe.samples") ?: "12").toInt()
        val depth = (System.getProperty("probe.depth") ?: "80").toInt()
        val games = (System.getProperty("probe.games") ?: "40").toInt()
        val players = (System.getProperty("probe.players") ?: "3").toInt()
        val bonus = (System.getProperty("probe.bonus") ?: "0.03").toDouble()
        val mix = (System.getProperty("probe.mix") ?: "true").toBoolean()
        val cfg = HardConfig(samples = samples, rolloutDecisions = depth, timeBudget = null, minSamples = 1, priorBonus = bonus, mixOpponents = mix)
        val hard = { s: Long -> AiFactory.create(AiDifficulty.HARD, seed = s, hard = cfg) }
        val villainKind = System.getProperty("probe.villain") ?: "normal"
        val normal = if (villainKind == "random") { s: Long -> RandomAgent(s) as AiAgent } else { s: Long -> AiFactory.create(AiDifficulty.NORMAL, seed = s) }
        val heroKind = System.getProperty("probe.hero") ?: "hard"
        var decisions = 0
        val t0 = System.nanoTime()
        var heroMs = 0L
        var maxMs = 0L
        val timed = { s: Long ->
            val inner = if (heroKind == "normal") AiFactory.create(AiDifficulty.NORMAL, seed = s) else hard(s)
            object : AiAgent {
                override fun onGameStart(view: io.github.lhd980820.coup.engine.view.PlayerView) = inner.onGameStart(view)
                override fun observe(events: List<io.github.lhd980820.coup.engine.view.VisibleEvent>, view: io.github.lhd980820.coup.engine.view.PlayerView) = inner.observe(events, view)
                override fun decide(view: io.github.lhd980820.coup.engine.view.PlayerView, request: io.github.lhd980820.coup.engine.view.DecisionRequest): io.github.lhd980820.coup.engine.command.Command {
                    val a = System.nanoTime()
                    val c = inner.decide(view, request)
                    val d = (System.nanoTime() - a) / 1_000_000; heroMs += d; if (d > maxMs) maxMs = d; decisions++
                    return c
                }
            } as AiAgent
        }
        val rate = Arena.winRate(timed, normal, players, games, 7000L)
        val total = (System.nanoTime() - t0) / 1_000_000
        println("PROBE bonus=$bonus mix=$mix samples=$samples depth=$depth players=$players games=$games $heroKind-vs-$villainKind winRate=${"%.2f".format(rate)} (baseline ${"%.2f".format(1.0 / players)}) hardDecisions=$decisions avgMs=${if (decisions>0) heroMs / decisions else 0} maxMs=$maxMs totalMs=$total")
    }
}
