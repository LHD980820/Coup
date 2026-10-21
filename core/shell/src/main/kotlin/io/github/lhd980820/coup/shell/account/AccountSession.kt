package io.github.lhd980820.coup.shell.account

import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.Nicknames
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.Settlement
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

public sealed interface AccountState {
    public data object Loading : AccountState
    public data object Ready : AccountState

    /** 프로필을 준비하지 못했다(네트워크, 규칙 거부 등). [AccountSession.start]를 다시 부르면 재시도한다. */
    public data object Failed : AccountState
}

/**
 * 로그인한 사용자 한 명의 계정 상태(프로필, 직전 게임의 레이팅 반영). 로그인 직후 [start]로 프로필을 만들거나 옛 계정을 이어받는다.
 * 레이팅은 서버 판정이 반영한다(ADR 0015) — 여기서는 그 결과를 지켜본다([watchSettlement]).
 */
public class AccountSession(
    private val users: FirestoreUserRepository,
    private val email: String?,
    private val fallbackNickname: String,
    private val scope: CoroutineScope,
) {
    private var watcher: Job? = null
    private var settlementWatcher: Job? = null
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    public val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)

    /** 내 프로필(레이팅 반영 후 바로 갱신된다). [AccountState.Ready] 전에는 null. */
    public val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    /** 지켜보는 게임의 레이팅 반영 상태(결과 화면의 "레이팅 +n"). 지켜보는 게임이 없으면 null. */
    private val _lastSettlement = MutableStateFlow<Settlement?>(null)
    public val lastSettlement: StateFlow<Settlement?> = _lastSettlement.asStateFlow()

    public suspend fun start() {
        _state.value = AccountState.Loading
        try {
            val profile = users.ensureProfile(Nicknames.defaultFor(email, fallbackNickname))
            _profile.value = profile
            watcher?.cancel()
            watcher = scope.launch { users.observeProfile().collect { if (it != null) _profile.value = it } }
            _state.value = AccountState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportException) {
            _state.value = AccountState.Failed
        }
    }

    /** 닉네임을 바꾼다. 규칙 위반이면 이유를, 성공이면 null을 돌려준다. 네트워크 오류는 예외로 올라간다. */
    public suspend fun updateNickname(nickname: String): NicknameError? = users.updateNickname(nickname)

    /**
     * 끝난 게임 [gameId]의 판정을 [lastSettlement]로 지켜본다. 판정이 나면(반영·무효·비레이팅) 멈춘다.
     * 구독이 끊기면 [retryDelayMillis] 뒤 다시 구독한다. 앱이 꺼져도 반영은 서버가 하므로 잃는 것이 없다.
     */
    public fun watchSettlement(gameId: String, retryDelayMillis: Long = 2_000) {
        settlementWatcher?.cancel()
        _lastSettlement.value = Settlement.Verifying
        settlementWatcher = scope.launch {
            while (true) {
                try {
                    users.observeSettlement(gameId).onEach { _lastSettlement.value = it }.first { it.isFinal }
                    return@launch
                } catch (e: TransportException) {
                    delay(retryDelayMillis)
                }
            }
        }
    }

    public fun clearSettlement() {
        settlementWatcher?.cancel()
        settlementWatcher = null
        _lastSettlement.value = null
    }
}
