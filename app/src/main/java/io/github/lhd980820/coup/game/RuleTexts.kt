package io.github.lhd980820.coup.game

import androidx.annotation.StringRes
import io.github.lhd980820.coup.R

/**
 * 코어가 주는 하우스룰의 제목/설명 키와 파라미터 키 -> 문자열 리소스. 코어에 새 하우스룰이 등록됐는데 여기 없으면 화면은 키를 그대로 보여주고,
 * `RuleTextsTest`가 등록된 모든 하우스룰과 조절 항목의 번역이 있는지 잡는다.
 */
internal object RuleTexts {
    private val houseRuleKeys: Map<String, Int> = mapOf(
        "house_rule_no_steal_from_broke_title" to R.string.house_rule_no_steal_from_broke_title,
        "house_rule_no_steal_from_broke_desc" to R.string.house_rule_no_steal_from_broke_desc,
        "house_rule_last_stand_title" to R.string.house_rule_last_stand_title,
        "house_rule_last_stand_desc" to R.string.house_rule_last_stand_desc,
    )

    private val paramTitles: Map<String, Int> = mapOf(
        "startingCoins" to R.string.rule_param_startingCoins_title,
        "forcedActionThreshold" to R.string.rule_param_forcedActionThreshold_title,
        "refundCostWhenActionChallengeLost" to R.string.rule_param_refundCostWhenActionChallengeLost_title,
        "exchangeReturnShuffles" to R.string.rule_param_exchangeReturnShuffles_title,
    )

    private val paramDescriptions: Map<String, Int> = mapOf(
        "startingCoins" to R.string.rule_param_startingCoins_desc,
        "forcedActionThreshold" to R.string.rule_param_forcedActionThreshold_desc,
        "refundCostWhenActionChallengeLost" to R.string.rule_param_refundCostWhenActionChallengeLost_desc,
        "exchangeReturnShuffles" to R.string.rule_param_exchangeReturnShuffles_desc,
    )

    @StringRes fun houseRule(key: String): Int? = houseRuleKeys[key]
    @StringRes fun paramTitle(key: String): Int? = paramTitles[key]
    @StringRes fun paramDescription(key: String): Int? = paramDescriptions[key]
}
