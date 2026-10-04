package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest

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
 * view / projectEvents / timeoutCommand / determinize는 Phase 1 10단계에서 추가된다.
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
}

public object GameEngines {
    public fun create(registry: RuleSetRegistry = BuiltinRules.registry()): GameEngine = DefaultGameEngine(registry)
}
