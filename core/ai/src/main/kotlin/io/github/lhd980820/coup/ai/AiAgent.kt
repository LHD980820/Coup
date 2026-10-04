package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.VisibleEvent

/**
 * AI 상대(설계 §7.1). 입력은 **엔진이 만든 시점별 정보뿐**이다: [PlayerView], [VisibleEvent], [DecisionRequest].
 * 게임의 진짜 상태(`GameState`)를 받는 메서드는 없으며, 받더라도 비공개 필드는 `:engine` 밖에서 읽을 수 없다.
 *
 * 한 에이전트는 한 좌석을 담당하며 상태(신념 등)를 가질 수 있다. 런타임은 [observe]와 [decide]를 동시에 호출하지 않는다.
 */
public interface AiAgent {
    /** 게임 시작 시 1회. */
    public fun onGameStart(view: PlayerView) {}

    /** 상태가 바뀔 때마다 내 시점으로 투영된 이벤트와 새 뷰를 받는다. */
    public fun observe(events: List<VisibleEvent>, view: PlayerView) {}

    /**
     * 지금 내려야 할 결정. 반드시 [request] 안의 합법 선택지 중 하나를 돌려준다.
     * 시간이 오래 걸릴 수 있으므로 런타임은 별도 디스패처에서 호출한다.
     */
    public fun decide(view: PlayerView, request: DecisionRequest): Command
}
