package io.github.lhd980820.coup.runtime.transport

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.session.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 한 좌석에게 보내는 한 번의 갱신. 이 좌석이 볼 수 있는 정보만 담는다(엔진이 투영한 뷰와 이벤트).
 * [events]는 이번 갱신에서 생긴 이벤트다. 놓쳐도 [view]가 항상 진실이다.
 */
@Serializable
public data class ViewEnvelope(
    public val version: Long,
    public val view: PlayerView,
    public val events: List<VisibleEvent>,
    /** epoch millis. 호스트 시계 기준이며 게스트는 표시용으로만 쓴다. */
    public val deadlines: Map<PlayerId, Long>,
)

/** 호스트가 한 번의 상태 변경마다 내보내는 묶음. */
public class Publication(
    public val version: Long,
    /** 원격 사람 좌석별 뷰. 봇과 호스트 자신은 포함하지 않는다. */
    public val views: Map<PlayerId, ViewEnvelope>,
    public val spectator: ViewEnvelope,
    /** 호스트 재시작 복구용 권한자 상태([io.github.lhd980820.coup.engine.serialization.EngineJson] 문자열). 호스트 외에는 읽을 수 없어야 한다. */
    public val authorityBackup: String,
)

/** [senderUid]는 전송 계층이 인증한 발신자다. 명령 안의 `actor`는 신뢰하지 않는다. */
public class IncomingCommand(public val commandId: String, public val senderUid: String, public val command: Command)

@Serializable
public sealed interface AckResult {
    @Serializable
    public data class Accepted(public val version: Long) : AckResult

    @Serializable
    public data class Rejected(public val reason: Rejection) : AckResult
}

public class GameResultRecord(
    public val ranking: List<PlayerId>,
    public val ruleSetConfig: RuleSetConfig,
    /** 레이팅 반영 대상인가(D5: 봇·하우스룰·파라미터 변경이 있으면 false). */
    public val rated: Boolean,
    /** [rated]일 때만 채워진다. */
    public val ratingDeltas: Map<PlayerId, Int>,
)

/** 전송 실패(네트워크 오류 등). 세션은 이를 [io.github.lhd980820.coup.runtime.session.SubmitResult.NetworkError]로 바꾼다. */
public class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 호스트와 게스트 사이의 통로 추상화(설계 §6.4). 메모리 구현은 테스트용, Firestore 구현은 `:data`에 둔다. */
public interface GameTransport {
    public interface Host {
        public suspend fun publish(gameId: String, publication: Publication)
        public fun incomingCommands(gameId: String): Flow<IncomingCommand>
        public suspend fun acknowledge(gameId: String, commandId: String, ack: AckResult)
        public suspend fun finish(gameId: String, result: GameResultRecord)
    }

    public interface Guest {
        /** 내 좌석의 뷰 갱신. 구독하면 가장 최근 갱신부터 받는다. 다른 좌석의 뷰는 구독할 수 없다. */
        public fun observeMyView(gameId: String, me: PlayerId): Flow<ViewEnvelope>
        public fun connection(gameId: String): Flow<ConnectionState>

        /** 명령을 보내고 호스트의 처리 결과를 기다린다. @throws TransportException 전송 실패 */
        public suspend fun sendCommand(gameId: String, command: Command): AckResult
    }
}
