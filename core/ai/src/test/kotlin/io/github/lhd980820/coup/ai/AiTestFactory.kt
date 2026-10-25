package io.github.lhd980820.coup.ai

/**
 * 테스트용 AI 생성기. HARD는 단위 테스트가 느려지지 않도록 가벼운 탐색 설정을 쓰고, 시간 상한을 없애 결과가 항상 재현되게 한다.
 * (실제 설정의 속도·강도는 [HardAgentTest]와 느린 대결 측정이 따로 확인한다.)
 */
object AiTestFactory {
    val lightHard = HardConfig(samples = 3, rolloutDecisions = 25, timeBudget = null, minSamples = 1)

    fun create(difficulty: AiDifficulty, personality: Personality = Personality.BALANCED, seed: Long = 0): AiAgent =
        AiFactory.create(difficulty, personality, seed, hard = lightHard)
}
