package io.github.lhd980820.coup.shell.stats

import io.github.lhd980820.coup.account.AppliedResult
import io.github.lhd980820.coup.account.RankingEntry
import io.github.lhd980820.coup.account.Rankings
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** 내 자리: 상위 목록 안이면 그 줄, 밖이면 내 프로필만(순위는 모른다). */
public sealed interface MyStanding {
    /** 아직 rated 게임을 하지 않아 순위가 없다. */
    public data class Unranked(val profile: UserProfile) : MyStanding

    /** 상위 목록 안. */
    public data class InTop(val entry: RankingEntry) : MyStanding

    /** 상위 목록 밖(순위는 목록 길이만큼만 안다). */
    public data class BelowTop(val profile: UserProfile) : MyStanding
}

public data class RankingUiState(
    /** 상위 순위. 처음 불러오기 전에는 null. */
    val entries: List<RankingEntry>? = null,
    val me: MyStanding? = null,
    /** 목록을 읽지 못했다(다시 시도 중). 이미 받은 목록은 그대로 보여 준다. */
    val offline: Boolean = false,
)

/**
 * 랭킹 화면의 상태 홀더. 상위 [limit]명과 내 프로필을 구독해 합친다. 구독이 오류로 끊기면 [offline]을 켜고 [retryMillis] 뒤에 다시 구독한다.
 * 참고: 서버가 레이팅 순으로 [limit]명만 주고 rated 게임을 안 한 사람은 그 뒤에 걸러내므로, 아직 안 한 사용자가 많으면 목록이 [limit]보다 짧을 수 있다.
 */
public class RankingModel(
    private val users: FirestoreUserRepository,
    private val myUid: String,
    private val limit: Int = DEFAULT_LIMIT,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private val _state = MutableStateFlow(RankingUiState())
    public val state: StateFlow<RankingUiState> = _state.asStateFlow()

    /** 화면이 보이는 동안 구독한다. 취소될 때까지 돈다. */
    public suspend fun run() {
        while (true) {
            try {
                combine(users.observeTop(limit), users.observeProfile()) { top, mine -> top to mine }.collect { (top, mine) ->
                    _state.value = RankingUiState(top, standing(top, mine), offline = false)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                _state.value = _state.value.copy(offline = true)
                delay(retryMillis)
            }
        }
    }

    private fun standing(top: List<RankingEntry>, mine: UserProfile?): MyStanding? {
        top.firstOrNull { it.uid == myUid }?.let { return MyStanding.InTop(it) }
        mine ?: return null
        return if (mine.plays == 0) MyStanding.Unranked(mine) else MyStanding.BelowTop(mine)
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 50
        private const val DEFAULT_RETRY_MILLIS = 3_000L
    }
}

public data class HistoryUiState(
    /** 최근 것부터. 처음 불러오기 전에는 null. */
    val results: List<AppliedResult>? = null,
    val profile: UserProfile? = null,
    val offline: Boolean = false,
) {
    /** 승률(0.0~1.0). 판수가 0이면 null. */
    val winRate: Double? get() = profile?.let(Rankings::winRate)

    /** 보이는 전적의 레이팅 변화 합. */
    val netDelta: Int get() = results.orEmpty().sumOf { it.delta }
}

/** 전적 화면의 상태 홀더. 내 rated 게임 기록 최근 [limit]개와 프로필(판수·승수)을 구독한다. */
public class HistoryModel(
    private val users: FirestoreUserRepository,
    private val limit: Int = DEFAULT_LIMIT,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private val _state = MutableStateFlow(HistoryUiState())
    public val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    public suspend fun run() {
        while (true) {
            try {
                combine(users.observeHistory(limit), users.observeProfile()) { results, profile -> results to profile }.collect { (results, profile) ->
                    _state.value = HistoryUiState(results, profile, offline = false)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                _state.value = _state.value.copy(offline = true)
                delay(retryMillis)
            }
        }
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 50
        private const val DEFAULT_RETRY_MILLIS = 3_000L
    }
}
