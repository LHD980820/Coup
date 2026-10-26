package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.rules.RuleSelection
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor
import io.github.lhd980820.coup.presentation.singleplayer.OpponentSpec
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig

internal object SetupTags {
    fun opponents(n: Int) = "setup_opponents_$n"
    fun difficulty(d: AiDifficulty) = "setup_difficulty_${d.name}"
    const val START = "setup_start"
}

/** 싱글플레이 설정(설계 §10.2): AI 수·난이도. 룰셋/하우스룰 선택은 후속(코어의 `RuleSetConfig`에 이미 있다). */
@Composable
internal fun SinglePlayerSetupScreen(
    onStart: (SinglePlayerConfig) -> Unit,
    modifier: Modifier = Modifier,
    /** 지난번에 시작한 선택. 있으면 화면을 그 값으로 채운다(규칙이 이 버전에서 만들 수 없는 것이면 표준으로). */
    remembered: SetupChoice? = null,
    /** 시작을 누를 때 그 선택을 알린다(기기에 저장하는 쪽이 받는다). */
    onRemember: (SetupChoice) -> Unit = {},
) {
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    var opponents by rememberSaveable { mutableIntStateOf(remembered?.opponents ?: 2) }
    var difficulty by rememberSaveable { mutableStateOf(remembered?.difficulty ?: AiDifficulty.NORMAL) }
    val rules = rememberRuleSelection(remembered?.rules?.takeIf { usable(editor, it) } ?: RuleSelection())
    val you = stringResource(R.string.game_you)
    val aiNames = (1..5).map { stringResource(R.string.game_ai_name, it) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.game_title), style = MaterialTheme.typography.headlineMedium)

            Text(stringResource(R.string.game_setup_opponents), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    FilterChip(
                        selected = opponents == n,
                        onClick = { opponents = n },
                        label = { Text(n.toString()) },
                        modifier = Modifier.testTag(SetupTags.opponents(n)),
                    )
                }
            }

            Text(stringResource(R.string.game_setup_difficulty), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                listOf(
                    AiDifficulty.EASY to R.string.game_difficulty_easy,
                    AiDifficulty.NORMAL to R.string.game_difficulty_normal,
                    AiDifficulty.HARD to R.string.game_difficulty_hard,
                ).forEach { (d, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = difficulty == d, onClick = { difficulty = d }, role = Role.RadioButton)
                            .testTag(SetupTags.difficulty(d))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = difficulty == d, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
            }

            RuleSetPicker(editor, rules.value, onChange = { rules.value = it })

            Button(
                onClick = {
                    onRemember(SetupChoice(opponents, difficulty, rules.value))
                    onStart(
                        SinglePlayerConfig(
                            opponents = List(opponents) { OpponentSpec(difficulty = difficulty, name = aiNames[it]) },
                            ruleSet = editor.config(rules.value),
                            humanName = you,
                        ),
                    )
                },
                enabled = editor.issues(rules.value).isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(SetupTags.START),
            ) { Text(stringResource(R.string.game_setup_start)) }
        }
    }
}

/** 저장된 규칙 선택이 이 버전에서도 유효한가(모르는 하우스룰·항목이 있거나 게임이 못 되는 조합이면 쓰지 않는다). */
private fun usable(editor: RuleSetEditor, selection: RuleSelection): Boolean =
    runCatching { editor.fromConfig(editor.config(selection)) }.isSuccess && editor.issues(selection).isEmpty()
