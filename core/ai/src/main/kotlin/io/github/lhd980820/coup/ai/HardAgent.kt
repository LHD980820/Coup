package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.ai.belief.ClaimHistory
import io.github.lhd980820.coup.ai.belief.HiddenSampler
import io.github.lhd980820.coup.ai.policy.Valuation
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.ActionOption
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.engine.view.VisibleEvent
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * HARD 탐색 설정(설계 §7.5).
 * @property samples 한 결정마다 뽑는 비공개 정보 가정(결정화)의 최대 개수. 가정이 많을수록 추정이 안정되고 느려진다.
 * @property rolloutDecisions 한 번의 시뮬레이션이 진행하는 최대 결정 수. 넘으면 그 시점의 형세로 평가한다.
 *   기본 14는 측정으로 골랐다(ADR 0019): 끝까지 두면 판의 우연이 후보 간 작은 차이를 덮어 오히려 약해지고(4인전 무작위 상대 승률 0.38~0.51),
 *   너무 짧으면(3) 형세 평가의 오차가 커진다. 6~20에서 4인전 NORMAL 상대 승률 0.5~0.65, 표본 40개에서 0.69.
 * @property timeBudget 한 결정에 쓰는 시간 상한(null이면 [samples]만으로 끝나 결과가 항상 재현된다).
 *   상한에 닿으면 끝낸 가정까지만으로 고른다 — 느린 기기에서는 덜 정확하지만 늦지 않는다.
 * @property minSamples 시간 상한이 있어도 최소한 끝내는 가정 수(결과가 한 가정에 휘둘리지 않도록).
 * @property candidateTargets 대상이 필요한 행동에서 후보로 삼는 대상 수(위협이 큰 순).
 * @property priorBonus NORMAL 정책이 고른 수에 더해 주는 보상(평균 보상 단위). 표본이 적을 때 우연한 차이로 검증된 휴리스틱에서
 *   벗어나지 않도록 하는 사전값이다 — 시뮬레이션이 확실히 더 낫다고 말할 때만 NORMAL의 선택을 뒤집는다.
 * @property mixOpponents 시뮬레이션 속 상대를 한 가지 정책으로 단정하지 않고 가정마다 여러 성격(균형·공격·신중·무작위)으로 섞는다.
 *   상대가 NORMAL이라고만 가정하면 그 습성을 파고드는 수에 과적합해 다른 부류의 상대에게 약해진다.
 */
public data class HardConfig(
    val samples: Int = 40,
    val rolloutDecisions: Int = 14,
    val timeBudget: Duration? = 400.milliseconds,
    val minSamples: Int = 4,
    val candidateTargets: Int = 2,
    val priorBonus: Double = 0.03,
    val mixOpponents: Boolean = true,
) {
    init {
        require(samples >= 1 && rolloutDecisions >= 1 && minSamples in 1..samples && candidateTargets >= 1 && priorBonus >= 0.0)
    }
}

/**
 * HARD AI: 결정화 몬테카를로(설계 §7.5). 결정마다
 * 1) 뷰와 공개 기록에서 상대 손패·덱 순서를 [samples]번 뽑고(진짜 상태는 모른다),
 * 2) 뽑은 가정마다 후보 수를 하나씩 엔진에 적용한 뒤 휴리스틱 정책으로 [HardConfig.rolloutDecisions]번째 결정까지(또는 판이 끝날 때까지) 두어 보고,
 * 3) 후보별 평균 보상이 가장 큰 수를 고른다.
 * 블러핑·도전·막기의 선택이 별도 규칙 없이 시뮬레이션 결과에서 나온다. 같은 가정 집합으로 모든 후보를 비교한다(공통 난수).
 * 입력은 [PlayerView]·이벤트뿐이고, 시뮬레이션 상태는 `determinize`로만 만든다.
 */
internal class HardAgent(
    private val engine: GameEngine,
    private val config: HardConfig,
    personality: Personality,
    seed: Long,
    private val clock: () -> Long = System::nanoTime,
) : AiAgent {
    private val random = Random(seed)
    private val base = HeuristicAgent(AiSettings.of(AiDifficulty.HARD), personality, seed xor BASE_SEED_MIX)

    override fun observe(events: List<VisibleEvent>, view: PlayerView) = base.observe(events, view)

    override fun decide(view: PlayerView, request: DecisionRequest): Command {
        val me = requireNotNull(view.me) { "spectator views cannot decide" }.id
        val generated = candidates(view, request, me)
        // 고를 것이 없거나 하나뿐이면 탐색할 필요가 없다
        if (generated.size <= 1) return generated.firstOrNull() ?: base.decide(view, request)
        // NORMAL 정책의 선택은 후보에서 빠지지 않는다(대상 줄이기로 사라졌더라도 되살린다) — 사전값을 얹을 자리
        val preferred = base.decide(view, request)
        val candidates = if (preferred in generated) generated else generated + preferred

        val startedAt = clock()
        val deadline = config.timeBudget?.let { startedAt + it.inWholeNanoseconds }
        val sampler = HiddenSampler(view, base.history)
        val totals = DoubleArray(candidates.size)
        var finished = 0
        repeat(config.samples) {
            if (finished >= config.minSamples && (stopRequested() || (deadline != null && clock() >= deadline))) return@repeat
            val assignment = sampler.sample(random) ?: return@repeat
            val sampleSeed = random.nextLong()
            val rewards = simulate(view, me, assignment, candidates, sampleSeed, deadline, mustFinish = finished < config.minSamples)
                ?: return@repeat
            rewards.forEachIndexed { i, r -> totals[i] += r }
            finished++
        }
        // 한 가정도 끝내지 못했으면(뷰가 모순이거나 시간이 부족) NORMAL 정책이 정한다
        if (finished == 0) return base.decide(view, request)
        val score = { i: Int -> totals[i] / finished + if (candidates[i] == preferred) config.priorBonus else 0.0 }
        val best = candidates.indices.maxWith(compareBy<Int> { score(it) }.thenBy { -it }) // 동점이면 앞선 후보(요청 순서)
        return candidates[best]
    }

    private fun stopRequested(): Boolean = Thread.currentThread().isInterrupted

    // ---- 후보 --------------------------------------------------------------------------------

    private fun candidates(view: PlayerView, request: DecisionRequest, me: PlayerId): List<Command> {
        val v = view.version
        return when (request) {
            is DecisionRequest.ChooseAction -> request.options.filter { it.selectable }.flatMap { actionCandidates(view, me, it) }
            is DecisionRequest.Respond -> buildList {
                add(Command.Pass(me, v))
                if (request.canChallenge) add(Command.Challenge(me, v))
                request.blockOptions.forEach { add(Command.Block(me, it.role, v)) }
            }
            is DecisionRequest.ChooseRevealCard -> distinctByRole(request.cards).map { Command.RevealCard(me, it.id, v) }
            is DecisionRequest.ChooseInfluenceToLose -> distinctByRole(request.cards).map { Command.LoseInfluence(me, it.id, v) }
            is DecisionRequest.ChooseExchange -> exchangeChoices(request.candidates, request.keepCount).map { Command.ChooseExchange(me, it.map(Card::id), v) }
        }
    }

    private fun actionCandidates(view: PlayerView, me: PlayerId, option: ActionOption): List<Command> {
        val targets = option.validTargets ?: return listOf(Command.DeclareAction(me, option.actionId, null, view.version))
        return targets.sortedByDescending { Valuation.threat(view, it) }.take(config.candidateTargets)
            .map { Command.DeclareAction(me, option.actionId, it, view.version) }
    }

    /** 같은 역할 카드는 어느 것을 내도 같다 — 후보를 줄인다. */
    private fun distinctByRole(cards: List<Card>): List<Card> = cards.distinctBy { it.role }

    /** [keep]장을 남기는 조합. 역할 구성이 같은 조합은 하나로 본다. */
    private fun exchangeChoices(cards: List<Card>, keep: Int): List<List<Card>> {
        val result = mutableListOf<List<Card>>()
        val seen = mutableSetOf<List<String>>()
        fun walk(start: Int, chosen: List<Card>) {
            if (chosen.size == keep) {
                if (seen.add(chosen.map { it.role.value }.sorted())) result += chosen
                return
            }
            for (i in start until cards.size) walk(i + 1, chosen + cards[i])
        }
        walk(0, emptyList())
        return result
    }

    // ---- 시뮬레이션 --------------------------------------------------------------------------

    /**
     * 가정 하나에서 후보마다 끝까지 두어 본 보상. 뷰와 가정이 모순이면 null.
     * 시간 상한을 넘으면(필수 표본이 아닐 때) 이 가정은 버린다 — 일부 후보만 평가된 표본이 비교를 기울이지 않도록.
     */
    private fun simulate(
        view: PlayerView,
        me: PlayerId,
        assignment: HiddenAssignment,
        candidates: List<Command>,
        sampleSeed: Long,
        deadline: Long?,
        mustFinish: Boolean,
    ): DoubleArray? {
        val root = try {
            engine.determinize(view, assignment, sampleSeed)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val rewards = DoubleArray(candidates.size)
        // 가정마다 상대의 성격을 뽑는다(모든 후보가 같은 상대를 만난다 — 공통 난수)
        val styleRandom = Random(sampleSeed)
        val styles = view.seats.filter { it != me }.associateWith { pickStyle(styleRandom) }
        for ((i, candidate) in candidates.withIndex()) {
            if (!mustFinish && deadline != null && clock() >= deadline) return null
            val first = engine.apply(root, candidate) as? ApplyResult.Accepted ?: return null
            var state = first.state
            // 시뮬레이션 안의 좌석들은 모두 NORMAL 정책이고, 공개된 주장 기록은 지금까지 알려진 것에서 이어 간다
            val history = base.history.copy()
            history.observe(engine.projectEvents(first.events, Viewer.Spectator))
            val agents = view.seats.associateWith { seat ->
                val seatSeed = sampleSeed xor seat.value.hashCode().toLong()
                if (seat == me) heuristic(Personality.BALANCED, seatSeed, history) else opponent(styles.getValue(seat), seatSeed, history)
            }
            var steps = 0
            while (!state.isOver && steps < config.rolloutDecisions) {
                val who = engine.pendingDeciders(state).first()
                val seatView = engine.view(state, Viewer.Player(who))
                val command = agents.getValue(who).decide(seatView, checkNotNull(seatView.myDecision))
                val result = engine.apply(state, command) as? ApplyResult.Accepted ?: break // 정책이 어긋나면 그 시점의 형세로 평가
                state = result.state
                history.observe(engine.projectEvents(result.events, Viewer.Spectator))
                steps++
            }
            rewards[i] = reward(engine.view(state, Viewer.Player(me)), me)
        }
        return rewards
    }

    private enum class Style { BALANCED, AGGRESSIVE, CAUTIOUS, RANDOM }

    private fun pickStyle(random: Random): Style {
        if (!config.mixOpponents) return Style.BALANCED
        val x = random.nextDouble()
        return when {
            x < STYLE_BALANCED -> Style.BALANCED
            x < STYLE_BALANCED + STYLE_AGGRESSIVE -> Style.AGGRESSIVE
            x < STYLE_BALANCED + STYLE_AGGRESSIVE + STYLE_CAUTIOUS -> Style.CAUTIOUS
            else -> Style.RANDOM
        }
    }

    private fun heuristic(personality: Personality, seed: Long, history: ClaimHistory): AiAgent =
        HeuristicAgent(AiSettings.of(AiDifficulty.HARD), personality, seed, history)

    private fun opponent(style: Style, seed: Long, history: ClaimHistory): AiAgent = when (style) {
        Style.BALANCED -> heuristic(Personality.BALANCED, seed, history)
        Style.AGGRESSIVE -> heuristic(Personality.AGGRESSIVE, seed, history)
        Style.CAUTIOUS -> heuristic(Personality.CAUTIOUS, seed, history)
        Style.RANDOM -> RandomAgent(seed)
    }

    /** 끝난 판은 순위(우승 1.0), 안 끝났으면 형세(내 영향력·코인이 전체에서 차지하는 몫)로 평가한다. 형세 값은 우승보다 낮게 둔다. */
    private fun reward(view: PlayerView, me: PlayerId): Double {
        val result = view.result
        if (result != null) {
            if (result.winner == me) return 1.0
            val n = view.seats.size
            val rank = result.ranking.indexOf(me).let { if (it < 0) n else it + 1 }
            return RUNNER_UP_SHARE * (n - rank).coerceAtLeast(0) / (n - 1).coerceAtLeast(1)
        }
        val mine = view.me ?: return 0.0
        val myInfluence = mine.hand.count { !it.revealed }
        if (myInfluence == 0) return 0.0
        val myScore = standing(myInfluence, mine.coins)
        val others = view.opponents.filter { it.isAlive }.sumOf { standing(it.hiddenCount, it.coins) }
        return UNFINISHED_CAP * myScore / (myScore + others)
    }

    private fun standing(influence: Int, coins: Int): Double = INFLUENCE_WEIGHT * influence + COIN_WEIGHT * coins

    internal companion object {
        /** 예비·사전값을 내는 NORMAL 정책의 seed를 에이전트 seed와 갈라 놓는 값(테스트가 같은 정책을 재현할 때도 쓴다). */
        const val BASE_SEED_MIX = 0x5DEECE66DL
        const val STYLE_BALANCED = 0.5
        const val STYLE_AGGRESSIVE = 0.15
        const val STYLE_CAUTIOUS = 0.15 // 나머지 0.2는 무작위
        const val RUNNER_UP_SHARE = 0.35
        const val UNFINISHED_CAP = 0.8
        const val INFLUENCE_WEIGHT = 2.0
        const val COIN_WEIGHT = 0.25
    }
}
