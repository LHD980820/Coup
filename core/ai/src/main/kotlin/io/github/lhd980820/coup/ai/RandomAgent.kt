package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import kotlin.random.Random

/**
 * 합법 수 중에서 무작위로 고르는 기준선 에이전트. 런타임 테스트와 AI 평가 하네스의 비교 대상으로 쓴다.
 * 최소한의 상식만 있다: 공개를 요구받으면 주장 역할 카드가 있으면 그것을 낸다.
 */
public class RandomAgent(
    seed: Long,
    private val challengeRate: Double = 0.15,
    private val blockRate: Double = 0.3,
) : AiAgent {
    private val random = Random(seed)

    override fun decide(view: PlayerView, request: DecisionRequest): Command {
        val me = requireNotNull(view.me) { "spectator views cannot decide" }.id
        val v = view.version
        return when (request) {
            is DecisionRequest.ChooseAction -> {
                val option = request.options.filter { it.selectable }.random(random)
                Command.DeclareAction(me, option.actionId, option.validTargets?.random(random), v)
            }
            is DecisionRequest.Respond -> when {
                request.canChallenge && random.nextDouble() < challengeRate -> Command.Challenge(me, v)
                request.blockOptions.isNotEmpty() && random.nextDouble() < blockRate ->
                    Command.Block(me, request.blockOptions.random(random).role, v)
                else -> Command.Pass(me, v)
            }
            is DecisionRequest.ChooseRevealCard -> {
                val proof = request.cards.firstOrNull { it.role in request.claimedRoles }
                Command.RevealCard(me, (proof ?: request.cards.random(random)).id, v)
            }
            is DecisionRequest.ChooseInfluenceToLose -> Command.LoseInfluence(me, request.cards.random(random).id, v)
            is DecisionRequest.ChooseExchange ->
                Command.ChooseExchange(me, request.candidates.shuffled(random).take(request.keepCount).map { it.id }, v)
        }
    }
}
