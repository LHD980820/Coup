package io.github.lhd980820.coup.engine.rules

/** 기반 룰셋과 하우스룰을 등록하고 [RuleSetConfig]로부터 검증된 [RuleSet]을 만든다. */
public class RuleSetRegistry {
    private val bases = mutableMapOf<String, Pair<Int, () -> RuleSet>>()
    private val houseRules = linkedMapOf<String, HouseRule>()

    public fun registerBase(id: String, version: Int, factory: () -> RuleSet) {
        require(id !in bases) { "base rule set already registered: $id" }
        bases[id] = version to factory
    }

    public fun registerHouseRule(rule: HouseRule) {
        require(rule.id !in houseRules) { "house rule already registered: ${rule.id}" }
        houseRules[rule.id] = rule
    }

    /** UI의 룰 설정 화면용: [baseId]와 호환되는 하우스룰 목록(등록 순서). */
    public fun availableHouseRules(baseId: String): List<HouseRule> =
        houseRules.values.filter { it.compatibleBases.isEmpty() || baseId in it.compatibleBases }

    /**
     * 기반 룰셋 → 하우스룰(ID 정렬 순서로 적용, 참가자 간 결정성) → 파라미터 오버라이드(명시 값이 우선) → 검증.
     * @throws IllegalArgumentException 알 수 없는 기반/버전/하우스룰/파라미터, 호환되지 않는 하우스룰, 검증 실패
     */
    public fun build(config: RuleSetConfig): RuleSet {
        val (version, factory) = requireNotNull(bases[config.baseId]) { "unknown base rule set: ${config.baseId}" }
        require(version == config.baseVersion) {
            "rule set ${config.baseId} version mismatch: requested ${config.baseVersion}, available $version"
        }

        val builder = RuleSetBuilder(factory())
        for (ruleId in config.houseRules.sorted()) {
            val rule = requireNotNull(houseRules[ruleId]) { "unknown house rule: $ruleId" }
            require(rule.compatibleBases.isEmpty() || config.baseId in rule.compatibleBases) {
                "house rule $ruleId is not compatible with ${config.baseId}"
            }
            rule.apply(builder)
        }
        builder.updateParams { applyOverrides(it, config.paramOverrides) }

        val ruleSet = builder.build()
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
