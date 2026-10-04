package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules

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
 * 나머지 메서드(apply, legalOptions, view 등)는 Phase 1 4단계 이후 추가된다.
 */
public interface GameEngine {
    /**
     * 룰셋 빌드·검증 → 덱 생성·셔플 → 분배 → 시작 코인 → 선 플레이어 결정.
     * @throws IllegalArgumentException 설정 오류(인원수 범위 밖, 중복 좌석, 좌석에 없는 선 플레이어, 잘못된 룰셋)
     */
    public fun newGame(setup: GameSetup): GameState
}

public object GameEngines {
    public fun create(registry: RuleSetRegistry = BuiltinRules.registry()): GameEngine = DefaultGameEngine(registry)
}
