package io.github.lhd980820.coup.runtime

import io.github.lhd980820.coup.engine.view.DecisionRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class SeatKind { LOCAL_HUMAN, AI, REMOTE_HUMAN }

/** 결정 종류와 좌석 종류에 따른 제한 시간. null이면 무제한. */
public fun interface TimeoutPolicy {
    public fun durationFor(request: DecisionRequest, seat: SeatKind): Duration?

    public companion object {
        /**
         * 확정 사항 D4: 응답 15초, 카드 선택(공개/상실/교환) 20초, 행동 선택 30초.
         * [unlimitedLocalHuman]이면 로컬 사람 좌석은 시간 제한이 없다(싱글플레이 옵션).
         */
        public fun standard(unlimitedLocalHuman: Boolean = false): TimeoutPolicy = TimeoutPolicy { request, seat ->
            if (unlimitedLocalHuman && seat == SeatKind.LOCAL_HUMAN) {
                null
            } else {
                when (request) {
                    is DecisionRequest.Respond -> 15.seconds
                    is DecisionRequest.ChooseRevealCard,
                    is DecisionRequest.ChooseInfluenceToLose,
                    is DecisionRequest.ChooseExchange,
                    -> 20.seconds
                    is DecisionRequest.ChooseAction -> 30.seconds
                }
            }
        }

        public val None: TimeoutPolicy = TimeoutPolicy { _, _ -> null }
    }
}
