package io.github.lhd980820.coup.ai

import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameEngines

/** 난이도·성격으로 AI를 만든다(설계 §7.1). 같은 인자는 같은 결정 시퀀스를 만든다(HARD는 시간 상한에 닿지 않는 한). */
public object AiFactory {
    /**
     * @param hard HARD의 탐색 설정(다른 난이도는 무시).
     * @param engine HARD가 시뮬레이션에 쓰는 엔진. 기본은 내장 룰셋 엔진.
     */
    public fun create(
        difficulty: AiDifficulty,
        personality: Personality = Personality.BALANCED,
        seed: Long = 0,
        hard: HardConfig = HardConfig(),
        engine: GameEngine = GameEngines.create(),
    ): AiAgent = when (difficulty) {
        AiDifficulty.HARD -> HardAgent(engine, hard, personality, seed)
        else -> HeuristicAgent(AiSettings.of(difficulty), personality, seed)
    }
}
