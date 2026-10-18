package io.github.lhd980820.coup.shell.lobby

import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.lobby.JoinCodes
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.RoomSummary
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** 로비에서 사용자에게 알릴 실패. 번역은 앱이 맡는다. */
public sealed interface LobbyProblem {
    /** 방 규칙 때문에 안 된다(가득 참, 틀린 코드, 업데이트 필요 등). */
    public data class Room(val error: RoomError) : LobbyProblem

    /** 서버에 닿지 못했거나 거절당했다. 다시 시도하면 된다. */
    public data object Network : LobbyProblem
}

public data class LobbyUiState(
    /** 공개 대기 방, 최신순. 처음 불러오기 전에는 null. */
    val rooms: List<RoomSummary>? = null,
    val refreshing: Boolean = false,
    /** 방 만들기·입장 중(버튼 잠금). */
    val busy: Boolean = false,
    val problem: LobbyProblem? = null,
)

/** 로비 화면이 다른 화면으로 가라고 알리는 일. */
public sealed interface LobbyEvent {
    public data class EnterRoom(val roomId: String) : LobbyEvent
}

/**
 * 방 목록 화면의 상태 홀더. 목록은 한 번 읽는 쿼리라 [run]이 주기적으로 다시 읽는다(화면이 보이는 동안만 부르면 된다).
 * 입장·생성에 성공하면 [events]로 [LobbyEvent.EnterRoom]을 내보낸다.
 */
public class LobbyModel(
    private val service: RoomService,
    private val me: LobbyUser,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_MILLIS,
) {
    private val _state = MutableStateFlow(LobbyUiState())
    public val state: StateFlow<LobbyUiState> = _state.asStateFlow()

    private val _events = Channel<LobbyEvent>(Channel.BUFFERED)
    public val events: Flow<LobbyEvent> = _events.receiveAsFlow()

    /** 화면이 보이는 동안 목록을 주기적으로 새로 읽는다. 취소될 때까지 돈다. */
    public suspend fun run() {
        while (true) {
            refresh()
            delay(refreshIntervalMillis)
        }
    }

    public suspend fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        try {
            val rooms = service.listOpen()
            _state.update { it.copy(rooms = rooms, refreshing = false, problem = if (it.problem == LobbyProblem.Network) null else it.problem) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            _state.update { it.copy(refreshing = false, problem = LobbyProblem.Network) }
        }
    }

    public fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    public suspend fun createRoom(title: String, visibility: Visibility, maxPlayers: Int, config: RuleSetConfig) {
        act { service.createRoom(me, title, visibility, maxPlayers, config) }
    }

    public suspend fun join(roomId: String) {
        act { service.join(roomId, me) }
    }

    /** 참가 코드로 입장한다. 코드는 대소문자·공백을 무시한다. */
    public suspend fun joinByCode(code: String) {
        act { service.joinByCode(me, code) }
    }

    private suspend fun act(block: suspend () -> RoomResult<io.github.lhd980820.coup.lobby.Room>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            _state.update { it.copy(busy = false, problem = LobbyProblem.Network) }
            return
        }
        when (result) {
            is RoomResult.Ok -> {
                _state.update { it.copy(busy = false) }
                _events.send(LobbyEvent.EnterRoom(result.value.id))
            }
            is RoomResult.Err -> _state.update { it.copy(busy = false, problem = LobbyProblem.Room(result.error)) }
        }
    }

    /** 참가 코드 입력칸이 보내도 되는 모양인가. */
    public fun isCodeComplete(input: String): Boolean = JoinCodes.isWellFormed(input)

    private companion object {
        const val DEFAULT_REFRESH_MILLIS = 8_000L
    }
}
