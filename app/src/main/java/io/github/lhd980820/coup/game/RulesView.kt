package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.rules.ParamSpec
import io.github.lhd980820.coup.presentation.rules.RuleSelection
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor

internal object RulesViewTags {
    const val BADGE = "rules_badge"
    const val DIALOG = "rules_dialog"
    const val DIALOG_CLOSE = "rules_dialog_close"
    fun line(id: String) = "rules_line_$id"
}

/** 규칙 한 줄: 하우스룰(제목+설명) 또는 바꾼 값(이름+값, 기본값). [id]는 테스트용 식별자. */
internal data class RuleLine(val id: String, val title: String, val detail: String)

/**
 * 이 판에서 표준과 다른 규칙들. 표준이면 빈 목록, 이 버전이 모르는 규칙이면 null(화면은 "알 수 없음"을 보인다).
 * 목록·기본값·번역은 룰 편집기와 같은 곳에서 온다 — 규칙을 판단하지 않는다.
 */
@Composable
internal fun ruleLines(config: RuleSetConfig): List<RuleLine>? {
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    val selection = remember(config) { runCatching { editor.fromConfig(config) }.getOrNull() } ?: return null
    return changedRules(editor, selection)
}

@Composable
private fun changedRules(editor: RuleSetEditor, selection: RuleSelection): List<RuleLine> {
    val houses = editor.houseRules.filter { it.id in selection.houseRules }.map { rule ->
        RuleLine(
            id = rule.id,
            title = RuleTexts.houseRule(rule.titleKey)?.let { stringResource(it) } ?: rule.titleKey,
            detail = RuleTexts.houseRule(rule.descriptionKey)?.let { stringResource(it) } ?: "",
        )
    }
    val params = editor.params.filter { it.key in selection.params }.map { spec ->
        val value = when (spec) {
            is ParamSpec.IntParam -> editor.intValue(selection, spec).toString() to spec.default.toString()
            is ParamSpec.BoolParam -> onOff(editor.boolValue(selection, spec)) to onOff(spec.default)
        }
        RuleLine(
            id = spec.key,
            title = RuleTexts.paramTitle(spec.key)?.let { stringResource(it) } ?: spec.key,
            detail = stringResource(R.string.rules_changed_value, value.first, value.second),
        )
    }
    return houses + params
}

@Composable
private fun onOff(on: Boolean): String = stringResource(if (on) R.string.rules_on else R.string.rules_off)

/** 이 판의 규칙을 보여주는 대화상자: 표준이면 그렇다고, 아니면 바뀐 규칙 목록. */
@Composable
internal fun RulesDialog(config: RuleSetConfig, onDismiss: () -> Unit) {
    val lines = ruleLines(config)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RulesViewTags.DIALOG),
        title = { Text(stringResource(R.string.rules_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    lines == null -> Text(stringResource(R.string.rules_unknown))
                    lines.isEmpty() -> Text(stringResource(R.string.rules_standard))
                    else -> lines.forEach { line ->
                        Column(Modifier.testTag(RulesViewTags.line(line.id)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(line.title, style = MaterialTheme.typography.titleSmall)
                            if (line.detail.isNotEmpty()) Text(line.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(RulesViewTags.DIALOG_CLOSE)) { Text(stringResource(R.string.game_ability_close)) }
        },
    )
}

/** 표준과 다른 규칙이 있는 판인가. 규칙을 모르면(null) 아니다. */
internal val RuleSetConfig?.isCustom: Boolean get() = this != null && (houseRules.isNotEmpty() || paramOverrides.isNotEmpty())
