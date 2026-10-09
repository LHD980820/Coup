package io.github.lhd980820.coup.presentation.tutorial

import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView

/**
 * 정해진 수를 순서대로 두는 에이전트(튜토리얼 선생). 다른 AI처럼 [PlayerView]와 [DecisionRequest]만 본다.
 * 대본의 다음 수가 지금 결정에 맞지 않거나 합법이 아니면 안전한 기본 수를 두고 대본은 그대로 둔다
 * (카드 공개는 주장한 역할이 있으면 그것을, 상실/교환은 첫 선택지를).
 */
public class ScriptedAgent(script: List<TutorMove>) : AiAgent {
    private val queue = ArrayDeque(script)

    override fun decide(view: PlayerView, request: DecisionRequest): Command {
        val me = checkNotNull(view.me).id
        val v = view.version
        return when (request) {
            is DecisionRequest.ChooseAction -> {
                val move = queue.firstOrNull() as? TutorMove.Act
                val scripted = move?.let { m -> request.options.firstOrNull { it.actionId == m.action && it.selectable } }
                val option = scripted?.also { queue.removeFirst() }
                    ?: request.options.firstOrNull { it.actionId.value == "income" && it.selectable }
                    ?: request.options.first { it.selectable }
                Command.DeclareAction(me, option.actionId, option.validTargets?.firstOrNull(), v)
            }
            is DecisionRequest.Respond -> when (val move = queue.firstOrNull()) {
                TutorMove.Challenge -> if (request.canChallenge) take { Command.Challenge(me, v) } else Command.Pass(me, v)
                is TutorMove.Block ->
                    if (request.blockOptions.any { it.role == move.role }) take { Command.Block(me, move.role, v) } else Command.Pass(me, v)
                TutorMove.Pass -> take { Command.Pass(me, v) }
                else -> Command.Pass(me, v)
            }
            is DecisionRequest.ChooseRevealCard ->
                Command.RevealCard(me, (request.cards.firstOrNull { it.role in request.claimedRoles } ?: request.cards.first()).id, v)
            is DecisionRequest.ChooseInfluenceToLose -> Command.LoseInfluence(me, request.cards.first().id, v)
            is DecisionRequest.ChooseExchange -> Command.ChooseExchange(me, request.candidates.take(request.keepCount).map { it.id }, v)
        }
    }

    private inline fun take(build: () -> Command): Command = build().also { queue.removeFirst() }
}
