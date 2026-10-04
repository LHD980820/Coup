package io.github.lhd980820.coup.engine.rules.builtin

import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry

/** 앱에 내장된 룰셋 등록. 새 기반 룰셋/하우스룰은 여기에 한 줄씩 추가한다. */
public object BuiltinRules {
    public fun registry(): RuleSetRegistry = RuleSetRegistry().apply {
        registerBase(ClassicRuleSet.ID, ClassicRuleSet.VERSION, ClassicRuleSet::create)
    }

    public fun classicConfig(): RuleSetConfig = RuleSetConfig(ClassicRuleSet.ID, ClassicRuleSet.VERSION)
}
