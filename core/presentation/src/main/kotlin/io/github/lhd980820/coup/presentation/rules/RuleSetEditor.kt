package io.github.lhd980820.coup.presentation.rules

import io.github.lhd980820.coup.engine.rules.HouseRule
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry

/** 룰 설정 화면이 보여주는 조절 항목 하나. 기본값은 기반 룰셋에서 읽는다(여기에 중복해서 적지 않는다). */
public sealed interface ParamSpec {
    public val key: String

    public data class IntParam(override val key: String, val default: Int, val min: Int, val max: Int) : ParamSpec
    public data class BoolParam(override val key: String, val default: Boolean) : ParamSpec
}

/** 사용자가 고른 룰: 켠 하우스룰과 기본값에서 바꾼 파라미터(문자열 값, 코어의 `paramOverrides` 형식). */
public data class RuleSelection(val houseRules: Set<String> = emptySet(), val params: Map<String, String> = emptyMap()) {
    /** 아무것도 바꾸지 않은 표준 규칙인가. */
    val isStandard: Boolean get() = houseRules.isEmpty() && params.isEmpty()

    /** 바꾼 항목 수(켠 하우스룰 + 바꾼 파라미터). */
    val changeCount: Int get() = houseRules.size + params.size
}

/**
 * 룰 설정 화면의 순수 로직(설계 §10.2: 룰셋/하우스룰 선택). 화면은 이 객체가 돌려주는 목록을 그리고 [RuleSelection]만 들고 있으면 된다.
 * - 기본값으로 되돌린 파라미터는 **override에서 뺀다** — 그래야 "표준 규칙"이 `RuleSetConfig`로도 표준이고(레이팅 대상, D5) 방 설정 비교가 정확하다.
 * - 값은 범위 안으로 맞추고, 조합은 [RuleSetRegistry.build]로 검증한다(사용자가 만들 수 있는 모든 조합이 실제로 게임이 되는지는 테스트가 확인).
 */
public class RuleSetEditor(private val registry: RuleSetRegistry, private val base: RuleSetConfig) {
    init {
        require(base.houseRules.isEmpty() && base.paramOverrides.isEmpty()) { "base must be the plain rule set" }
    }

    private val baseParams = registry.build(base).params

    /** 이 기반 룰셋과 호환되는 하우스룰(등록 순서). 제목·설명은 키이고 번역은 앱 몫이다. */
    public val houseRules: List<HouseRule> = registry.availableHouseRules(base.baseId)

    /** 조절할 수 있는 파라미터. 범위는 게임이 정상적으로 진행되는 안쪽으로 제한한다. */
    public val params: List<ParamSpec> = listOf(
        ParamSpec.IntParam("startingCoins", baseParams.startingCoins, min = 1, max = 7),
        ParamSpec.IntParam("forcedActionThreshold", baseParams.forcedActionThreshold, min = 7, max = 12),
        ParamSpec.BoolParam("refundCostWhenActionChallengeLost", baseParams.refundCostWhenActionChallengeLost),
        ParamSpec.BoolParam("exchangeReturnShuffles", baseParams.exchangeReturnShuffles),
    )

    public val standard: RuleSelection = RuleSelection()

    /** 저장된 설정에서 선택을 복원한다. 모르는 하우스룰·파라미터는 [IllegalArgumentException]. */
    public fun fromConfig(config: RuleSetConfig): RuleSelection {
        require(config.baseId == base.baseId && config.baseVersion == base.baseVersion) { "different base rule set" }
        val unknownRules = config.houseRules - houseRules.map { it.id }.toSet()
        require(unknownRules.isEmpty()) { "unknown house rules: $unknownRules" }
        val unknownParams = config.paramOverrides.keys - params.map { it.key }.toSet()
        require(unknownParams.isEmpty()) { "unknown params: $unknownParams" }
        return RuleSelection(config.houseRules, config.paramOverrides)
    }

    public fun config(selection: RuleSelection): RuleSetConfig =
        base.copy(houseRules = selection.houseRules, paramOverrides = selection.params)

    public fun toggleHouseRule(selection: RuleSelection, id: String, on: Boolean = id !in selection.houseRules): RuleSelection {
        require(houseRules.any { it.id == id }) { "unknown house rule: $id" }
        return selection.copy(houseRules = if (on) selection.houseRules + id else selection.houseRules - id)
    }

    public fun intValue(selection: RuleSelection, spec: ParamSpec.IntParam): Int = selection.params[spec.key]?.toIntOrNull() ?: spec.default

    public fun boolValue(selection: RuleSelection, spec: ParamSpec.BoolParam): Boolean = selection.params[spec.key]?.toBooleanStrictOrNull() ?: spec.default

    /** 범위 밖 값은 가장 가까운 허용 값으로 맞춘다. 기본값이면 override를 뺀다. */
    public fun setInt(selection: RuleSelection, spec: ParamSpec.IntParam, value: Int): RuleSelection {
        val v = value.coerceIn(spec.min, spec.max)
        return selection.copy(params = if (v == spec.default) selection.params - spec.key else selection.params + (spec.key to v.toString()))
    }

    public fun setBool(selection: RuleSelection, spec: ParamSpec.BoolParam, value: Boolean): RuleSelection =
        selection.copy(params = if (value == spec.default) selection.params - spec.key else selection.params + (spec.key to value.toString()))

    /** 선택한 룰이 실제 룰셋으로 만들어지지 않는 이유(없으면 빈 목록). 화면은 시작 버튼을 막고 이유를 보여준다. */
    public fun issues(selection: RuleSelection): List<String> =
        runCatching { registry.build(config(selection)) }.exceptionOrNull()?.let { listOf(it.message ?: it::class.simpleName.orEmpty()) }.orEmpty()

    /** 이 룰로 가능한 인원 범위(방 만들기 화면의 최대 인원 선택에 쓴다). */
    public fun playerRange(selection: RuleSelection): IntRange =
        registry.build(config(selection)).params.let { it.minPlayers..it.maxPlayers }

    /** 레이팅 대상인가(D5): 하우스룰도 파라미터 변경도 없을 때. 방의 `isRated`와 같은 기준이다. */
    public fun isRated(selection: RuleSelection): Boolean = selection.isStandard
}
