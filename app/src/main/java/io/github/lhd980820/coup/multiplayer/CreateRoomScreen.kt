package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.ui.kit.TableSurface
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.fadingEdges
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.CoupIcons
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
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

/**
 * 방 만들기(설계 §10.2): 제목·공개 여부·최대 인원·룰. 규칙 검증과 방 생성은 코어(`LobbyModel`/`RoomRules`)가 한다.
 *
 * 모습(docs/DESIGN.md "멀티플레이 화면"): 제목 칸, 공개/비공개 두 판(지구·자물쇠), 최대 인원은 숫자 동그라미(고른 만큼 자리 그림이 켜진다), 규칙.
 * 만들기 버튼은 맨 아래에 고정된다.
 */
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

    TableSurface(modifier) {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            ScreenBar(stringResource(R.string.mp_create_title), onBack, CreateTags.BACK, Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            Column(
                Modifier.weight(1f).fadingEdges(scroll).verticalScroll(scroll).padding(horizontal = ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.mp_create_name)) },
                    leadingIcon = { Icon(CoupIcons.Pencil, contentDescription = null, tint = Gold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(CreateTags.TITLE),
                )

                // 공개 여부: 두 판 중 하나
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VisibilityTile(
                        CoupIcons.Globe, stringResource(R.string.mp_create_public), stringResource(R.string.mp_create_public_hint),
                        selected = visibility == Visibility.PUBLIC, tag = CreateTags.PUBLIC, modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { visibility = Visibility.PUBLIC }
                    VisibilityTile(
                        CoupIcons.Lock, stringResource(R.string.mp_create_private_short), stringResource(R.string.mp_create_private_hint),
                        selected = visibility == Visibility.PRIVATE, tag = CreateTags.PRIVATE, modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { visibility = Visibility.PRIVATE }
                }

                // 최대 인원: 숫자 동그라미, 아래에 그만큼 켜진 자리
                Section(stringResource(R.string.mp_create_max), CoupIcons.Group) {
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        range.forEach { n -> CountToken(n, selected = effectiveMax == n, tag = CreateTags.max(n)) { max = n } }
                    }
                    SeatDots(effectiveMax, range.last, stringResource(R.string.mp_max_players, effectiveMax), size = 18.dp)
                }

                RuleSetPicker(editor, rules.value, onChange = { rules.value = it }, unratedNote = true)

                state.problem?.let { NoticeBar(stringResource(MultiplayerTexts.problem(it)), CreateTags.PROBLEM, error = true) }
            }
            PrimaryButton(
                stringResource(R.string.mp_create_submit),
                { scope.launch { lobby.createRoom(title, visibility, effectiveMax, editor.config(rules.value)) } },
                Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 10.dp).testTag(CreateTags.SUBMIT),
                enabled = !state.busy && title.isNotBlank() && editor.issues(rules.value).isEmpty(),
                icon = CoupIcons.Plus,
            )
        }
    }
}

/** 공개 여부 판 하나: 그림, 이름, 한 줄 설명. 고르면 금색 테두리와 체크. */
@Composable
private fun VisibilityTile(icon: ImageVector, title: String, hint: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag)
            .panel(
                top = if (selected) SelectedTop else PanelHigh,
                line = if (selected) GoldBright else Hairline,
                lineWidth = if (selected) 2.dp else 1.dp,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = if (selected) GoldBright else ParchmentDim, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (selected) Parchment else ParchmentDim, modifier = Modifier.weight(1f))
            if (selected) Icon(CoupIcons.Check, contentDescription = null, tint = GoldBright, modifier = Modifier.size(18.dp))
        }
        Text(hint, style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
    }
}

/** 최대 인원 숫자 하나: 동그라미 안의 숫자. 고르면 금색으로 채운다. */
@Composable
private fun CountToken(n: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(42.dp).panel(CircleShape, top = if (selected) GoldBright else PanelTop, bottom = if (selected) Gold else Panel, line = if (selected) GoldBright else Hairline),
            contentAlignment = Alignment.Center,
        ) { Text(n.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = if (selected) GoldInk else Parchment) }
    }
}

/** 고른 판의 따뜻한 빛. */
private val SelectedTop = Color(0xFF3A2C16)
