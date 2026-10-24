package io.github.lhd980820.coup.runtime.session

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.SeatKind
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public data class SeatInfo(val displayName: String, val kind: SeatKind, val online: Boolean = true)

/** UI가 렌더링하는 한 시점의 스냅샷. 마감 시각은 epoch millis. */
public data class SessionSnapshot(
    val view: PlayerView,
    val myDeadline: Long?,
    val othersDeadlines: Map<PlayerId, Long>,
    val seatInfo: Map<PlayerId, SeatInfo>,
)

public sealed interface SubmitResult {
    public data object Ok : SubmitResult
    public data class Rejected(val reason: Rejection) : SubmitResult
    public data object NetworkError : SubmitResult
}

public enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
    HOST_LOST,
    CLOSED,

    /** 방장이 오래 돌아오지 않아 서버가 판을 끝냈다(ADR 0016). 더 둘 수 없고, 다시 연결되지도 않는다. */
    ABANDONED,

    /** (방장 세션) 연결이 끊긴 사이 다른 참가자가 방장을 이어받았다(ADR 0017). 이 세션은 멈췄고, 게스트로 다시 들어가야 한다. */
    HOST_MOVED,
}

/**
 * UI(ViewModel)가 보는 유일한 게임 인터페이스(설계 §6.3). 싱글플레이·멀티 호스트·멀티 게스트를 구분하지 않는다.
 * 노출되는 것은 내 시점의 정보뿐이다.
 */
public interface GameSession {
    public val gameId: String

    /** 관전자면 null. */
    public val me: PlayerId?
    public val snapshot: StateFlow<SessionSnapshot?>

    /**
     * 애니메이션·로그용 이벤트(내 시점으로 투영됨). 구독이 늦어도 최근 [EVENT_REPLAY]개는 다시 받는다 —
     * 화면(ViewModel)이 구독하기 전에 AI가 먼저 행동해도 로그가 빠지지 않게 하기 위해서다.
     */
    public val events: SharedFlow<VisibleEvent>
    public val connection: StateFlow<ConnectionState>

    public suspend fun submit(command: Command): SubmitResult
    public suspend fun concede(): SubmitResult
    public fun close()
}

/** [GameSession.events]가 늦게 구독한 쪽에 다시 보내주는 최근 이벤트 수. */
public const val EVENT_REPLAY: Int = 64
