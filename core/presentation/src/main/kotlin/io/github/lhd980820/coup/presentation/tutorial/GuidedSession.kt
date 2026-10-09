package io.github.lhd980820.coup.presentation.tutorial

import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.LocalGameSession
import io.github.lhd980820.coup.runtime.session.LocalPlayer
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 튜토리얼 진행 상황. [messageKey]는 지금 보여줄 안내, [offScript]는 지시와 다른 수를 시도한 횟수(화면이 흔들기 등으로 알린다). */
public data class TutorialProgress(
    val lessonId: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val messageKey: String,
    val completed: Boolean,
    val offScript: Int = 0,
)

/**
 * 어떤 [GameSession]이든 감싸서 튜토리얼 단계에 맞는 수만 통과시킨다. [GameController]는 그대로 쓰면 된다
 * (거절은 `UiMessage.Rejected`로 나온다). 과정이 끝나면 제한 없이 자유롭게 둘 수 있다.
 */
public class GuidedSession(private val inner: GameSession, private val lesson: TutorialLesson) : GameSession by inner {
    private val _progress = MutableStateFlow(
        TutorialProgress(lesson.id, 0, lesson.steps.size, lesson.steps.first().messageKey, completed = false),
    )
    public val progress: StateFlow<TutorialProgress> = _progress.asStateFlow()

    override suspend fun submit(command: Command): SubmitResult {
        val now = _progress.value
        val guarded = !now.completed && command !is Command.Concede
        if (guarded && !lesson.steps[now.stepIndex].expect.accepts(command)) {
            _progress.update { it.copy(offScript = it.offScript + 1) }
            return SubmitResult.Rejected(Rejection.NOT_YOUR_DECISION)
        }
        val result = inner.submit(command)
        if (guarded && result is SubmitResult.Ok) {
            _progress.update { p ->
                val next = p.stepIndex + 1
                if (next >= lesson.steps.size) {
                    p.copy(stepIndex = lesson.steps.size, messageKey = lesson.completionKey, completed = true)
                } else {
                    p.copy(stepIndex = next, messageKey = lesson.steps[next].messageKey)
                }
            }
        }
        return result
    }

    override suspend fun concede(): SubmitResult = submit(Command.Concede(me!!))
}

public object TutorialSessionFactory {
    /** 선생(스크립트 AI)과 사용자의 2인 로컬 게임을 열고 단계 안내가 붙은 세션을 돌려준다. 시간 제한은 없다. */
    public fun create(
        lesson: TutorialLesson,
        engine: GameEngine,
        scope: CoroutineScope,
        clock: Clock = Clock.System,
        aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
        humanName: String = "Player",
        tutorName: String = "Tutor",
        agent: AiAgent = ScriptedAgent(lesson.tutorScript),
    ): GuidedSession {
        val setup = GameSetup(
            gameId = "tutorial-${lesson.id}",
            seats = listOf(TutorialLesson.HUMAN, TutorialLesson.TUTOR),
            ruleSetConfig = BuiltinRules.classicConfig(),
            seed = lesson.seed,
            firstPlayer = lesson.firstPlayer,
        )
        val local = LocalGameSession(
            engine = engine,
            setup = setup,
            players = listOf(LocalPlayer(TutorialLesson.HUMAN, humanName), LocalPlayer(TutorialLesson.TUTOR, tutorName, agent)),
            timeoutPolicy = TimeoutPolicy.None,
            clock = clock,
            scope = scope,
            aiDispatcher = aiDispatcher,
        )
        return GuidedSession(local, lesson)
    }
}
