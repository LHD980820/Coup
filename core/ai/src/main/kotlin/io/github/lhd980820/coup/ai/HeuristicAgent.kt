package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.ai.belief.Beliefs
import io.github.lhd980820.coup.ai.belief.ClaimHistory
import io.github.lhd980820.coup.ai.policy.Valuation
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rules.BlockPolicy
import io.github.lhd980820.coup.engine.view.ActionOption
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent
import kotlin.math.pow
import kotlin.random.Random

/**
 * EASY / NORMAL AI(설계 §7.4, §7.5). 결정 종류별 휴리스틱 정책 + 신념(카드 카운팅, 주장 기록).
 * 모든 판단은 [PlayerView]와 [VisibleEvent]에서만 나온다.
 */
internal class HeuristicAgent(
    private val settings: AiSettings,
    private val personality: Personality,
    seed: Long,
) : AiAgent {
    private val random = Random(seed)
    private val history = ClaimHistory()

    override fun observe(events: List<VisibleEvent>, view: PlayerView) {
        history.observe(events)
    }

    override fun decide(view: PlayerView, request: DecisionRequest): Command {
        val me = requireNotNull(view.me) { "spectator views cannot decide" }.id
        val beliefs = Beliefs(view, history, settings.countCards, settings.trackClaims)
        val v = view.version
        return when (request) {
            is DecisionRequest.ChooseAction -> chooseAction(view, beliefs, me, request.options)
            is DecisionRequest.Respond -> respond(view, beliefs, me, request)
            is DecisionRequest.ChooseRevealCard -> {
                val proof = request.cards.firstOrNull { it.role in request.claimedRoles }
                Command.RevealCard(me, (proof ?: cheapest(view, request.cards)).id, v)
            }
            is DecisionRequest.ChooseInfluenceToLose -> Command.LoseInfluence(me, cheapest(view, request.cards).id, v)
            is DecisionRequest.ChooseExchange -> Command.ChooseExchange(me, bestHand(view, request.candidates, request.keepCount), v)
        }
    }

    private fun noise() = (random.nextDouble() - 0.5) * settings.noise

    // ---- 행동 선택 ---------------------------------------------------------------------------

    private fun chooseAction(view: PlayerView, beliefs: Beliefs, me: PlayerId, options: List<ActionOption>): Command {
        val scored = options.filter { it.selectable }.mapNotNull { option ->
            val summary = Valuation.summary(view, option.actionId) ?: return@mapNotNull null
            val targets = option.validTargets
            if (targets == null) {
                Triple(option, null as PlayerId?, scoreAction(view, beliefs, option, summary.blockingRoles, null))
            } else {
                val best = targets.maxBy { t -> scoreAction(view, beliefs, option, summary.blockingRoles, t) }
                Triple(option, best, scoreAction(view, beliefs, option, summary.blockingRoles, best))
            }
        }
        val (option, target, _) = scored.maxBy { it.third + noise() }
        return Command.DeclareAction(me, option.actionId, target, view.version)
    }

    private fun scoreAction(
        view: PlayerView,
        beliefs: Beliefs,
        option: ActionOption,
        blockingRoles: Set<RoleId>,
        target: PlayerId?,
    ): Double {
        val summary = Valuation.summary(view, option.actionId) ?: return Double.NEGATIVE_INFINITY
        var score = Valuation.actionGain(summary) - option.cost * 0.6
        if (target != null) {
            score += Valuation.threat(view, target) * 0.5
            if (option.actionId.value == "steal") {
                val coins = view.opponent(target)?.coins ?: 0
                score += minOf(2, coins) - 2.0 // 가져올 코인이 적으면 가치가 떨어진다
            }
        }

        // 막힐 위험
        if (blockingRoles.isNotEmpty()) {
            val blockers = when (summary.blockPolicy) {
                BlockPolicy.TARGET_ONLY -> listOfNotNull(target)
                BlockPolicy.ANY_OTHER_PLAYER -> view.opponents.filter { it.isAlive }.map { it.id }
                BlockPolicy.NONE -> emptyList()
            }
            val pNotBlocked = blockers.fold(1.0) { acc, b -> acc * (1 - (beliefs.pHolds(b, blockingRoles) * 0.85 + 0.05)) }
            score = score * pNotBlocked - option.cost * (1 - pNotBlocked) * 0.5
        }

        // 블러핑 위험
        if (!option.iHoldClaimedRole) {
            if (!settings.allowBluff) return Double.NEGATIVE_INFINITY
            val opponents = view.opponents.count { it.isAlive }
            val suspicion = settings.baseSuspicion + 0.35 * beliefs.publiclyExhausted(option.claimedRoles)
            val pChallenged = 1 - (1 - suspicion * (0.5 + personality.challengeAggression)).pow(opponents)
            val risk = pChallenged * Valuation.myLossCost(view)
            score -= risk * settings.bluffCaution * (1.4 - personality.bluffTendency - personality.riskTolerance * 0.4)
        }
        return score
    }

    // ---- 응답 -----------------------------------------------------------------------------

    private fun respond(view: PlayerView, beliefs: Beliefs, me: PlayerId, request: DecisionRequest.Respond): Command {
        val v = view.version
        val pending = request.pending
        val blockingWindow = request.windowKind == WindowKind.BLOCK_CHALLENGE
        val claimant = if (blockingWindow) checkNotNull(pending.blockedBy).blocker else pending.actor
        val claimed = if (blockingWindow) setOf(checkNotNull(pending.blockedBy).role) else pending.claimedRoles
        val summary = Valuation.summary(view, pending.actionId)
        val gain = summary?.let { Valuation.actionGain(it) } ?: 1.0

        // 내가 막을 수 있으면: 역할이 있으면 막고, 없으면 상황에 따라 블러핑 막기
        if (request.blockOptions.isNotEmpty()) {
            val held = request.blockOptions.firstOrNull { it.iHoldRole }
            val iAmTarget = pending.target == me
            val lethal = iAmTarget && summary?.targeted == true && summary.cost > 0 // 암살형
            if (held != null && (iAmTarget || random.nextDouble() < 0.8)) return Command.Block(me, held.role, v)
            if (held == null && settings.allowBluff) {
                val bluff = request.blockOptions.filterNot { beliefs.impossible(setOf(it.role)) }.firstOrNull()
                if (bluff != null) {
                    val lastCard = Valuation.myLossCost(view) > 5
                    // 마지막 카드가 암살당하면 어차피 탈락: 블러핑 막기 외엔 잃을 게 없다
                    if (lethal && lastCard) return Command.Block(me, bluff.role, v)
                    val exposure = beliefs.publiclyExhausted(setOf(bluff.role))
                    val willingness = personality.bluffTendency * (if (iAmTarget) 0.6 else 0.15) * (1 - exposure)
                    if (!lethal && random.nextDouble() < willingness) return Command.Block(me, bluff.role, v)
                }
            }
        }

        if (request.canChallenge) {
            if (beliefs.impossible(claimed)) return Command.Challenge(me, v) // 확정 블러핑
            // 마지막 카드로 치명적 행동(암살형)을 당한 사람은 역할이 없어도 막을 수밖에 없다 → 그 주장은 정보가 없다.
            // (이걸 믿으면 "암살 → 블러핑 막기 → 통과"가 영원히 반복된다: 회귀 테스트 AiBehaviorTest 참고)
            val desperateBlock = blockingWindow &&
                summary?.targeted == true && (summary.cost) > 0 &&
                pending.target == claimant && (view.opponent(claimant)?.hiddenCount ?: 0) <= 1
            val pHolds = beliefs.pHolds(claimant, claimed, ignoreClaims = desperateBlock)
            val stake = when {
                blockingWindow && pending.actor == me -> gain // 내 행동이 막히는 중
                pending.target == me && summary?.targeted == true && summary.cost > 0 -> Valuation.myLossCost(view) // 나를 암살
                pending.target == me -> gain
                else -> gain * 0.4 // 남의 이득은 간접적으로만 손해
            }
            val win = stake + 3.0 // 상대가 카드를 잃는다
            val lose = Valuation.myLossCost(view)
            val ev = (1 - pHolds) * win - pHolds * lose
            val threshold = 1.5 - personality.challengeAggression * 2.0
            if (ev + noise() > threshold) return Command.Challenge(me, v)
        }
        return Command.Pass(me, v)
    }

    // ---- 카드 선택 ---------------------------------------------------------------------------

    private fun cheapest(view: PlayerView, cards: List<Card>): Card = cards.minBy { Valuation.roleValue(view, it.role) }

    /** 가치가 높은 카드를 남기되, 같은 역할 두 장보다는 서로 다른 역할을 선호한다. */
    private fun bestHand(view: PlayerView, candidates: List<Card>, keep: Int): List<CardId> {
        val chosen = mutableListOf<Card>()
        val remaining = candidates.sortedByDescending { Valuation.roleValue(view, it.role) }.toMutableList()
        while (chosen.size < keep) {
            val next = remaining.firstOrNull { c -> chosen.none { it.role == c.role } } ?: remaining.first()
            chosen += next
            remaining -= next
        }
        return chosen.map { it.id }
    }
}
