package io.github.lhd980820.coup.shell.account

import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.Nicknames
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.remote.ApplyOutcome
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface AccountState {
    public data object Loading : AccountState
    public data object Ready : AccountState

    /** 프로필을 준비하지 못했다(네트워크, 규칙 거부 등). [AccountSession.start]를 다시 부르면 재시도한다. */
    public data object Failed : AccountState
}

/**
 * 로그인한 사용자 한 명의 계정 상태(프로필, 정산). 로그인 직후 [start]로 프로필을 만들거나 옛 계정을 이어받고,
 * 정산 대기 중인 rated 게임이 있으면 반영한다(ADR 0007) — 그래야 다음 rated 게임의 입장이 막히지 않는다.
 */
public class AccountSession(
    private val users: FirestoreUserRepository,
    private val email: String?,
    private val fallbackNickname: String,
    private val scope: CoroutineScope,
) {
    private var watcher: Job? = null
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    public val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)

    /** 내 프로필(레이팅 반영 후 바로 갱신된다). [AccountState.Ready] 전에는 null. */
    public val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    /** 마지막으로 반영한 결과(결과 화면의 "레이팅 +n"). 새 게임을 시작하면 지워진다. */
    private val _lastSettlement = MutableStateFlow<ApplyOutcome?>(null)
    public val lastSettlement: StateFlow<ApplyOutcome?> = _lastSettlement.asStateFlow()

    public suspend fun start() {
        _state.value = AccountState.Loading
        try {
            val profile = users.ensureProfile(Nicknames.defaultFor(email, fallbackNickname))
            _profile.value = profile
            watcher?.cancel()
            watcher = scope.launch { users.observeProfile().collect { if (it != null) _profile.value = it } }
            // 정산 대기가 남아 있으면 지금 반영한다(앱이 게임 중에 꺼진 경우)
            users.settlePending()?.let { _lastSettlement.value = it }
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
     * 끝난 rated 게임을 내 레이팅에 반영한다. 결과 문서는 방장 기기가 마지막 상태를 게시한 **뒤에** 쓰이므로 아직 없을 수 있다 —
     * 있을 때까지 [attempts]번 [retryDelayMillis] 간격으로 기다린다. 비 rated 게임이면 [ApplyOutcome.Skipped]를 돌려준다.
     * 끝내 못 하면 null(정산 대기 표시가 남아 있으므로 다음 [start]에서 반영된다).
     */
    public suspend fun settleGame(gameId: String, attempts: Int = 20, retryDelayMillis: Long = 1_000): ApplyOutcome? {
        repeat(attempts) {
            val outcome = try {
                users.applyResult(gameId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                null
            }
            if (outcome != null && outcome != ApplyOutcome.NoResultYet) {
                _lastSettlement.value = outcome
                return outcome
            }
            delay(retryDelayMillis)
        }
        return null
    }

    public fun clearSettlement() {
        _lastSettlement.value = null
    }
}
