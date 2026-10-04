package io.github.lhd980820.coup.engine.rules

/** 기반 룰셋을 등록하고 [RuleSetConfig]로부터 검증된 [RuleSet]을 만든다. 하우스룰은 Phase 1 11단계에서 추가. */
public class RuleSetRegistry {
    private val bases = mutableMapOf<String, Pair<Int, () -> RuleSet>>()

    public fun registerBase(id: String, version: Int, factory: () -> RuleSet) {
        require(id !in bases) { "base rule set already registered: $id" }
        bases[id] = version to factory
    }

    /** @throws IllegalArgumentException 알 수 없는 기반/버전/하우스룰/파라미터이거나 결과 룰셋이 검증에 실패한 경우 */
    public fun build(config: RuleSetConfig): RuleSet {
        val (version, factory) = requireNotNull(bases[config.baseId]) { "unknown base rule set: ${config.baseId}" }
        require(version == config.baseVersion) {
            "rule set ${config.baseId} version mismatch: requested ${config.baseVersion}, available $version"
        }
        require(config.houseRules.isEmpty()) { "unknown house rules: ${config.houseRules}" }

        val base = factory()
        val ruleSet = RuleSet(base.id, base.version, base.roles, base.actions, applyOverrides(base.params, config.paramOverrides))
        val issues = RuleSetValidator.validate(ruleSet)
        require(issues.isEmpty()) { "invalid rule set ${config.baseId}: ${issues.joinToString { it.message }}" }
        return ruleSet
    }

    private fun applyOverrides(params: RuleParams, overrides: Map<String, String>): RuleParams =
        overrides.entries.fold(params) { p, (key, raw) ->
            fun int() = requireNotNull(raw.toIntOrNull()) { "param $key expects an integer: $raw" }
            fun bool() = requireNotNull(raw.toBooleanStrictOrNull()) { "param $key expects a boolean: $raw" }
            when (key) {
                "startingCoins" -> p.copy(startingCoins = int())
                "handSize" -> p.copy(handSize = int())
                "minPlayers" -> p.copy(minPlayers = int())
                "maxPlayers" -> p.copy(maxPlayers = int())
                "forcedActionThreshold" -> p.copy(forcedActionThreshold = int())
                "refundCostWhenActionChallengeLost" -> p.copy(refundCostWhenActionChallengeLost = bool())
                "exchangeReturnShuffles" -> p.copy(exchangeReturnShuffles = bool())
                else -> throw IllegalArgumentException("unknown rule param: $key")
            }
        }
}
