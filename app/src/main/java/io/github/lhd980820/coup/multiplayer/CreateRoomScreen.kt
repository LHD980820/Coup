package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.game.RuleSetPicker
import io.github.lhd980820.coup.game.rememberRuleSelection
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor
import io.github.lhd980820.coup.shell.lobby.LobbyEvent
import io.github.lhd980820.coup.shell.lobby.LobbyModel
import kotlinx.coroutines.launch

internal object CreateTags {
    const val BACK = "create_back"
    const val TITLE = "create_title"
    const val PUBLIC = "create_public"
    const val PRIVATE = "create_private"
    const val SUBMIT = "create_submit"
    const val PROBLEM = "create_problem"
    fun max(n: Int) = "create_max_$n"
}

/** 방 만들기(설계 §10.2): 제목·공개 여부·최대 인원·룰. 규칙 검증과 방 생성은 코어(`LobbyModel`/`RoomRules`)가 한다. */
@Composable
internal fun CreateRoomScreen(lobby: LobbyModel, defaultTitle: String, onEnterRoom: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by lobby.state.collectAsState()
    LaunchedEffect(lobby) { lobby.events.collect { if (it is LobbyEvent.EnterRoom) onEnterRoom(it.roomId) } }
    val scope = rememberCoroutineScope()
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    val rules = rememberRuleSelection()
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var visibility by rememberSaveable { mutableStateOf(Visibility.PUBLIC) }
    var max by rememberSaveable { mutableIntStateOf(4) }
    val range = if (editor.issues(rules.value).isEmpty()) editor.playerRange(rules.value) else 2..6
    val effectiveMax = max.coerceIn(range.first, range.last)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(CreateTags.BACK)) { Text(stringResource(R.string.mp_back)) }
            Text(stringResource(R.string.mp_create_title), style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.mp_create_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(CreateTags.TITLE),
            )

            Column(Modifier.selectableGroup()) {
                listOf(
                    Visibility.PUBLIC to (R.string.mp_create_public to CreateTags.PUBLIC),
                    Visibility.PRIVATE to (R.string.mp_create_private to CreateTags.PRIVATE),
                ).forEach { (v, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = visibility == v, onClick = { visibility = v }, role = Role.RadioButton)
                            .testTag(label.second)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = visibility == v, onClick = null)
                        Text(stringResource(label.first), Modifier.padding(start = 8.dp))
                    }
                }
            }

            Text(stringResource(R.string.mp_create_max), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                range.forEach { n ->
                    FilterChip(
                        selected = effectiveMax == n,
                        onClick = { max = n },
                        label = { Text(n.toString()) },
                        modifier = Modifier.testTag(CreateTags.max(n)),
                    )
                }
            }

            RuleSetPicker(editor, rules.value, onChange = { rules.value = it }, unratedNote = true)

            state.problem?.let {
                Text(
                    stringResource(MultiplayerTexts.problem(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(CreateTags.PROBLEM),
                )
            }

            Button(
                onClick = { scope.launch { lobby.createRoom(title, visibility, effectiveMax, editor.config(rules.value)) } },
                enabled = !state.busy && title.isNotBlank() && editor.issues(rules.value).isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(CreateTags.SUBMIT),
            ) { Text(stringResource(R.string.mp_create_submit)) }
        }
    }
}
