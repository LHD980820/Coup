package io.github.lhd980820.coup.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import io.github.lhd980820.coup.ai.belief.ClaimHistory
import io.github.lhd980820.coup.ai.belief.HiddenSampler
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.Viewer
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** HARD(결정화 몬테카를로, 설계 §7.5): 가정 표본의 일관성, 탐색 예산, 합법성과 재현성, 강도. */
class HardAgentTest {
    private val engine = Arena.engine
    private fun p(n: Int) = PlayerId("p$n")

    /** 시뮬레이션 호출 수를 세고(선택적으로 실패시키는) 엔진. */
    private class SpyEngine(private val inner: GameEngine, private val failDeterminize: Boolean = false) : GameEngine by inner {
        var determinizeCalls = 0
        override fun determinize(view: PlayerView, assignment: HiddenAssignment, seed: Long) = run {
            determinizeCalls++
            if (failDeterminize) throw IllegalArgumentException("contradiction")
            inner.determinize(view, assignment, seed)
        }
    }

    private fun config(samples: Int = 6, minSamples: Int = 1, timeBudget: kotlin.time.Duration? = null) =
        HardConfig(samples = samples, rolloutDecisions = 14, timeBudget = timeBudget, minSamples = minSamples)

    /** 무작위로 둔 게임에서 결정 시점의 (상태, 결정자) 목록을 모은다. */
    private fun decisionPoints(games: Int, players: Int = 3, limit: Int = 400): List<Pair<GameState, PlayerId>> {
        val points = mutableListOf<Pair<GameState, PlayerId>>()
        for (g in 0 until games) {
            val seats = List(players) { p(it) }
            var state = engine.newGame(GameSetup("hp$g", seats, BuiltinRules.classicConfig(), g.toLong()))
            val agents = seats.associateWith { RandomAgent(g * 31L + it.value.hashCode()) }
            while (!state.isOver && points.size < limit) {
                val who = engine.pendingDeciders(state).first()
                points += state to who
                val view = engine.view(state, Viewer.Player(who))
                state = (engine.apply(state, agents.getValue(who).decide(view, checkNotNull(view.myDecision))) as ApplyResult.Accepted).state
            }
        }
        return points
    }

    // ---- 비공개 정보 표본 ----

    @Test
    fun `뽑은 가정으로 만든 세계는 그 플레이어에게 원래 뷰와 똑같이 보인다 - 교환 중 포함`() {
        var checked = 0
        var exchanges = 0
        decisionPoints(games = 12, players = 4, limit = 600).forEachIndexed { i, (state, who) ->
            val view = engine.view(state, Viewer.Player(who))
            val sampler = HiddenSampler(view, ClaimHistory())
            val assignment = checkNotNull(sampler.sample(Random(i))) { "no assignment for ${view.phase}" }
            val world = engine.determinize(view, assignment, seed = 1)
            check(engine.view(world, Viewer.Player(who)) == view) { "sampled world looks different at ${view.phase}" }
            if (view.myDecision is DecisionRequest.ChooseExchange) exchanges++
            checked++
        }
        assertThat(checked).isGreaterThan(300)
        assertThat(exchanges).isGreaterThan(0)
    }

    @Test
    fun `상대가 주장한 역할은 그 상대가 들고 있다고 더 자주 가정한다`() {
        val seats = List(3) { p(it) }
        val state = engine.newGame(GameSetup("claim", seats, BuiltinRules.classicConfig(), 5, p(0)))
        val declared = engine.apply(state, Command.DeclareAction(p(0), ActionId("tax"), null)) as ApplyResult.Accepted
        val viewer = Viewer.Player(p(1))
        val view = engine.view(declared.state, viewer)
        val duke = RoleId("duke")
        val history = ClaimHistory().also { it.observe(engine.projectEvents(declared.events, viewer)) }

        fun holdsDuke(h: ClaimHistory): Double {
            val sampler = HiddenSampler(view, h)
            val random = Random(3)
            return (0 until 2_000).count { duke in checkNotNull(sampler.sample(random)).hands.getValue(p(0)) } / 2_000.0
        }
        val without = holdsDuke(ClaimHistory())
        val with = holdsDuke(history)
        assertThat(with).isGreaterThan(without + 0.15)
    }

    // ---- 탐색 예산 ----

    private fun firstPoint(predicate: (PlayerView) -> Boolean): PlayerView =
        decisionPoints(games = 20).map { (s, w) -> engine.view(s, Viewer.Player(w)) }.first(predicate)

    private fun multiChoiceView() = firstPoint { v ->
        (v.myDecision as? DecisionRequest.Respond)?.let { it.canChallenge || it.blockOptions.isNotEmpty() } == true
    }

    @Test
    fun `고를 수가 하나뿐이면 탐색하지 않는다`() {
        // 마지막 한 장을 잃는 순간 등: 잃을 카드가 하나뿐이다
        val onlyOne = firstPoint { v ->
            (v.myDecision as? DecisionRequest.ChooseInfluenceToLose)?.let { it.cards.distinctBy { c -> c.role }.size == 1 } == true
        }
        val spy = SpyEngine(engine)
        val command = AiFactory.create(AiDifficulty.HARD, seed = 1, hard = config(), engine = spy).decide(onlyOne, checkNotNull(onlyOne.myDecision))
        assertThat(command).isInstanceOf(Command.LoseInfluence::class)
        assertThat(spy.determinizeCalls).isEqualTo(0)
    }

    @Test
    fun `시간 상한이 없으면 정해진 가정 수만큼 정확히 탐색한다`() {
        val view = multiChoiceView()
        val spy = SpyEngine(engine)
        AiFactory.create(AiDifficulty.HARD, seed = 1, hard = config(samples = 7), engine = spy).decide(view, checkNotNull(view.myDecision))
        assertThat(spy.determinizeCalls).isEqualTo(7)
    }

    @Test
    fun `시간 상한이 이미 지났어도 최소 가정 수는 끝낸다`() {
        val view = multiChoiceView()
        val spy = SpyEngine(engine)
        val agent = HardAgent(spy, config(samples = 20, minSamples = 3, timeBudget = 0.milliseconds), Personality.BALANCED, 1)
        val command = agent.decide(view, checkNotNull(view.myDecision))
        assertThat(spy.determinizeCalls).isEqualTo(3)
        assertThat(command.actor).isEqualTo(view.me!!.id)
    }

    @Test
    fun `시간이 흐르면 상한에서 멈춘다 - 가정 하나를 시뮬레이션할 때마다 시계가 움직이는 경우`() {
        val view = multiChoiceView()
        val spy = SpyEngine(engine)
        var now = 0L
        // 호출마다 10ms가 지나는 시계: 상한 35ms면 몇 번 못 돌고 멈춰야 한다
        val clock = { now += 10_000_000L; now }
        HardAgent(spy, config(samples = 50, minSamples = 1, timeBudget = 35.milliseconds), Personality.BALANCED, 1, clock).decide(view, checkNotNull(view.myDecision))
        assertThat(spy.determinizeCalls).isGreaterThan(0)
        assertThat(spy.determinizeCalls).isLessThan(10)
    }

    @Test
    fun `취소 신호(스레드 인터럽트)가 오면 최소 가정만 끝내고 돌려준다`() {
        val view = multiChoiceView()
        val spy = SpyEngine(engine)
        Thread.currentThread().interrupt()
        try {
            HardAgent(spy, config(samples = 30, minSamples = 2), Personality.BALANCED, 1).decide(view, checkNotNull(view.myDecision))
        } finally {
            Thread.interrupted() // 다른 테스트에 번지지 않게 지운다
        }
        assertThat(spy.determinizeCalls).isEqualTo(2)
    }

    @Test
    fun `가정이 뷰와 모순이라 시뮬레이션이 안 되면 NORMAL 정책으로 합법적인 수를 낸다`() {
        val spy = SpyEngine(engine, failDeterminize = true)
        decisionPoints(games = 6).take(120).forEach { (state, who) ->
            val view = engine.view(state, Viewer.Player(who))
            val command = AiFactory.create(AiDifficulty.HARD, seed = 2, hard = config(), engine = spy).decide(view, checkNotNull(view.myDecision))
            assertThat(engine.apply(state, command)).isInstanceOf(ApplyResult.Accepted::class)
        }
        assertThat(spy.determinizeCalls).isGreaterThan(0)
    }

    // ---- 후보 줄이기 ----

    @Test
    fun `대상 후보 수 설정이 대상이 필요한 행동의 후보를 줄인다`() {
        val seats = List(5) { p(it) }
        val state = engine.newGame(GameSetup("targets", seats, BuiltinRules.classicConfig(), 9, p(0)))
        val view = engine.view(state, Viewer.Player(p(0)))
        // 코인 2로 시작하므로 쿠·암살은 못 한다: 대상 후보가 늘어나는 행동은 강탈(대상 4명)뿐
        val counts = listOf(1, 2, 4).map { k ->
            val spy = SpyEngine(engine)
            // 후보 수는 직접 볼 수 없으니, 한 표본에서 apply가 몇 번 불렸는지로 센다
            var applies = 0
            val counting = object : GameEngine by spy {
                override fun apply(state: GameState, command: Command): ApplyResult {
                    applies++
                    return spy.apply(state, command)
                }
            }
            HardAgent(counting, HardConfig(samples = 1, rolloutDecisions = 1, timeBudget = null, minSamples = 1, candidateTargets = k), Personality.BALANCED, 1)
                .decide(view, checkNotNull(view.myDecision))
            applies
        }
        assertThat(counts[1]).isGreaterThan(counts[0])
        assertThat(counts[2]).isGreaterThan(counts[1])
    }

    // ---- 사전값(NORMAL의 선택) ----

    @Test
    fun `사전 보너스가 아주 크면 NORMAL 정책의 선택을 그대로 따른다 - 대상 후보를 줄여도 그 수는 후보에 남는다`() {
        val cfg = HardConfig(samples = 2, rolloutDecisions = 4, timeBudget = null, minSamples = 1, candidateTargets = 1, priorBonus = 1_000.0)
        var different = 0
        val points = decisionPoints(games = 10, players = 5, limit = 200)
        points.forEach { (state, who) ->
            val view = engine.view(state, Viewer.Player(who))
            val request = checkNotNull(view.myDecision)
            val hard = HardAgent(engine, cfg, Personality.BALANCED, seed = 3L).decide(view, request)
            val normal = HeuristicAgent(AiSettings.of(AiDifficulty.HARD), Personality.BALANCED, 3L xor HardAgent.BASE_SEED_MIX).decide(view, request)
            if (hard != normal) different++
        }
        assertThat(points.size).isGreaterThan(100)
        assertThat(different).isEqualTo(0)
    }

    @Test
    fun `사전 보너스가 없으면 시뮬레이션이 NORMAL의 선택을 뒤집을 수 있다`() {
        val cfg = HardConfig(samples = 8, rolloutDecisions = 14, timeBudget = null, minSamples = 1, priorBonus = 0.0)
        var different = 0
        decisionPoints(games = 10, players = 4, limit = 150).forEach { (state, who) ->
            val view = engine.view(state, Viewer.Player(who))
            val request = checkNotNull(view.myDecision)
            val hard = HardAgent(engine, cfg, Personality.BALANCED, seed = 3L).decide(view, request)
            val normal = HeuristicAgent(AiSettings.of(AiDifficulty.HARD), Personality.BALANCED, 3L xor HardAgent.BASE_SEED_MIX).decide(view, request)
            if (hard != normal) different++
        }
        assertThat(different).isGreaterThan(0)
    }

    // ---- 재현성과 강도 ----

    @Test
    fun `같은 seed와 설정이면 한 판 전체가 똑같이 재현된다`() {
        fun play(): List<Any> {
            val log = mutableListOf<Any>()
            val agents = listOf(
                AiFactory.create(AiDifficulty.HARD, seed = 11, hard = config(samples = 4)),
                AiFactory.create(AiDifficulty.NORMAL, seed = 12),
                AiFactory.create(AiDifficulty.NORMAL, seed = 13),
            )
            val result = Arena.play(agents, seed = 77, onDecision = { log += it })
            return log + result.winner + result.decisions
        }
        assertThat(play()).isEqualTo(play())
    }

    @Test
    fun `HARD는 무작위보다 훨씬 강하다`() {
        val hard = { s: Long -> AiFactory.create(AiDifficulty.HARD, seed = s, hard = config(samples = 12)) }
        val random = { s: Long -> RandomAgent(s) as AiAgent }
        assertThat(Arena.winRate(hard, random, players = 4, games = 60, seedBase = 500)).isGreaterThan(0.55) // 기준선 0.25, 측정 ~0.76
    }

    @Test
    fun `HARD는 NORMAL보다 강하다 - 2인전`() {
        val hard = { s: Long -> AiFactory.create(AiDifficulty.HARD, seed = s, hard = config(samples = 24)) }
        val normal = { s: Long -> AiFactory.create(AiDifficulty.NORMAL, seed = s) }
        assertThat(Arena.winRate(hard, normal, players = 2, games = 100, seedBase = 900)).isGreaterThan(0.58) // 기준선 0.5, 기본 설정(표본 40) 측정 ~0.71
    }
}
