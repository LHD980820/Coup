package io.github.lhd980820.coup.shell.lobby

import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.BotStyle
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomRules
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.SeatKind
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class WaitingSeat(
    val id: String,
    val name: String,
    val kind: SeatKind,
    val ready: Boolean,
    val isHost: Boolean,
    val isMe: Boolean,
    val botLevel: BotLevel?,
    val botStyle: BotStyle?,
    /** 방장이 이 자리를 비울 수 있다(사람은 내보내기, 봇은 빼기). */
    val removable: Boolean,
)

public data class WaitingRoomUiState(
    val roomId: String,
    val title: String,
    val visibility: Visibility,
    /** 비공개 방의 참가 코드(방 안에 있는 사람에게만 보인다). */
    val joinCode: String?,
    val seats: List<WaitingSeat>,
    val maxPlayers: Int,
    val emptySeats: Int,
    val isHost: Boolean,
    val myReady: Boolean,
    val ruleSetConfig: RuleSetConfig,
    /** 이 구성의 게임이 레이팅에 반영되는가(D5). */
    val rated: Boolean,
    /** 지금 시작할 수 없는 이유(비어 있으면 시작 가능). 방장 화면의 안내에 쓴다. */
    val blockers: List<RoomError>,
    val canStart: Boolean,
    /** 요청 처리 중(버튼 잠금). */
    val busy: Boolean = false,
    /** 게임을 여는 중(방장: 문서 생성·복구, 게스트: 방장을 기다리는 중). */
    val launching: Boolean = false,
    /** 내가 앉은 게임이 진행 중이다(게임 화면을 나왔거나 연결이 끊겼다면 [WaitingRoomModel.rejoin]으로 돌아간다). */
    val gameInProgress: Boolean = false,
    val problem: LobbyProblem? = null,
)

public sealed interface WaitingRoomEvent {
    /** 내가 방을 나갔다. */
    public data object Left : WaitingRoomEvent

    /** 방장이 방을 닫았다(방장이 나갔다). */
    public data object RoomClosed : WaitingRoomEvent

    /** 방장이 나를 내보냈다. */
    public data object Kicked : WaitingRoomEvent

    /** 게임이 열렸다. 화면은 [session]으로 게임 화면을 연다. 세션을 닫는 것은 화면의 몫이다. */
    public data class GameStarted(val session: GameSession, val gameId: String) : WaitingRoomEvent
}

/**
 * 대기실 한 화면의 상태 홀더. [run]이 방 문서를 구독해 [state]를 만들고(누가 들어오고 나갔는지, 준비, 방장의 변경이 모두 여기로 온다),
 * 의도 메서드가 [RoomService]를 통해 방을 바꾼다. 규칙은 모두 코어의 [RoomRules]가 정하므로 여기에는 보여줄 것을 고르는 일만 있다.
 *
 * 게임이 시작되면: 방장은 [start]가 [GameLauncher.host]로, 게스트는 방이 PLAYING이 되는 것을 보고 [GameLauncher.join]으로 세션을 열어
 * [WaitingRoomEvent.GameStarted]를 내보낸다. 게임이 끝나 대기실로 돌아오면 화면이 [gameFinished]를 부른다.
 */
public class WaitingRoomModel(
    public val roomId: String,
    private val me: LobbyUser,
    private val service: RoomService,
    private val registry: RuleSetRegistry,
    private val launcher: GameLauncher,
) {
    private val _state = MutableStateFlow<WaitingRoomUiState?>(null)

    /** 첫 방 스냅샷이 오기 전에는 null. */
    public val state: StateFlow<WaitingRoomUiState?> = _state.asStateFlow()

    private val _events = Channel<WaitingRoomEvent>(Channel.BUFFERED)
    public val events: Flow<WaitingRoomEvent> = _events.receiveAsFlow()

    private var everSeated = false

    /** 이미 세션을 연(또는 연 중인) 게임. 같은 게임을 두 번 열지 않는다. */
    private var handledGameId: String? = null

    @Volatile
    private var busy = false

    /** 방장이 지금 게임을 시작하는 중이다(그 사이 방이 PLAYING으로 보여도 "복구"로 착각하지 않게). */
    @Volatile
    private var starting = false

    @Volatile
    private var lastRoom: Room? = null

    /** 화면이 보이는 동안 방을 구독한다. 방이 닫히거나 내가 나가면 끝난다. */
    public suspend fun run() {
        coroutineScope {
            service.observe(roomId)
                .takeWhile { room ->
                    val seated = room?.seats?.any { it.id == me.uid } == true
                    when {
                        room == null || room.status == RoomStatus.CLOSED -> {
                            _events.send(WaitingRoomEvent.RoomClosed)
                            false
                        }
                        // 앉아 있다가 사라졌다 = 내보내졌다(입장 직후의 오래된 스냅샷은 앉은 적이 없으므로 무시한다)
                        !seated && everSeated -> {
                            _events.send(WaitingRoomEvent.Kicked)
                            false
                        }
                        else -> {
                            everSeated = everSeated || seated
                            true
                        }
                    }
                }
                .collect { room ->
                    if (room == null) return@collect
                    lastRoom = room
                    _state.update { render(room, it) }
                    // 진행 중인 게임이 있으면 들어간다: 게스트는 방장이 게임을 열었을 때와 다시 들어올 때(앱 재시작) 모두,
                    // 방장은 앱을 다시 켜서 방에 돌아왔을 때(권한자 백업에서 복구). 방장이 막 시작하는 중이면 start()가 맡는다.
                    val gameId = room.gameId
                    val seated = room.seats.any { it.id == me.uid }
                    val isHost = room.hostUid == me.uid
                    if (seated && room.status == RoomStatus.PLAYING && gameId != null && handledGameId != gameId && !(isHost && starting)) {
                        handledGameId = gameId
                        launch { enterGame(room, gameId) }
                    }
                }
        }
    }

    private fun render(room: Room, previous: WaitingRoomUiState?): WaitingRoomUiState {
        val isHost = room.hostUid == me.uid
        val blockers = RoomRules.startBlockers(room, registry)
        return WaitingRoomUiState(
            roomId = room.id,
            title = room.title,
            visibility = room.visibility,
            joinCode = room.joinCode,
            seats = room.seats.map {
                WaitingSeat(
                    id = it.id,
                    name = it.displayName,
                    kind = it.kind,
                    ready = it.ready,
                    isHost = it.id == room.hostUid,
                    isMe = it.id == me.uid,
                    botLevel = it.botLevel,
                    botStyle = it.botStyle,
                    removable = isHost && it.id != room.hostUid && room.status == RoomStatus.WAITING,
                )
            },
            maxPlayers = room.maxPlayers,
            emptySeats = (room.maxPlayers - room.seats.size).coerceAtLeast(0),
            isHost = isHost,
            myReady = room.seats.firstOrNull { it.id == me.uid }?.ready ?: false,
            ruleSetConfig = room.ruleSetConfig,
            rated = room.isRated,
            blockers = blockers,
            canStart = isHost && blockers.isEmpty(),
            busy = previous?.busy ?: false,
            launching = previous?.launching ?: false,
            gameInProgress = room.status == RoomStatus.PLAYING && room.gameId != null && room.seats.any { it.id == me.uid },
            problem = previous?.problem,
        )
    }

    // ---- 의도 ---------------------------------------------------------------------------------

    public suspend fun toggleReady() {
        val ready = _state.value?.myReady ?: return
        change { service.setReady(roomId, me.uid, !ready) }
    }

    public suspend fun leave() {
        change(onOk = { _events.send(WaitingRoomEvent.Left) }) { service.leave(roomId, me.uid) }
    }

    public suspend fun kick(uid: String) {
        change { service.kick(roomId, me.uid, uid) }
    }

    public suspend fun addBot(level: BotLevel = BotLevel.NORMAL, style: BotStyle = BotStyle.BALANCED) {
        change { service.addBot(roomId, me.uid, level, style) }
    }

    public suspend fun removeBot(botId: String) {
        change { service.removeBot(roomId, me.uid, botId) }
    }

    public suspend fun setMaxPlayers(maxPlayers: Int) {
        change { service.setMaxPlayers(roomId, me.uid, maxPlayers) }
    }

    public suspend fun updateRules(config: RuleSetConfig) {
        change { service.updateRules(roomId, me.uid, config) }
    }

    public fun dismissProblem() {
        _state.update { it?.copy(problem = null) }
    }

    /** 방장: 게임을 시작한다. 방이 PLAYING이 된 뒤 게임을 열지 못하면 방을 대기실로 되돌린다. */
    public suspend fun start() {
        if (busy) return
        setBusy(true, launching = true)
        starting = true
        try {
            when (val started = service.start(roomId, me.uid)) {
                is RoomResult.Err -> report(LobbyProblem.Room(started.error))
                is RoomResult.Ok -> {
                    val plan = started.value
                    handledGameId = plan.gameId
                    try {
                        val session = launcher.host(plan)
                        _events.send(WaitingRoomEvent.GameStarted(session, plan.gameId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TransportException) {
                        reopenAfterFailedLaunch()
                        report(LobbyProblem.Network)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            report(LobbyProblem.Network)
        } finally {
            starting = false
            setBusy(false, launching = false)
        }
    }

    /**
     * 진행 중인 게임으로 다시 들어간다(게임 화면을 나왔거나, 방장과 연결이 끊겼다가 돌아왔을 때). 방장이면 마지막 게시 상태에서 복구한다.
     * 진행 중인 게임이 없으면 아무 일도 하지 않는다.
     */
    public suspend fun rejoin() {
        val room = lastRoom ?: return
        val gameId = room.gameId ?: return
        if (room.status != RoomStatus.PLAYING || room.seats.none { it.id == me.uid } || _state.value?.launching == true) return
        handledGameId = gameId
        enterGame(room, gameId)
    }

    /** 게임 화면을 닫고 대기실로 돌아왔다. 방장은 방을 다시 대기 상태로 돌려 다음 판을 준비한다(게스트는 다시 준비해야 한다). */
    public suspend fun gameFinished() {
        val s = _state.value ?: return
        if (!s.isHost) return
        try {
            val result = service.endGame(roomId, me.uid)
            if (result is RoomResult.Err) report(LobbyProblem.Room(result.error))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            report(LobbyProblem.Network)
        }
    }

    private suspend fun enterGame(room: Room, gameId: String) {
        setBusy(busy, launching = true)
        val session: GameSession?
        try {
            session = if (room.hostUid == me.uid) launcher.resumeHost(room) else launcher.join(room, PlayerId(me.uid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            handledGameId = null // 방이 다시 갱신되거나 "게임으로 돌아가기"를 누르면 다시 시도한다
            report(LobbyProblem.Network)
            return
        } finally {
            // 알리기 전에 푼다 — 받는 쪽이 곧바로 다시 들어가기를 눌러도 "여는 중"에 막히지 않게
            setBusy(busy, launching = false)
        }
        if (session == null) {
            // 방장: 게임은 이미 끝나 결과까지 기록됐다(대기실로 돌리기 전에 앱이 꺼졌다) -> 지금 방을 대기 상태로 돌린다
            try {
                service.endGame(roomId, me.uid).let { if (it is RoomResult.Err) report(LobbyProblem.Room(it.error)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                report(LobbyProblem.Network)
            }
        } else {
            _events.send(WaitingRoomEvent.GameStarted(session, gameId))
        }
    }

    private suspend fun reopenAfterFailedLaunch() {
        try {
            service.endGame(roomId, me.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            // 방은 PLAYING으로 남지만 방장이 나가면 닫힌다. 여기서는 더 할 수 있는 일이 없다.
        }
    }

    private suspend fun change(onOk: suspend () -> Unit = {}, block: suspend () -> RoomResult<Room>) {
        if (busy) return
        setBusy(true, launching = _state.value?.launching ?: false)
        try {
            when (val result = block()) {
                is RoomResult.Ok -> onOk()
                is RoomResult.Err -> report(LobbyProblem.Room(result.error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            report(LobbyProblem.Network)
        } finally {
            setBusy(false, launching = _state.value?.launching ?: false)
        }
    }

    private fun setBusy(value: Boolean, launching: Boolean) {
        busy = value
        _state.update { it?.copy(busy = value, launching = launching) }
    }

    private fun report(problem: LobbyProblem) {
        _state.update { it?.copy(problem = problem) }
    }
}
