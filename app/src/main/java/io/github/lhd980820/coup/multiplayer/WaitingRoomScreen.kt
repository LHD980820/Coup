package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.ui.kit.TableSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.felt
import io.github.lhd980820.coup.ui.kit.fadingEdges
import io.github.lhd980820.coup.ui.kit.SecondaryButton
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Avatar
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
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
 *
 * 모습(docs/DESIGN.md "멀티플레이 화면"): 맨 위에 방 제목과 나가기, 그 아래 참가 코드 표(비공개 방), 레이팅 표, 테이블에 앉은 사람들의 자리(두 줄씩),
 * 방장 컨트롤 또는 규칙. 시작·준비 버튼은 맨 아래에 고정되어 늘 닿는다.
 */
@Composable
internal fun WaitingRoomScreen(model: WaitingRoomModel, onEvent: (WaitingRoomEvent) -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model) { model.events.collect(onEvent) }

    val s = state
    TableSurface(modifier) {
        if (s == null) return@TableSurface
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            ScreenBar(s.title, onBack = null, backTag = "", modifier = Modifier.padding(start = ScreenGutter, end = 8.dp, top = 6.dp)) {
                BarButton(CoupIcons.Exit, stringResource(R.string.mp_leave), { scope.launch { model.leave() } }, WaitingTags.LEAVE, enabled = !s.busy)
            }
            Column(
                Modifier.weight(1f).fadingEdges(scroll).verticalScroll(scroll).padding(horizontal = ScreenGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (s.visibility == Visibility.PRIVATE && s.joinCode != null) {
                    JoinCodeTicket(title = s.title, code = s.joinCode!!)
                }
                RatedNote(s.rated)
                SeatGrid(s, onRemove = { seat -> scope.launch { if (seat.kind == SeatKind.BOT) model.removeBot(seat.id) else model.kick(seat.id) } })
                if (s.isHost) HostControls(model, s) else RulesReadOnly(s.ruleSetConfig)
                s.problem?.let { NoticeBar(stringResource(MultiplayerTexts.problem(it)), WaitingTags.PROBLEM, error = true) }
            }
            BottomActions(model, s)
        }
    }
}

/** 맨 아래에 고정된 행동: 방장은 시작(못 하면 이유), 손님은 준비/준비 취소. 진행 중인 게임이 있으면 돌아가기. */
@Composable
private fun BottomActions(model: WaitingRoomModel, s: WaitingRoomUiState) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 진행 중인 게임이 있는데 게임 화면 밖에 있다(방장과 끊겨 나왔거나, 들어가다 실패했다) -> 다시 들어간다
        if (s.gameInProgress && !s.launching) {
            PrimaryButton(stringResource(R.string.mp_rejoin), { scope.launch { model.rejoin() } }, Modifier.fillMaxWidth().testTag(WaitingTags.REJOIN), icon = CoupIcons.Enter)
        }
        if (s.launching) {
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(WaitingTags.LAUNCHING),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
                Text(stringResource(R.string.mp_launching), color = Parchment)
            }
        }
        if (s.isHost) {
            if (s.blockers.isNotEmpty()) {
                Row(Modifier.testTag(WaitingTags.BLOCKERS), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CoupIcons.Hourglass, contentDescription = null, tint = ParchmentDim, modifier = Modifier.padding(top = 2.dp).size(15.dp))
                    Text(s.blockers.map { stringResource(MultiplayerTexts.room(it)) }.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = ParchmentDim)
                }
            }
            PrimaryButton(
                stringResource(R.string.mp_start), { scope.launch { model.start() } },
                Modifier.fillMaxWidth().testTag(WaitingTags.START), enabled = s.canStart && !s.busy, icon = CoupIcons.Swords,
            )
        } else {
            if (s.myReady) {
                SecondaryButton(
                    stringResource(R.string.mp_unready), { scope.launch { model.toggleReady() } },
                    Modifier.fillMaxWidth().testTag(WaitingTags.READY), enabled = !s.busy && !s.launching, icon = CoupIcons.Cross, line = io.github.lhd980820.coup.ui.kit.Hairline, content = ParchmentDim,
                )
            } else {
                PrimaryButton(
                    stringResource(R.string.mp_ready), { scope.launch { model.toggleReady() } },
                    Modifier.fillMaxWidth().testTag(WaitingTags.READY), enabled = !s.busy && !s.launching, icon = CoupIcons.Check,
                )
            }
            if (s.myReady && !s.launching) {
                Row(Modifier.testTag(WaitingTags.WAITING_HOST), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CoupIcons.Hourglass, contentDescription = null, tint = ParchmentDim, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.mp_waiting_host), style = MaterialTheme.typography.bodyMedium, color = ParchmentDim)
                }
            }
        }
    }
}

/** 레이팅에 반영되는지: 별(반영) 또는 흐린 별(봇이나 바꾼 규칙이 있어 반영 안 됨)과 한 줄. */
@Composable
private fun RatedNote(rated: Boolean) {
    Row(
        Modifier.fillMaxWidth().testTag(WaitingTags.RATED_NOTE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CoupIcons.Star, contentDescription = null, tint = if (rated) Gold else ParchmentDim.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Text(
            stringResource(if (rated) R.string.mp_room_rated else R.string.mp_room_unrated),
            style = MaterialTheme.typography.labelLarge,
            color = if (rated) Gold else ParchmentDim,
        )
    }
}

/**
 * 비공개 방의 참가 코드 표: 열쇠 그림, 큰 코드, 복사·공유 그림 버튼. 기기 밖으로 내보내는 일은 [CodeSharer]가 하고, 없으면 코드만 보인다.
 */
@Composable
private fun JoinCodeTicket(title: String, code: String) {
    val sharer = LocalCodeSharer.current
    val label = stringResource(R.string.mp_code_label)
    val message = stringResource(R.string.mp_code_share_message, title, code)
    val chooser = stringResource(R.string.mp_code_share_chooser)
    var copied by remember(code) { mutableStateOf<Boolean?>(null) }
    val spoken = stringResource(R.string.mp_room_code, code)
    Column(
        Modifier.fillMaxWidth().felt().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(CoupIcons.Key, contentDescription = null, tint = GoldBright, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = GoldBright)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                code,
                Modifier.weight(1f).semantics { contentDescription = spoken }.testTag(WaitingTags.CODE),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = Parchment,
            )
            if (sharer != null) {
                BarButton(CoupIcons.Copy, stringResource(R.string.mp_code_copy), { copied = sharer.copy(label, code) }, WaitingTags.CODE_COPY, tint = GoldBright)
                BarButton(CoupIcons.Share, stringResource(R.string.mp_code_share), { sharer.share(message, chooser) }, WaitingTags.CODE_SHARE, tint = GoldBright)
            }
        }
        copied?.let {
            Text(
                stringResource(if (it) R.string.mp_code_copied else R.string.mp_code_copy_failed),
                style = MaterialTheme.typography.labelMedium,
                color = if (it) GoldBright else CrimsonLight,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WaitingTags.CODE_COPIED),
            )
        }
    }
}

/** 테이블에 앉은 사람들: 두 자리씩 한 줄. 빈 자리는 점선으로 비어 있다. */
@Composable
private fun SeatGrid(s: WaitingRoomUiState, onRemove: (WaitingSeat) -> Unit) {
    val tiles: List<WaitingSeat?> = s.seats + List(s.emptySeats) { null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (index, seat) ->
                    val m = Modifier.weight(1f).fillMaxHeight()
                    if (seat == null) EmptySeatTile(m) else SeatTile(seat, index, canRemove = seat.removable && !s.busy, onRemove = { onRemove(seat) }, modifier = m)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 자리 하나: 얼굴(봇은 로봇, 나는 금색 사람), 이름("(나)"), 상태 한 줄(방장 왕관 / 봇 / 준비 완료 체크 / 준비 안 함 모래시계).
 * 준비한 자리와 방장 자리는 금색 테두리다. 내보낼 수 있으면 모서리에 X 버튼이 붙는다.
 */
@Composable
private fun SeatTile(seat: WaitingSeat, index: Int, canRemove: Boolean, onRemove: () -> Unit, modifier: Modifier) {
    val name = if (seat.isMe) "${seat.name} ${stringResource(R.string.mp_seat_me)}" else seat.name
    val bot = seat.kind == SeatKind.BOT
    val settled = seat.isHost || seat.ready || bot
    val (icon, tint, detail) = when {
        seat.isHost -> Triple(CoupIcons.Crown, GoldBright, stringResource(R.string.mp_seat_host))
        bot -> Triple(
            CoupIcons.Robot, ParchmentDim,
            stringResource(R.string.mp_seat_bot, stringResource(MultiplayerTexts.botLevel(seat.botLevel)), stringResource(MultiplayerTexts.botStyle(seat.botStyle))),
        )
        seat.ready -> Triple(CoupIcons.Check, GoldBright, stringResource(R.string.mp_seat_ready))
        else -> Triple(CoupIcons.Hourglass, ParchmentDim, stringResource(R.string.mp_seat_not_ready))
    }
    Box(modifier.testTag(WaitingTags.seat(seat.id))) {
        Row(
            Modifier
                .fillMaxSize()
                .panel(top = if (seat.isMe) TurnWarm else io.github.lhd980820.coup.ui.theme.PanelHigh, line = if (settled) Gold.copy(alpha = 0.8f) else io.github.lhd980820.coup.ui.kit.Hairline, lineWidth = if (settled) 1.5.dp else 1.dp)
                .padding(start = 10.dp, end = if (seat.removable) 30.dp else 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(seat.name, if (seat.isMe) Gold else seatTint(index), size = 38.dp, ring = if (settled) GoldBright else GoldDeep, icon = when {
                bot -> CoupIcons.Robot
                seat.isMe -> CoupIcons.Person
                else -> null
            })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (seat.removable) {
            val label = stringResource(if (bot) R.string.mp_remove_bot_desc else R.string.mp_kick_desc, seat.name)
            IconButton(
                onClick = onRemove,
                enabled = canRemove,
                modifier = Modifier.align(Alignment.TopEnd).size(40.dp).semantics { contentDescription = label }.testTag(WaitingTags.kick(seat.id)),
            ) { Icon(CoupIcons.Cross, contentDescription = null, tint = if (canRemove) ParchmentDim else ParchmentDim.copy(alpha = 0.4f), modifier = Modifier.size(15.dp)) }
        }
    }
}

/** 빈 자리: 점선 테두리 안의 흐린 사람 그림과 "빈 자리". */
@Composable
private fun EmptySeatTile(modifier: Modifier) {
    Row(
        modifier
            .testTag(WaitingTags.EMPTY_SEAT)
            .heightIn(min = 58.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    ParchmentDim.copy(alpha = 0.4f),
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(38.dp).border(1.dp, ParchmentDim.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(CoupIcons.Person, contentDescription = null, tint = ParchmentDim.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
        }
        Text(stringResource(R.string.mp_seat_empty), style = MaterialTheme.typography.bodyMedium, color = ParchmentDim.copy(alpha = 0.7f))
    }
}

/** 내 자리 판의 따뜻한 빛. */
private val TurnWarm = Color(0xFF3A2C16)

@Composable
private fun HostControls(model: WaitingRoomModel, s: WaitingRoomUiState) {
    val scope = rememberCoroutineScope()
    val editor = remember { RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig()) }
    val selection = remember(s.ruleSetConfig) { runCatching { editor.fromConfig(s.ruleSetConfig) }.getOrNull() }
    val range = selection?.let { editor.playerRange(it) } ?: 2..6

    // 인원과 봇: 최대 인원(−/+)과 봇 넣기
    Section(stringResource(R.string.mp_section_seats), CoupIcons.Group) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mp_max_players, s.maxPlayers), Modifier.weight(1f).testTag(WaitingTags.MAX_VALUE), style = MaterialTheme.typography.titleSmall, color = Parchment)
            StepButton(
                CoupIcons.Minus, stringResource(R.string.mp_max_decrease), WaitingTags.MAX_MINUS,
                enabled = !s.busy && s.maxPlayers > maxOf(range.first, s.seats.size),
            ) { scope.launch { model.setMaxPlayers(s.maxPlayers - 1) } }
            StepButton(
                CoupIcons.Plus, stringResource(R.string.mp_max_increase), WaitingTags.MAX_PLUS,
                enabled = !s.busy && s.maxPlayers < range.last,
            ) { scope.launch { model.setMaxPlayers(s.maxPlayers + 1) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val padding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            SecondaryButton(
                stringResource(R.string.mp_add_bot_easy), { scope.launch { model.addBot(BotLevel.EASY) } },
                Modifier.weight(1f).testTag(WaitingTags.ADD_BOT_EASY), enabled = !s.busy && s.emptySeats > 0, icon = CoupIcons.Robot, contentPadding = padding,
            )
            SecondaryButton(
                stringResource(R.string.mp_add_bot_normal), { scope.launch { model.addBot(BotLevel.NORMAL) } },
                Modifier.weight(1f).testTag(WaitingTags.ADD_BOT_NORMAL), enabled = !s.busy && s.emptySeats > 0, icon = CoupIcons.Robot, contentPadding = padding,
            )
        }
    }
    if (selection != null) {
        RuleSetPicker(editor, selection, onChange = { scope.launch { model.updateRules(editor.config(it)) } }, unratedNote = true)
    }
}

/** 게스트에게 보이는 방의 규칙 요약(바꿀 수 없다): 두루마리 그림과 한 줄. */
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
    Row(Modifier.fillMaxWidth().panel().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(CoupIcons.Scroll, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.mp_rules_readonly, text), style = MaterialTheme.typography.bodyMedium, color = Parchment, modifier = Modifier.testTag(WaitingTags.RULES_READONLY))
    }
}
