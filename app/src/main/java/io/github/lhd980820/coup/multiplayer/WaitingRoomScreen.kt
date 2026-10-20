package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.game.RuleSetPicker
import io.github.lhd980820.coup.game.RuleTexts
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.SeatKind
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor
import io.github.lhd980820.coup.shell.lobby.WaitingRoomEvent
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import io.github.lhd980820.coup.shell.lobby.WaitingRoomUiState
import io.github.lhd980820.coup.shell.lobby.WaitingSeat
import kotlinx.coroutines.launch

internal object WaitingTags {
    const val LEAVE = "room_leave"
    const val CODE = "room_code"
    const val READY = "room_ready"
    const val START = "room_start"
    const val BLOCKERS = "room_blockers"
    const val PROBLEM = "room_problem"
    const val LAUNCHING = "room_launching"
    const val WAITING_HOST = "room_waiting_host"
    const val RATED_NOTE = "room_rated_note"
    const val RULES_READONLY = "room_rules_readonly"
    const val ADD_BOT_EASY = "room_add_bot_easy"
    const val ADD_BOT_NORMAL = "room_add_bot_normal"
    const val MAX_MINUS = "room_max_minus"
    const val MAX_PLUS = "room_max_plus"
    const val MAX_VALUE = "room_max_value"
    fun seat(id: String) = "room_seat_$id"
    fun kick(id: String) = "room_kick_$id"
    const val EMPTY_SEAT = "room_empty_seat"
    const val REJOIN = "room_rejoin"
    const val CODE_COPY = "room_code_copy"
    const val CODE_SHARE = "room_code_share"
    const val CODE_COPIED = "room_code_copied"
}

/**
 * 대기실(설계 §10.2). 방 구독·준비·봇·내보내기·시작은 코어의 [WaitingRoomModel]이 하고, 규칙 위반은 이유가 문장으로 나온다.
 * 방장에게만 보이는 컨트롤(봇, 최대 인원, 규칙 변경, 시작)과 게스트에게만 보이는 컨트롤(준비)을 구분한다.
 */
@Composable
internal fun WaitingRoomScreen(model: WaitingRoomModel, onEvent: (WaitingRoomEvent) -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model) { model.events.collect(onEvent) }

    val s = state
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (s == null) return@Surface
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { model.leave() } }, enabled = !s.busy, modifier = Modifier.testTag(WaitingTags.LEAVE)) {
                    Text(stringResource(R.string.mp_leave))
                }
            }
            if (s.visibility == Visibility.PRIVATE && s.joinCode != null) {
                JoinCodeRow(title = s.title, code = s.joinCode!!)
            }
            Text(
                stringResource(if (s.rated) R.string.mp_room_rated else R.string.mp_room_unrated),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(WaitingTags.RATED_NOTE),
            )

            s.seats.forEach { seat ->
                SeatRow(seat, canRemove = seat.removable && !s.busy, onRemove = { scope.launch { if (seat.kind == SeatKind.BOT) model.removeBot(seat.id) else model.kick(seat.id) } })
            }
            repeat(s.emptySeats) {
                Card(Modifier.fillMaxWidth().testTag(WaitingTags.EMPTY_SEAT), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(stringResource(R.string.mp_seat_empty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (s.isHost) HostControls(model, s) else RulesReadOnly(s.ruleSetConfig)

            s.problem?.let {
                Text(
                    stringResource(MultiplayerTexts.problem(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WaitingTags.PROBLEM),
                )
            }
            // 진행 중인 게임이 있는데 게임 화면 밖에 있다(방장과 끊겨 나왔거나, 들어가다 실패했다) -> 다시 들어간다
            if (s.gameInProgress && !s.launching) {
                Button(onClick = { scope.launch { model.rejoin() } }, modifier = Modifier.fillMaxWidth().testTag(WaitingTags.REJOIN)) {
                    Text(stringResource(R.string.mp_rejoin))
                }
            }
            if (s.launching) {
                Text(stringResource(R.string.mp_launching), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WaitingTags.LAUNCHING))
            }

            if (s.isHost) {
                if (s.blockers.isNotEmpty()) {
                    Text(
                        s.blockers.map { stringResource(MultiplayerTexts.room(it)) }.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(WaitingTags.BLOCKERS),
                    )
                }
                Button(
                    onClick = { scope.launch { model.start() } },
                    enabled = s.canStart && !s.busy,
                    modifier = Modifier.fillMaxWidth().testTag(WaitingTags.START),
                ) { Text(stringResource(R.string.mp_start)) }
            } else {
                Button(
                    onClick = { scope.launch { model.toggleReady() } },
                    enabled = !s.busy && !s.launching,
                    modifier = Modifier.fillMaxWidth().testTag(WaitingTags.READY),
                ) { Text(stringResource(if (s.myReady) R.string.mp_unready else R.string.mp_ready)) }
                if (s.myReady && !s.launching) {
                    Text(stringResource(R.string.mp_waiting_host), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(WaitingTags.WAITING_HOST))
                }
            }
        }
    }
}

/** 비공개 방의 참가 코드와 복사·공유. 기기 밖으로 내보내는 일은 [CodeSharer]가 하고, 없으면 코드만 보인다. */
@Composable
private fun JoinCodeRow(title: String, code: String) {
    val sharer = LocalCodeSharer.current
    val label = stringResource(R.string.mp_code_label)
    val message = stringResource(R.string.mp_code_share_message, title, code)
    val chooser = stringResource(R.string.mp_code_share_chooser)
    var copied by remember(code) { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.mp_room_code, code), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(WaitingTags.CODE))
        if (sharer != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copied = sharer.copy(label, code) }, modifier = Modifier.testTag(WaitingTags.CODE_COPY)) {
                    Text(stringResource(R.string.mp_code_copy))
                }
                OutlinedButton(onClick = { sharer.share(message, chooser) }, modifier = Modifier.testTag(WaitingTags.CODE_SHARE)) {
                    Text(stringResource(R.string.mp_code_share))
                }
            }
            copied?.let {
                Text(
                    stringResource(if (it) R.string.mp_code_copied else R.string.mp_code_copy_failed),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WaitingTags.CODE_COPIED),
                )
            }
        }
    }
}

@Composable
private fun SeatRow(seat: WaitingSeat, canRemove: Boolean, onRemove: () -> Unit) {
    val name = if (seat.isMe) "${seat.name} ${stringResource(R.string.mp_seat_me)}" else seat.name
    Card(Modifier.fillMaxWidth().testTag(WaitingTags.seat(seat.id))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                val detail = when {
                    seat.isHost -> stringResource(R.string.mp_seat_host)
                    seat.kind == SeatKind.BOT ->
                        stringResource(R.string.mp_seat_bot, stringResource(MultiplayerTexts.botLevel(seat.botLevel)), stringResource(MultiplayerTexts.botStyle(seat.botStyle)))
                    seat.ready -> stringResource(R.string.mp_seat_ready)
                    else -> stringResource(R.string.mp_seat_not_ready)
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            if (seat.removable) {
                val label = stringResource(if (seat.kind == SeatKind.BOT) R.string.mp_remove_bot_desc else R.string.mp_kick_desc, seat.name)
                OutlinedButton(
                    onClick = onRemove,
                    enabled = canRemove,
                    modifier = Modifier.semantics { contentDescription = label }.testTag(WaitingTags.kick(seat.id)),
                ) { Text("✕") }
            }
        }
    }
}

@Composable
private fun HostControls(model: WaitingRoomModel, s: WaitingRoomUiState) {
    val scope = rememberCoroutineScope()
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    val selection = remember(s.ruleSetConfig) { runCatching { editor.fromConfig(s.ruleSetConfig) }.getOrNull() }
    val range = selection?.let { editor.playerRange(it) } ?: 2..6

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val minus = stringResource(R.string.mp_max_decrease)
        val plus = stringResource(R.string.mp_max_increase)
        Text(stringResource(R.string.mp_max_players, s.maxPlayers), Modifier.weight(1f).testTag(WaitingTags.MAX_VALUE), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { scope.launch { model.setMaxPlayers(s.maxPlayers - 1) } },
            enabled = !s.busy && s.maxPlayers > maxOf(range.first, s.seats.size),
            modifier = Modifier.defaultMinSize(minWidth = 48.dp).semantics { contentDescription = minus }.testTag(WaitingTags.MAX_MINUS),
        ) { Text("−") }
        OutlinedButton(
            onClick = { scope.launch { model.setMaxPlayers(s.maxPlayers + 1) } },
            enabled = !s.busy && s.maxPlayers < range.last,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp).semantics { contentDescription = plus }.testTag(WaitingTags.MAX_PLUS),
        ) { Text("+") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { scope.launch { model.addBot(BotLevel.EASY) } },
            enabled = !s.busy && s.emptySeats > 0,
            modifier = Modifier.weight(1f).testTag(WaitingTags.ADD_BOT_EASY),
        ) { Text(stringResource(R.string.mp_add_bot_easy)) }
        OutlinedButton(
            onClick = { scope.launch { model.addBot(BotLevel.NORMAL) } },
            enabled = !s.busy && s.emptySeats > 0,
            modifier = Modifier.weight(1f).testTag(WaitingTags.ADD_BOT_NORMAL),
        ) { Text(stringResource(R.string.mp_add_bot_normal)) }
    }
    if (selection != null) {
        RuleSetPicker(editor, selection, onChange = { scope.launch { model.updateRules(editor.config(it)) } }, unratedNote = true)
    }
}

/** 게스트에게 보이는 방의 규칙 요약(바꿀 수 없다). */
@Composable
private fun RulesReadOnly(config: RuleSetConfig) {
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    val selection = remember(config) { runCatching { editor.fromConfig(config) }.getOrNull() }
    val text = if (selection == null || selection.isStandard) {
        stringResource(R.string.rules_standard)
    } else {
        val houses = editor.houseRules.filter { it.id in selection.houseRules }.map { r ->
            RuleTexts.houseRule(r.titleKey)?.let { stringResource(it) } ?: r.titleKey
        }
        val params = selection.params.map { (key, value) ->
            (RuleTexts.paramTitle(key)?.let { stringResource(it) } ?: key) + ": " + value
        }
        (houses + params).joinToString(", ")
    }
    Text(stringResource(R.string.mp_rules_readonly, text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(WaitingTags.RULES_READONLY))
}

