package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.kit.TableSurface
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

    TableSurface(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.game_title), style = MaterialTheme.typography.headlineMedium)

            SectionTitle(stringResource(R.string.game_setup_opponents))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    ChoiceTile(n.toString(), selected = opponents == n, onClick = { opponents = n }, modifier = Modifier.weight(1f).testTag(SetupTags.opponents(n)), big = true)
                }
            }

            SectionTitle(stringResource(R.string.game_setup_difficulty))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AiDifficulty.EASY to R.string.game_difficulty_easy,
                    AiDifficulty.NORMAL to R.string.game_difficulty_normal,
                    AiDifficulty.HARD to R.string.game_difficulty_hard,
                ).forEach { (d, label) ->
                    ChoiceTile(stringResource(label), selected = difficulty == d, onClick = { difficulty = d }, modifier = Modifier.weight(1f).testTag(SetupTags.difficulty(d)))
                }
            }

            RuleSetPicker(editor, rules.value, onChange = { rules.value = it })

            PrimaryButton(
                stringResource(R.string.game_setup_start),
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
                icon = CoupIcons.Swords,
                modifier = Modifier.fillMaxWidth().testTag(SetupTags.START),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Gold)
}

/**
 * 여러 개 중 하나를 고르는 칸(상대 수, 난이도). 고른 칸은 금색으로 채우고 굵게 쓴다 — 스크린리더에는 라디오 버튼으로 읽힌다.
 */
@Composable
private fun ChoiceTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .panel(
                shape,
                top = if (selected) GoldBright else PanelHigh,
                bottom = if (selected) Gold else Panel,
                line = if (selected) GoldBright else Hairline,
                lineWidth = if (selected) 2.dp else 1.dp,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
            color = if (selected) GoldInk else ParchmentDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** 저장된 규칙 선택이 이 버전에서도 유효한가(모르는 하우스룰·항목이 있거나 게임이 못 되는 조합이면 쓰지 않는다). */
private fun usable(editor: RuleSetEditor, selection: RuleSelection): Boolean =
    runCatching { editor.fromConfig(editor.config(selection)) }.isSuccess && editor.issues(selection).isEmpty()
