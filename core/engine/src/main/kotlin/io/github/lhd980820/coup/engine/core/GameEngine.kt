package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.engine.view.VisibleEvent

public data class GameSetup(
    public val gameId: String,
    public val seats: List<PlayerId>,
    public val ruleSetConfig: RuleSetConfig,
    public val seed: Long,
    /** null이면 RNG로 선 플레이어를 정한다. */
    public val firstPlayer: PlayerId? = null,
)

/**
 * 순수 게임 엔진. 같은 입력에는 항상 같은 출력을 낸다(설계 문서 §4.3).
 */
public interface GameEngine {
    /**
     * 룰셋 빌드·검증 → 덱 생성·셔플 → 분배 → 시작 코인 → 선 플레이어 결정.
     * @throws IllegalArgumentException 설정 오류(인원수 범위 밖, 중복 좌석, 좌석에 없는 선 플레이어, 잘못된 룰셋)
     */
    public fun newGame(setup: GameSetup): GameState

    /** 명령 검증 → 상태 전이 → 입력이 필요할 때까지 자동 해결 → 버전 +1. 거절 시 상태 불변. */
    public fun apply(state: GameState, command: Command): ApplyResult

    /** 지금 입력이 필요한 플레이어들. 비어 있으면 게임 종료. */
    public fun pendingDeciders(state: GameState): Set<PlayerId>

    /** [player]가 지금 내려야 할 결정과 선택지. 결정할 것이 없으면 null. */
    public fun legalOptions(state: GameState, player: PlayerId): DecisionRequest?

    /** [viewer] 시점에서 본 상태. 비공개 정보(상대 미공개 카드, 덱 내용, RNG)는 빠진다. */
    public fun view(state: GameState, viewer: Viewer): PlayerView

    /** 이벤트의 시점별 투영. 당사자 전용 정보(새로 받은 카드, 교환 후보)는 타인에게 마스킹된다. */
    public fun projectEvents(events: List<GameEvent>, viewer: Viewer): List<VisibleEvent>

    /**
     * 시간 초과 시 대신 제출할 기본 명령(설계 §15 D4): 응답 → 허용, 공개/상실 → 첫 미공개 카드,
     * 교환 → 현재 손패 유지, 행동 → 강제면 첫 대상에게 강제 행동, 아니면 무료·무주장·무대상 행동(수입).
     * @throws IllegalArgumentException [player]가 지금 결정권자가 아닐 때
     */
    public fun timeoutCommand(state: GameState, player: PlayerId): Command

    /**
     * 뷰 + 가정한 비공개 정보로 시뮬레이션용 상태를 만든다(Hard AI 전용). 입력에 진짜 비공개 정보가 없으므로 치팅이 아니다.
     * @throws IllegalArgumentException 가정이 공개 정보와 모순될 때
     */
    public fun determinize(view: PlayerView, assignment: HiddenAssignment, seed: Long): GameState
}

public object GameEngines {
    public fun create(registry: RuleSetRegistry = BuiltinRules.registry()): GameEngine = DefaultGameEngine(registry)
}
