package io.github.lhd980820.coup.presentation

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.SessionSnapshot
import io.github.lhd980820.coup.runtime.session.SubmitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 게임 화면의 상태 홀더(설계 §10.4). Android `ViewModel`은 이것을 감싸기만 하면 된다.
 * - 세션의 스냅샷/이벤트/연결을 모아 [GameUiState]로 만든다([GameUiMapper]).
 * - 사용자 의도(intent)를 엔진 명령으로 바꿔 보낸다. "행동 고르기 -> 대상 고르기", "카드 고르기 -> 확정" 같은
 *   여러 단계의 입력은 여기서만 관리한다(엔진은 모른다). 결정이 바뀌면 선택 상태는 자동으로 비워진다.
 * - 명령을 보내는 동안 [GameUiState.busy]로 버튼을 잠가 중복 탭을 막는다.
 *
 * 의도 메서드는 `suspend`다. 화면(ViewModel)이 `viewModelScope.launch { ... }`로 부르면 된다.
 */
public class GameController(
    private val session: GameSession,
    private val scope: CoroutineScope,
    private val maxLog: Int = DEFAULT_MAX_LOG,
    private val busyTimeoutMillis: Long = DEFAULT_BUSY_TIMEOUT_MILLIS,
) {
    private val lock = Any()
    private var snapshot: SessionSnapshot? = null
    private var connection = session.connection.value
    private var interaction = Interaction()
    private var interactionKey: Any? = null
    private var inFlight = false

    /** 명령이 수락된 직후, 그 버전보다 새로운 상태가 도착할 때까지 입력을 잠근다(옛 버전으로 두 번째 탭이 나가는 것을 막는다). */
    private var awaitingAfterVersion: Long? = null
    private val busy get() = inFlight || awaitingAfterVersion != null
    private val log = ArrayDeque<LogEntry>()

    private val _state = MutableStateFlow<GameUiState?>(null)
    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 256)
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    private val jobs = mutableListOf<Job>()

    /** 첫 스냅샷이 오기 전에는 null. */
    public val state: StateFlow<GameUiState?> = _state.asStateFlow()
    public val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()
    public val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    init {
        jobs += scope.launch {
            session.snapshot.collect { snap ->
                if (snap != null) update { onSnapshot(snap) }
            }
        }
        jobs += scope.launch { session.connection.collect { c -> update { connection = c } } }
        jobs += scope.launch {
            session.events.collect { event ->
                EventMapper.log(event)?.let { entry ->
                    update {
                        log.addLast(entry)
                        while (log.size > maxLog) log.removeFirst()
                    }
                }
                EventMapper.effect(event)?.let { _effects.emit(it) }
            }
        }
    }

    // ---- 의도(intent) -------------------------------------------------------------------------

    /** 행동 버튼. 대상이 필요하면 대상 선택 단계로 넘어가고, 아니면 바로 보낸다. */
    public suspend fun chooseAction(actionId: ActionId) {
        val button = (state.value?.decision as? DecisionUi.Actions)?.buttons?.firstOrNull { it.actionId == actionId && it.enabled } ?: return
        if (button.needsTarget) {
            update { interaction = interaction.copy(pendingAction = actionId) }
        } else {
            send { me -> Command.DeclareAction(me, actionId, null, version()) }
        }
    }

    public suspend fun chooseTarget(target: PlayerId) {
        val pick = state.value?.decision as? DecisionUi.PickTarget ?: return
        if (target !in pick.targets) return
        send { me -> Command.DeclareAction(me, pick.actionId, target, version()) }
    }

    public fun cancelTarget() {
        update { interaction = interaction.copy(pendingAction = null) }
    }

    public suspend fun pass() {
        if (state.value?.decision !is DecisionUi.Respond) return
        send { me -> Command.Pass(me, version()) }
    }

    public suspend fun challenge() {
        val respond = state.value?.decision as? DecisionUi.Respond ?: return
        if (!respond.canChallenge) return
        send { me -> Command.Challenge(me, version()) }
    }

    public suspend fun block(role: RoleId) {
        val respond = state.value?.decision as? DecisionUi.Respond ?: return
        if (respond.blocks.none { it.role == role }) return
        send { me -> Command.Block(me, role, version()) }
    }

    /** 카드 선택(공개/상실): 눌러서 고른다. 다시 누르면 해제. 확정은 [confirmCard]. */
    public fun selectCard(cardId: CardId) {
        val pick = state.value?.decision as? DecisionUi.PickCard ?: return
        if (pick.cards.none { it.id == cardId }) return
        update { interaction = interaction.copy(selectedCards = if (cardId in interaction.selectedCards) emptySet() else setOf(cardId)) }
    }

    public suspend fun confirmCard() {
        val pick = state.value?.decision as? DecisionUi.PickCard ?: return
        val chosen = pick.selected ?: return
        send { me ->
            when (pick.purpose) {
                CardPurpose.REVEAL -> Command.RevealCard(me, chosen, version())
                CardPurpose.LOSE -> Command.LoseInfluence(me, chosen, version())
            }
        }
    }

    /** 교환: 남길 카드를 토글한다. 정해진 장수를 넘겨서 고를 수는 없다. */
    public fun toggleExchange(cardId: CardId) {
        val pick = state.value?.decision as? DecisionUi.PickExchange ?: return
        if (pick.candidates.none { it.id == cardId }) return
        update {
            val current = interaction.selectedCards
            interaction = interaction.copy(
                selectedCards = when {
                    cardId in current -> current - cardId
                    current.size < pick.keepCount -> current + cardId
                    else -> current
                },
            )
        }
    }

    public suspend fun confirmExchange() {
        val pick = state.value?.decision as? DecisionUi.PickExchange ?: return
        if (!pick.canConfirm) return
        val keep = pick.candidates.filter { it.selected }.map { it.id }
        send { me -> Command.ChooseExchange(me, keep, version()) }
    }

    public suspend fun concede() {
        send { me -> Command.Concede(me, version()) }
    }

    public fun close() {
        jobs.forEach { it.cancel() }
        session.close()
    }

    // ---- 내부 ---------------------------------------------------------------------------------

    private fun version(): Long = checkNotNull(snapshot).view.version

    private suspend fun send(build: (PlayerId) -> Command) {
        var submittedVersion = 0L
        val me = synchronized(lock) {
            val snap = snapshot ?: return
            if (busy) return
            inFlight = true
            submittedVersion = snap.view.version
            recompute()
            snap.view.me?.id
        } ?: run { update { inFlight = false }; return }

        val result = try {
            session.submit(build(me))
        } catch (e: Throwable) {
            update { inFlight = false }
            throw e
        }
        when (result) {
            SubmitResult.Ok -> {
                update {
                    inFlight = false
                    interaction = Interaction()
                    interactionKey = null
                    // 새 상태가 이미 도착했을 수도 있다(그러면 잠글 필요 없음)
                    if ((snapshot?.view?.version ?: 0L) <= submittedVersion) awaitingAfterVersion = submittedVersion
                }
                scope.launch {
                    delay(busyTimeoutMillis)
                    update { if (awaitingAfterVersion == submittedVersion) awaitingAfterVersion = null }
                }
            }
            is SubmitResult.Rejected -> {
                update { inFlight = false; interaction = Interaction() }
                _messages.emit(UiMessage.Rejected(result.reason))
            }
            SubmitResult.NetworkError -> {
                update { inFlight = false }
                _messages.emit(UiMessage.NetworkError)
            }
        }
    }

    private fun onSnapshot(snap: SessionSnapshot) {
        snapshot = snap
        awaitingAfterVersion?.let { if (snap.view.version > it) awaitingAfterVersion = null }
        // 내가 내려야 할 결정이 바뀌면(새 버전이거나 종류가 달라지면) 화면 위 선택 상태를 비운다.
        val key = snap.view.version to snap.view.myDecision?.let { it::class }
        if (key != interactionKey) {
            interaction = Interaction()
            interactionKey = key
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            recompute()
        }
    }

    private fun recompute() {
        val snap = snapshot ?: return
        _state.value = GameUiMapper.map(snap, connection, interaction, busy, log.toList())
    }

    public companion object {
        public const val DEFAULT_MAX_LOG: Int = 100

        /** 수락된 명령의 새 상태가 이 시간 안에 오지 않으면(유실 등) 잠금을 푼다. */
        public const val DEFAULT_BUSY_TIMEOUT_MILLIS: Long = 3_000
    }
}
