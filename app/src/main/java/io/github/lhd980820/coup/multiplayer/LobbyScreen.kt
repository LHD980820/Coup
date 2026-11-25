package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.ui.kit.TableSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Avatar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
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
 *
 * 모습(docs/DESIGN.md "멀티플레이 화면"): 맨 위에 뒤로·제목·새로고침, 그 아래 방 만들기와 참가 코드, 그리고 열린 방들이 테이블 위의 판처럼 놓인다 —
 * 방마다 방장 얼굴, 앉은 자리 수(사람 그림), 레이팅·봇·바꾼 규칙 표, 입장 버튼.
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

    TableSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenGutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenBar(stringResource(R.string.mp_lobby_title), onBack, LobbyTags.BACK) {
                BarButton(CoupIcons.Refresh, stringResource(R.string.mp_lobby_refresh), { scope.launch { lobby.refresh() } }, LobbyTags.REFRESH)
            }

            notice?.let { NoticeBar(stringResource(MultiplayerTexts.notice(it)), LobbyTags.NOTICE, error = false) }
            state.problem?.let { NoticeBar(stringResource(MultiplayerTexts.problem(it)), LobbyTags.PROBLEM, error = true) }

            PrimaryButton(stringResource(R.string.mp_lobby_create), onCreate, Modifier.fillMaxWidth().testTag(LobbyTags.CREATE), enabled = !state.busy, icon = CoupIcons.Plus)

            // 참가 코드로 입장: 열쇠 그림의 입력 칸과 그 끝의 입장 버튼
            val joinLabel = stringResource(R.string.mp_code_join)
            val join: () -> Unit = { scope.launch { lobby.joinByCode(code) } }
            OutlinedTextField(
                value = code,
                // 공유받은 메시지를 통째로 붙여 넣으면 그 안의 코드만 남긴다
                onValueChange = { code = JoinCodes.extract(it) ?: it.take(MAX_CODE_INPUT) },
                label = { Text(stringResource(R.string.mp_code_label)) },
                supportingText = { Text(stringResource(R.string.mp_code_paste_hint)) },
                leadingIcon = { Icon(CoupIcons.Key, contentDescription = null, tint = Gold) },
                trailingIcon = {
                    IconButton(
                        onClick = join,
                        enabled = !state.busy && code.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = joinLabel }.testTag(LobbyTags.CODE_JOIN),
                    ) { Icon(CoupIcons.Enter, contentDescription = null, tint = if (!state.busy && code.isNotBlank()) GoldBright else ParchmentDim.copy(alpha = 0.5f)) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { join() }),
                modifier = Modifier.fillMaxWidth().testTag(LobbyTags.CODE),
            )

            val rooms = state.rooms
            when {
                rooms == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
                    Text(stringResource(R.string.mp_lobby_loading), color = ParchmentDim)
                }
                rooms.isEmpty() -> EmptyState(CoupIcons.Group, stringResource(R.string.mp_lobby_empty), LobbyTags.EMPTY)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(CoupIcons.Group, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.mp_lobby_open_rooms, rooms.size), style = MaterialTheme.typography.labelLarge, color = Gold)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(rooms, key = { it.id }) { room ->
                            RoomCard(room, enabled = !state.busy, onJoin = { scope.launch { lobby.join(room.id) } })
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_CODE_INPUT = 12

/**
 * 열린 방 하나: 방장 얼굴과 방 제목, 방장 이름(왕관), 앉은 자리 수, 표(레이팅·봇·바꾼 규칙), 입장 버튼. 자리가 다 찼으면 입장이 잠긴다.
 */
@Composable
private fun RoomCard(room: RoomSummary, enabled: Boolean, onJoin: () -> Unit) {
    val full = room.playerCount >= room.maxPlayers
    val players = stringResource(R.string.mp_room_players, room.playerCount, room.maxPlayers)
    Column(
        Modifier.fillMaxWidth().testTag(LobbyTags.room(room.id)).panel().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(room.hostName, seatTint(room.hostName.hashCode()), size = 40.dp, ring = GoldDeep)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(room.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val host = stringResource(R.string.mp_room_host_name, room.hostName)
                Row(Modifier.clearAndSetSemantics { contentDescription = host }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(CoupIcons.Crown, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
                    Text(room.hostName, style = MaterialTheme.typography.labelMedium, color = ParchmentDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PrimaryButton(
                stringResource(R.string.mp_room_join), onJoin, Modifier.testTag(LobbyTags.join(room.id)),
                enabled = enabled && !full, icon = CoupIcons.Enter, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatDots(room.playerCount, room.maxPlayers, players)
            Text(players, style = MaterialTheme.typography.labelMedium, color = if (full) CrimsonLight else ParchmentDim, modifier = Modifier.clearAndSetSemantics { })
        }
        if (room.isRated || room.hasBots || room.hasCustomRules) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (room.isRated) IconChip(CoupIcons.Star, stringResource(R.string.mp_badge_rated), color = Gold)
                if (room.hasBots) IconChip(CoupIcons.Robot, stringResource(R.string.mp_badge_bots))
                if (room.hasCustomRules) IconChip(CoupIcons.Scroll, stringResource(R.string.mp_badge_custom))
            }
        }
    }
}
