package io.github.lhd980820.coup.ai

/** AI 난이도. HARD(결정화 탐색)는 Phase 6에서 추가한다. */
public enum class AiDifficulty { EASY, NORMAL }

/**
 * 성격(설계 §7.1). 0.0~1.0.
 * @property bluffTendency 역할 없이 주장(블러핑)하려는 성향
 * @property challengeAggression 의심스러운 주장에 도전하려는 성향
 * @property riskTolerance 카드를 잃을 위험을 감수하려는 성향
 */
public data class Personality(
    val bluffTendency: Double = 0.4,
    val challengeAggression: Double = 0.5,
    val riskTolerance: Double = 0.5,
) {
    init {
        listOf(bluffTendency, challengeAggression, riskTolerance).forEach { require(it in 0.0..1.0) }
    }

    public companion object {
        public val BALANCED: Personality = Personality()
        public val CAUTIOUS: Personality = Personality(bluffTendency = 0.15, challengeAggression = 0.3, riskTolerance = 0.2)
        public val AGGRESSIVE: Personality = Personality(bluffTendency = 0.7, challengeAggression = 0.75, riskTolerance = 0.8)
    }
}

/** 난이도별 능력 스위치. */
internal data class AiSettings(
    /** 미확인 카드 풀로 확률을 계산한다(아니면 덱 구성만으로 대충 추정). */
    val countCards: Boolean,
    /** 상대의 주장 기록을 신념에 반영한다. */
    val trackClaims: Boolean,
    /** 역할 없이 주장(블러핑)할 수 있다. */
    val allowBluff: Boolean,
    /** 점수에 더하는 무작위 잡음의 크기. */
    val noise: Double,
    /** 블러핑 위험(도전받아 카드를 잃을 기대 손실)에 곱하는 계수. 클수록 블러핑을 덜 한다. */
    val bluffCaution: Double = 1.0,
    /** 상대 한 명이 내 주장에 도전할 기본 확률 추정치. */
    val baseSuspicion: Double = 0.10,
) {
    companion object {
        fun of(difficulty: AiDifficulty): AiSettings = when (difficulty) {
            AiDifficulty.EASY -> AiSettings(countCards = false, trackClaims = false, allowBluff = false, noise = 1.5)
            // 보정값(2026-10 대결 측정): 의심 0.35 / 신중 2.0에서 EASY 상대 승률 2인 ~0.79, 4인 ~0.80,
            // 블러핑 없는 NORMAL 변형 상대 2인 ~0.75. 블러핑 위험을 낮게 잡으면 EASY에게 진다(0.42).
            AiDifficulty.NORMAL -> AiSettings(
                countCards = true,
                trackClaims = true,
                allowBluff = true,
                noise = 0.3,
                bluffCaution = 2.0,
                baseSuspicion = 0.35,
            )
        }
    }
}
