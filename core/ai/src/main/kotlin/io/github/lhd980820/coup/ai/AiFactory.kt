package io.github.lhd980820.coup.ai

/** 난이도·성격으로 AI를 만든다(설계 §7.1). 같은 인자는 같은 결정 시퀀스를 만든다. */
public object AiFactory {
    public fun create(difficulty: AiDifficulty, personality: Personality = Personality.BALANCED, seed: Long = 0): AiAgent =
        HeuristicAgent(AiSettings.of(difficulty), personality, seed)
}
