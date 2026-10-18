package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.presentation.rules.ParamSpec
import io.github.lhd980820.coup.presentation.rules.RuleSelection
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor

internal object RuleTags {
    const val TOGGLE = "rules_toggle"
    const val SUMMARY = "rules_summary"
    const val RESET = "rules_reset"
    const val INVALID = "rules_invalid"
    fun house(id: String) = "rules_house_$id"
    fun value(key: String) = "rules_param_${key}_value"
    fun plus(key: String) = "rules_param_${key}_plus"
    fun minus(key: String) = "rules_param_${key}_minus"
    fun switch(key: String) = "rules_param_${key}_switch"
}

/** 회전·프로세스 복원에도 선택을 유지한다. */
internal val RuleSelectionSaver: Saver<RuleSelection, List<String>> = Saver(
    save = { s -> s.houseRules.sorted().map { "h:$it" } + s.params.entries.sortedBy { it.key }.map { "p:${it.key}=${it.value}" } },
    restore = { list ->
        RuleSelection(
            houseRules = list.filter { it.startsWith("h:") }.map { it.removePrefix("h:") }.toSet(),
            params = list.filter { it.startsWith("p:") }.associate { it.removePrefix("p:").substringBefore('=') to it.substringAfter('=') },
        )
    },
)

@Composable
internal fun rememberRuleSelection(): androidx.compose.runtime.MutableState<RuleSelection> =
    rememberSaveable(stateSaver = RuleSelectionSaver) { mutableStateOf(RuleSelection()) }

@Composable
private fun ruleText(res: Int?, fallback: String): String = res?.let { stringResource(it) } ?: fallback

/**
 * 룰셋/하우스룰 선택(설계 §10.2). 접힌 상태에서는 "표준 규칙 / 바꾼 규칙 N개"만 보이고, 펼치면 하우스룰 스위치와 세부 설정이 나온다.
 * 이 화면은 규칙을 판단하지 않는다 — 목록·범위·기본값·검증은 모두 코어의 [RuleSetEditor]에서 온다.
 * @param unratedNote 방(멀티플레이)에서 규칙을 바꾸면 레이팅에서 제외된다는 안내를 보일지
 */
@Composable
internal fun RuleSetPicker(
    editor: RuleSetEditor,
    selection: RuleSelection,
    onChange: (RuleSelection) -> Unit,
    modifier: Modifier = Modifier,
    unratedNote: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val issues = editor.issues(selection)
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (selection.isStandard) stringResource(R.string.rules_standard) else stringResource(R.string.rules_changed, selection.changeCount),
                        modifier = Modifier.testTag(RuleTags.SUMMARY),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag(RuleTags.TOGGLE)) {
                    Text(stringResource(if (expanded) R.string.rules_collapse else R.string.rules_edit))
                }
            }
            if (unratedNote && !selection.isStandard) {
                Text(stringResource(R.string.rules_unrated_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (issues.isNotEmpty()) {
                Text(
                    stringResource(R.string.rules_invalid, issues.first()),
                    modifier = Modifier.testTag(RuleTags.INVALID),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (expanded) {
                Text(stringResource(R.string.rules_house_title), style = MaterialTheme.typography.titleSmall)
                editor.houseRules.forEach { rule ->
                    SwitchRow(
                        title = ruleText(RuleTexts.houseRule(rule.titleKey), rule.titleKey),
                        description = ruleText(RuleTexts.houseRule(rule.descriptionKey), rule.descriptionKey),
                        checked = rule.id in selection.houseRules,
                        onChange = { onChange(editor.toggleHouseRule(selection, rule.id, it)) },
                        tag = RuleTags.house(rule.id),
                    )
                }
                Text(stringResource(R.string.rules_params_title), style = MaterialTheme.typography.titleSmall)
                editor.params.forEach { spec ->
                    val title = ruleText(RuleTexts.paramTitle(spec.key), spec.key)
                    val description = ruleText(RuleTexts.paramDescription(spec.key), "")
                    when (spec) {
                        is ParamSpec.BoolParam -> SwitchRow(
                            title = title,
                            description = description,
                            checked = editor.boolValue(selection, spec),
                            onChange = { onChange(editor.setBool(selection, spec, it)) },
                            tag = RuleTags.switch(spec.key),
                        )
                        is ParamSpec.IntParam -> StepperRow(
                            title = title,
                            description = description,
                            value = editor.intValue(selection, spec),
                            default = spec.default,
                            canDecrease = editor.intValue(selection, spec) > spec.min,
                            canIncrease = editor.intValue(selection, spec) < spec.max,
                            onDecrease = { onChange(editor.setInt(selection, spec, editor.intValue(selection, spec) - 1)) },
                            onIncrease = { onChange(editor.setInt(selection, spec, editor.intValue(selection, spec) + 1)) },
                            key = spec.key,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onChange(editor.standard) },
                    enabled = !selection.isStandard,
                    modifier = Modifier.fillMaxWidth().testTag(RuleTags.RESET),
                ) { Text(stringResource(R.string.rules_reset)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun StepperRow(
    title: String,
    description: String,
    value: Int,
    default: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    key: String,
) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.rules_default_value, default.toString()), style = MaterialTheme.typography.labelSmall)
        }
        val minus = stringResource(R.string.rules_decrease, title)
        val plus = stringResource(R.string.rules_increase, title)
        OutlinedButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.semantics { contentDescription = minus }.testTag(RuleTags.minus(key))) { Text("−") }
        Text(value.toString(), modifier = Modifier.testTag(RuleTags.value(key)), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.semantics { contentDescription = plus }.testTag(RuleTags.plus(key))) { Text("+") }
    }
}
