package io.github.lhd980820.coup.runtime.seat

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent
import io.github.lhd980820.coup.runtime.SeatKind

/** 명령 제출 함수. 권한자가 좌석에 넘겨준다. */
public typealias CommandSubmitter = suspend (Command) -> ApplyResult

/**
 * 한 좌석을 누가 조종하는가(설계 §6.2). 좌석은 **자기 시점의 뷰와 이벤트만** 받는다.
 * 권한자의 전달 코루틴에서 순서대로 호출되므로, 오래 걸리는 일(AI 생각)은 반드시 별도 코루틴으로 넘겨야 한다.
 */
public interface SeatController {
    public val playerId: PlayerId
    public val kind: SeatKind

    /** 권한자 시작 시 1회. */
    public suspend fun onStart(view: PlayerView) {}

    /** 상태가 바뀔 때마다. */
    public suspend fun onEvents(view: PlayerView, events: List<VisibleEvent>) {}

    /** 새 결정이 필요할 때(같은 결정에 대해서는 한 번만). */
    public suspend fun onDecisionRequired(view: PlayerView, request: DecisionRequest, submit: CommandSubmitter) {}
}

/** 로컬 사람 좌석: 아무것도 자동으로 하지 않는다. UI가 세션을 통해 명령을 넣는다. */
public class LocalHumanSeat(override val playerId: PlayerId) : SeatController {
    override val kind: SeatKind = SeatKind.LOCAL_HUMAN
}

/** 원격 사람 좌석(호스트 측 표현): 명령은 전송 계층에서 들어오므로 여기서는 아무것도 하지 않는다. */
public class RemoteSeat(override val playerId: PlayerId) : SeatController {
    override val kind: SeatKind = SeatKind.REMOTE_HUMAN
}
