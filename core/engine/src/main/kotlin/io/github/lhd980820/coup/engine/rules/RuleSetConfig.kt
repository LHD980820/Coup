package io.github.lhd980820.coup.engine.rules

import kotlinx.serialization.Serializable

/**
 * 직렬화 가능한 "룰 선택" 값. 함수가 포함된 [RuleSet]은 직렬화하지 않고,
 * 항상 이 값으로부터 [RuleSetRegistry.build]로 재구성한다.
 */
@Serializable
public data class RuleSetConfig(
    public val baseId: String,
    public val baseVersion: Int,
    public val houseRules: Set<String> = emptySet(),
    /** [RuleParams] 필드명 → 값(문자열). 예: "forcedActionThreshold" → "8". */
    public val paramOverrides: Map<String, String> = emptyMap(),
)
