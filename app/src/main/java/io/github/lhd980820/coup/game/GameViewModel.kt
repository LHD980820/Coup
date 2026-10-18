package io.github.lhd980820.coup.game

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
) : ViewModel(), GameActions {
    /** 게임을 돌리는 코루틴 범위. 기본은 [viewModelScope]이고, 테스트는 메인 루퍼에 의존하지 않는 범위를 넘긴다. */
    private val gameScope: CoroutineScope = scope ?: viewModelScope

    /** 게임 중이면 non-null, 설정 화면이면 null. */
    var controller by mutableStateOf<GameController?>(null)
        private set

    fun start(config: SinglePlayerConfig) {
        controller?.close()
        val session = SinglePlayerSessionFactory.create(config, GameEngines.create(), gameScope, aiDispatcher = aiDispatcher)
        controller = GameController(session, gameScope)
    }

    fun quit() {
        controller?.close()
        controller = null
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
}
