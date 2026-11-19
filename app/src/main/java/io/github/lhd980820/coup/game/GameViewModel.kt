package io.github.lhd980820.coup.game

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig
import io.github.lhd980820.coup.presentation.tutorial.GuidedSession
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.presentation.tutorial.TutorialLesson
import io.github.lhd980820.coup.presentation.tutorial.TutorialSessionFactory
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerSessionFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 화면이 부르는 의도. 컨트롤러가 사용할 수 없는 의도는 조용히 무시한다. */
internal interface GameActions {
    fun chooseAction(id: ActionId)
    fun chooseTarget(id: PlayerId)
    fun cancelTarget()
    fun pass()
    fun challenge()
    fun block(role: RoleId)
    fun selectCard(id: CardId)
    fun confirmCard()
    fun toggleExchange(id: CardId)
    fun confirmExchange()
    fun concede()
}

/**
 * 싱글플레이 한 판의 수명을 가진다(화면 회전에도 유지). 게임 로직은 모두 코어의 [GameController]에 있고 여기서는 시작/종료와 의도 전달만 한다.
 * [aiDispatcher]는 테스트에서 바꿀 수 있다.
 */
internal class GameViewModel(
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope? = null,
    val tutorialStore: TutorialStore = InMemoryTutorialStore(),
    /** AI가 화면의 연출을 기다리는 최대 시간(테스트에서 줄인다). */
    private val stageWaitMaxMs: Long = STAGE_WAIT_MAX_MS,
) : ViewModel(), GameActions {
    /** 게임을 돌리는 코루틴 범위. 기본은 [viewModelScope]이고, 테스트는 메인 루퍼에 의존하지 않는 범위를 넘긴다. */
    private val gameScope: CoroutineScope = scope ?: viewModelScope

    /** 게임 중이면 non-null, 설정 화면이면 null. */
    var controller by mutableStateOf<GameController?>(null)
        private set

    /** 튜토리얼 중이면 그 과정과 진행 상황, 아니면 null. */
    var tutorial by mutableStateOf<GuidedSession?>(null)
        private set
    var tutorialLesson by mutableStateOf<TutorialLesson?>(null)
        private set

    /** 튜토리얼 목록을 보여줄 때 쓴다(끝낸 과정 표시). */
    var completedLessons by mutableStateOf(tutorialStore.completed())
        private set

    /** 화면이 극적인 순간을 보여 주는 중인가(게임 화면이 알린다). AI는 그동안 다음 수를 두지 않는다. */
    private val stageBusy = MutableStateFlow(false)

    fun setStageBusy(busy: Boolean) {
        stageBusy.value = busy
    }

    /**
     * AI가 수를 두기 직전에 부른다: 화면이 방금 일어난 일을 다 보여 줄 때까지 기다리고, 끝나면 숨을 고른 뒤 돌아온다.
     * 화면이 알려 주지 않아도(꺼졌거나 멈췄어도) [stageWaitMaxMs]를 넘겨 기다리지는 않는다.
     */
    internal suspend fun awaitStage() {
        if (!stageBusy.value) return
        withTimeoutOrNull(stageWaitMaxMs) { stageBusy.first { !it } }
        delay(AFTER_STAGE_MS)
    }

    fun start(config: SinglePlayerConfig) {
        controller?.close()
        val session = SinglePlayerSessionFactory.create(config, GameEngines.create(), gameScope, aiDispatcher = aiDispatcher, awaitStage = ::awaitStage)
        controller = GameController(session, gameScope)
    }

    /** 이미 열린 세션(멀티플레이)으로 게임 화면을 연다. 세션은 [quit]에서 닫힌다. */
    fun startSession(session: GameSession) {
        controller?.close()
        controller = GameController(session, gameScope)
    }

    fun startTutorial(lesson: TutorialLesson, humanName: String, tutorName: String) {
        controller?.close()
        val guided = TutorialSessionFactory.create(
            lesson, GameEngines.create(), gameScope, aiDispatcher = aiDispatcher, humanName = humanName, tutorName = tutorName, awaitStage = ::awaitStage,
        )
        tutorial = guided
        tutorialLesson = lesson
        controller = GameController(guided, gameScope)
    }

    /** 튜토리얼 과정을 끝냈다고 기록한다(여러 번 불러도 같다). */
    fun completeTutorial(lessonId: String) {
        tutorialStore.markCompleted(lessonId)
        completedLessons = tutorialStore.completed()
    }

    fun quit() {
        controller?.close()
        stageBusy.value = false
        controller = null
        tutorial = null
        tutorialLesson = null
    }

    private fun run(block: suspend GameController.() -> Unit) {
        val c = controller ?: return
        gameScope.launch { c.block() }
    }

    override fun chooseAction(id: ActionId) = run { chooseAction(id) }
    override fun chooseTarget(id: PlayerId) = run { chooseTarget(id) }
    override fun cancelTarget() { controller?.cancelTarget() }
    override fun pass() = run { pass() }
    override fun challenge() = run { challenge() }
    override fun block(role: RoleId) = run { block(role) }
    override fun selectCard(id: CardId) { controller?.selectCard(id) }
    override fun confirmCard() = run { confirmCard() }
    override fun toggleExchange(id: CardId) { controller?.toggleExchange(id) }
    override fun confirmExchange() = run { confirmExchange() }
    override fun concede() = run { concede() }

    override fun onCleared() {
        controller?.close()
    }

    internal companion object {
        /** 연출을 기다리는 최대 시간: 극적인 순간 셋(도전·공개·탈락)이 이어져도 5초가 안 된다. */
        const val STAGE_WAIT_MAX_MS = 6_000L

        /** 연출이 끝난 뒤 다음 수까지 숨 고르는 시간. */
        const val AFTER_STAGE_MS = 350L
    }
}
