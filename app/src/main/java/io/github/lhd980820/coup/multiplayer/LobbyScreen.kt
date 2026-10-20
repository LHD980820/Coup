package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.lobby.JoinCodes
import io.github.lhd980820.coup.lobby.RoomSummary
import io.github.lhd980820.coup.shell.lobby.LobbyEvent
import io.github.lhd980820.coup.shell.lobby.LobbyModel
import kotlinx.coroutines.launch

internal object LobbyTags {
    const val BACK = "lobby_back"
    const val CREATE = "lobby_create"
    const val REFRESH = "lobby_refresh"
    const val EMPTY = "lobby_empty"
    const val CODE = "lobby_code"
    const val CODE_JOIN = "lobby_code_join"
    const val PROBLEM = "lobby_problem"
    const val NOTICE = "lobby_notice"
    fun room(id: String) = "lobby_room_$id"
    fun join(id: String) = "lobby_join_$id"
}

/**
 * 방 목록 화면(설계 §10.2). 목록 읽기·입장·생성은 코어의 [LobbyModel]이 하고, 이 화면은 보여주기만 한다.
 * 화면이 보이는 동안 [LobbyModel.run]이 목록을 주기적으로 새로 읽는다.
 */
@Composable
internal fun LobbyScreen(
    lobby: LobbyModel,
    notice: RoomNotice?,
    onEnterRoom: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by lobby.state.collectAsState()
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(lobby) { lobby.run() }
    LaunchedEffect(lobby) { lobby.events.collect { if (it is LobbyEvent.EnterRoom) onEnterRoom(it.roomId) } }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag(LobbyTags.BACK)) { Text(stringResource(R.string.mp_back)) }
                Text(stringResource(R.string.mp_lobby_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { lobby.refresh() } }, modifier = Modifier.testTag(LobbyTags.REFRESH)) {
                    Text(stringResource(R.string.mp_lobby_refresh))
                }
            }

            notice?.let {
                Text(stringResource(MultiplayerTexts.notice(it)), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(LobbyTags.NOTICE))
            }
            state.problem?.let {
                Text(
                    stringResource(MultiplayerTexts.problem(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LobbyTags.PROBLEM),
                )
            }

            Button(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag(LobbyTags.CREATE)) {
                Text(stringResource(R.string.mp_lobby_create))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    // 공유받은 메시지를 통째로 붙여 넣으면 그 안의 코드만 남긴다
                    onValueChange = { code = JoinCodes.extract(it) ?: it.take(MAX_CODE_INPUT) },
                    label = { Text(stringResource(R.string.mp_code_label)) },
                    supportingText = { Text(stringResource(R.string.mp_code_paste_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { scope.launch { lobby.joinByCode(code) } }),
                    modifier = Modifier.weight(1f).testTag(LobbyTags.CODE),
                )
                OutlinedButton(
                    onClick = { scope.launch { lobby.joinByCode(code) } },
                    enabled = !state.busy && code.isNotBlank(),
                    modifier = Modifier.testTag(LobbyTags.CODE_JOIN),
                ) { Text(stringResource(R.string.mp_code_join)) }
            }

            val rooms = state.rooms
            when {
                rooms == null -> Text(stringResource(R.string.mp_lobby_loading))
                rooms.isEmpty() -> Text(stringResource(R.string.mp_lobby_empty), modifier = Modifier.testTag(LobbyTags.EMPTY))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(rooms, key = { it.id }) { room ->
                        RoomCard(room, enabled = !state.busy, onJoin = { scope.launch { lobby.join(room.id) } })
                    }
                }
            }
        }
    }
}

private const val MAX_CODE_INPUT = 12

@Composable
private fun RoomCard(room: RoomSummary, enabled: Boolean, onJoin: () -> Unit) {
    val full = room.playerCount >= room.maxPlayers
    Card(Modifier.fillMaxWidth().testTag(LobbyTags.room(room.id))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(room.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.mp_room_host_name, room.hostName) + " · " + stringResource(R.string.mp_room_players, room.playerCount, room.maxPlayers),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (room.isRated) Badge(R.string.mp_badge_rated)
                    if (room.hasBots) Badge(R.string.mp_badge_bots)
                    if (room.hasCustomRules) Badge(R.string.mp_badge_custom)
                }
            }
            Button(onClick = onJoin, enabled = enabled && !full, modifier = Modifier.testTag(LobbyTags.join(room.id))) {
                Text(stringResource(R.string.mp_room_join))
            }
        }
    }
}

@Composable
private fun Badge(label: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(label), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}
