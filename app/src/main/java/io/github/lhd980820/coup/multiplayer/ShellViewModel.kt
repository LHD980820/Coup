package io.github.lhd980820.coup.multiplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.game.GameViewModel
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.remote.ApplyOutcome
import io.github.lhd980820.coup.shell.account.AccountState
import io.github.lhd980820.coup.shell.auth.AuthFormModel
import io.github.lhd980820.coup.shell.lobby.LobbyModel
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 멀티플레이 안의 화면(홈에서 들어오면 로그인 여부에 따라 로그인 또는 로비가 보인다). */
internal enum class MultiScreen { LOBBY, CREATE, ROOM }

/** 방에서 사라진 이유(로비에 한 번 보여준다). */
internal enum class RoomNotice { CLOSED, KICKED }

/** 지금 하고 있는 멀티플레이 게임. */
internal class MultiGame(val roomId: String, val gameId: String)

/**
 * 멀티플레이 화면들의 수명을 가진다(회전에도 유지). 판단은 모두 코어(`:shell`)의 모델에 있고, 여기서는
 * 로그인 상태에 따라 서비스를 만들고 없애며, 화면 사이의 이동과 게임 시작·종료를 이어 붙인다.
 * [game]은 싱글플레이와 같은 게임 화면을 쓰기 위해 공유한다.
 */
internal class ShellViewModel(
    private val backend: Backend,
    private val game: GameViewModel,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    val authForm = AuthFormModel(backend.auth)
    val user = backend.auth.user

    /** 로그인한 동안만 non-null. */
    var services by mutableStateOf<SignedIn?>(null)
        private set
    var accountState by mutableStateOf<AccountState>(AccountState.Loading)
        private set
    var profile by mutableStateOf<UserProfile?>(null)
        private set

    /** 직전 멀티플레이 게임의 레이팅 반영 결과. 반영 전에는 null. */
    var settlement by mutableStateOf<ApplyOutcome?>(null)
        private set

    /** 결과 문서를 끝내 못 받아 반영하지 못했다(다음 접속 때 반영된다). */
    var settlementFailed by mutableStateOf(false)
        private set

    var screen by mutableStateOf(MultiScreen.LOBBY)
        private set
    var lobby by mutableStateOf<LobbyModel?>(null)
        private set
    var room by mutableStateOf<WaitingRoomModel?>(null)
        private set
    var multiGame by mutableStateOf<MultiGame?>(null)
        private set
    var notice by mutableStateOf<RoomNotice?>(null)
        private set

    private var signedInScope: CoroutineScope? = null
    private var settleJob: Job? = null

    init {
        this.scope.launch { backend.auth.user.collect { onUser(it) } }
    }

    private fun onUser(authUser: io.github.lhd980820.coup.shell.auth.AuthUser?) {
        if (authUser == null) {
            teardown()
            return
        }
        if (services?.user?.uid == authUser.uid) return
        teardown()
        val parent = scope.coroutineContext[Job]
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))
        signedInScope = child
        val opened = backend.open(authUser, child)
        services = opened
        accountState = AccountState.Loading
        // 준비 상태와 프로필은 한 번에 받는다 — 따로 받으면 "준비 완료"가 프로필보다 먼저 보여 로비를 만들지 못한다
        child.launch {
            combine(opened.account.state, opened.account.profile) { state, p -> state to p }.collect { (state, p) ->
                accountState = state
                profile = p
            }
        }
        child.launch { opened.account.lastSettlement.collect { settlement = it } }
        child.launch { opened.account.start() }
    }

    private fun teardown() {
        game.quit()
        signedInScope?.cancel()
        signedInScope = null
        services = null
        profile = null
        settlement = null
        settlementFailed = false
        lobby = null
        room = null
        multiGame = null
        notice = null
        screen = MultiScreen.LOBBY
    }

    fun retryAccount() {
        val s = services ?: return
        signedInScope?.launch { s.account.start() }
    }

    /** 닉네임을 바꾼다. 규칙 위반이면 이유를, 성공이면 null을 돌려준다(서버 오류는 예외). */
    suspend fun updateNickname(nickname: String): NicknameError? = services?.account?.updateNickname(nickname)

    fun signOut() {
        scope.launch { backend.auth.signOut() }
    }

    // ---- 화면 이동 ----------------------------------------------------------------------------

    /** 홈에서 멀티플레이로 들어왔다. */
    fun openLobby() {
        val s = services ?: return
        val nickname = profile?.nickname ?: return
        if (lobby == null) lobby = LobbyModel(s.rooms, LobbyUser(s.user.uid, nickname))
        if (room == null) screen = MultiScreen.LOBBY
    }

    fun openCreate() {
        screen = MultiScreen.CREATE
    }

    fun closeCreate() {
        screen = MultiScreen.LOBBY
    }

    fun consumeNotice() {
        notice = null
    }

    fun enterRoom(roomId: String) {
        val s = services ?: return
        val nickname = profile?.nickname ?: return
        room = WaitingRoomModel(roomId, LobbyUser(s.user.uid, nickname), s.rooms, s.registry, s.launcher)
        screen = MultiScreen.ROOM
    }

    /** 대기실 모델이 알린 일을 처리한다(화면이 [WaitingRoomModel.events]를 모아서 넘긴다). */
    fun onRoomEvent(event: WaitingRoomEvent) {
        when (event) {
            WaitingRoomEvent.Left -> leaveRoomScreen(null)
            WaitingRoomEvent.RoomClosed -> leaveRoomScreen(RoomNotice.CLOSED)
            WaitingRoomEvent.Kicked -> leaveRoomScreen(RoomNotice.KICKED)
            is WaitingRoomEvent.GameStarted -> startGame(event)
        }
    }

    private fun leaveRoomScreen(why: RoomNotice?) {
        room = null
        notice = why
        screen = MultiScreen.LOBBY
    }

    private fun startGame(event: WaitingRoomEvent.GameStarted) {
        val current = room ?: return
        val opened = services ?: return
        game.startSession(event.session)
        multiGame = MultiGame(current.roomId, event.gameId)
        opened.account.clearSettlement()
        settlementFailed = false
        // 게임이 끝나면 레이팅을 반영한다(화면이 닫혀도, 회전해도 이어진다)
        val controller = game.controller ?: return
        settleJob?.cancel()
        settleJob = signedInScope?.launch {
            controller.state.first { it?.result != null }
            if (opened.account.settleGame(event.gameId) == null) settlementFailed = true
        }
    }

    /** 결과 화면에서 "대기실로" 또는 나가기: 게임 화면을 닫고 대기실로 돌아간다(방장은 방을 다음 판 준비 상태로). */
    fun finishGame() {
        settleJob?.let { if (settlement == null) it.cancel() } // 아직 결과가 안 나온 채 나가면(기권 등) 반영은 다음 접속 때
        game.quit()
        multiGame = null
        screen = MultiScreen.ROOM
        val model = room ?: return
        signedInScope?.launch { model.gameFinished() }
    }

    override fun onCleared() {
        teardown()
    }
}
