package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CARD_ASPECT
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.PlayingCardBack
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim

// ---- 상단 표시줄 --------------------------------------------------------------------------------

/**
 * 맨 위 표시줄: 누구의 차례인지(왼쪽), 그리고 구석의 작은 그림들 — 덱, 진행 기록([onShowLog]), 바뀐 규칙(있으면), 기권.
 * 글은 차례 칩 하나뿐이고 나머지는 그림이다(스크린리더에는 이름이 읽힌다). 항상 화면 맨 위에 고정돼 있어 응답 시트가 떠도 닿는다.
 */
@Composable
internal fun GameHud(state: GameUiState, onConcede: (() -> Unit)?, onShowRules: (() -> Unit)?, onShowLog: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // 차례 칩은 남는 너비 안에서만 자란다(이름이 아주 길면 줄여 쓰고, 구석의 그림들은 밀려나지 않는다)
        Box(Modifier.weight(1f)) { TurnChip(state) }
        DeckCount(state.deckSize)
        if (onShowLog != null) {
            HudButton(CoupIcons.History, stringResource(R.string.game_log_title), onShowLog, Modifier.testTag(GameTags.LOG_OPEN))
        }
        if (onShowRules != null) {
            // 표준과 다른 규칙으로 하는 판이면 눈에 띄게 알리고, 탭하면 무엇이 다른지 본다
            val count = state.ruleSet.let { it!!.houseRules.size + it.paramOverrides.size }
            val desc = stringResource(R.string.game_rules_badge_desc, count)
            TextButton(onClick = onShowRules, modifier = Modifier.semantics { contentDescription = desc }.testTag(RulesViewTags.BADGE)) {
                Icon(CoupIcons.Scroll, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.game_rules_badge, count))
            }
        }
        if (onConcede != null) {
            HudButton(CoupIcons.Flag, stringResource(R.string.game_concede), onConcede, Modifier.testTag(GameTags.CONCEDE))
        }
    }
}

/** 표시줄 구석의 그림 버튼(누르는 영역은 48dp). 이름은 스크린리더가 읽는다. */
@Composable
private fun HudButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(MIN_TOUCH).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = ParchmentDim, modifier = Modifier.size(20.dp))
    }
}

/** 덱: 카드 뒷면 그림과 남은 장수. */
@Composable
private fun DeckCount(deckSize: Int) {
    val label = stringResource(R.string.game_deck, deckSize)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = label }.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(width = 18.dp, height = 22.dp)) {
            // 두 장이 살짝 어긋나게 겹친 더미(덱이 비면 빈 자리만)
            if (deckSize > 1) PlayingCardBack(Modifier.align(Alignment.BottomEnd).size(width = 14.dp, height = 19.dp))
            if (deckSize > 0) {
                PlayingCardBack(Modifier.align(Alignment.TopStart).size(width = 14.dp, height = 19.dp))
            } else {
                Box(Modifier.align(Alignment.TopStart).size(width = 14.dp, height = 19.dp).panel(androidx.compose.foundation.shape.RoundedCornerShape(2.dp), top = Color.Transparent, bottom = Color.Transparent))
            }
        }
        Text(deckSize.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ParchmentDim)
    }
}

/** 맨 위 표시줄의 "누구의 차례" 칩: 그 사람의 아바타와 이름. 내 차례면 금색으로 채운다. 판이 끝났으면 없다. */
@Composable
private fun TurnChip(state: GameUiState, modifier: Modifier = Modifier) {
    val turn = state.turnPlayer ?: return
    val mine = turn == state.me?.id
    val index = state.opponents.indexOfFirst { it.id == turn }
    val name = if (mine) state.me?.name.orEmpty() else state.opponents.getOrNull(index)?.name ?: turn.value
    Row(
        modifier
            .testTag(GameTags.TURN_CHIP)
            // 아바타와 글자를 하나로 묶어 "AI 2의 차례"로 한 번에 읽힌다
            .semantics(mergeDescendants = true) { }
            .panel(CircleShape, top = if (mine) GoldBright else PanelTop, bottom = if (mine) Gold else Panel, line = if (mine) GoldBright else Gold.copy(alpha = 0.5f))
            .padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(name, if (mine) Gold else seatTint(index), size = 22.dp, ring = if (mine) GoldInk.copy(alpha = 0.5f) else GoldDeep, icon = if (mine) CoupIcons.Person else null)
        Text(
            if (mine) stringResource(R.string.game_turn_mine) else stringResource(R.string.game_waiting_for, name),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (mine) GoldInk else Parchment,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 이름이 아주 길면 줄여 쓴다(기권 버튼 자리는 남긴다)
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

// ---- 상대 자리 ----------------------------------------------------------------------------------

private val SEAT_GAP = 8.dp

/** 자리 하나의 최소 너비: 카드 두 장의 누르는 영역(48dp씩)이 들어가야 한다. */
private val SEAT_MIN_WIDTH = 104.dp
private val SEAT_MAX_WIDTH = 200.dp
private val SEAT_CARD_HEIGHT = 50.dp
private val SEAT_CARD_MAX_HEIGHT = 84.dp

/** 기본 크기 카드(50dp)로 그린 자리 하나의 높이(이름·코인·카드·상태 줄). */
private val SEAT_BASE_HEIGHT = 140.dp

/**
 * 자리 카드의 높이: 자리들에 줄 수 있는 높이([room])가 남으면 남는 만큼(4분의 3) 키운다. 자리 너비에 카드 두 장이 나란히 들어가야 하고,
 * [SEAT_CARD_MAX_HEIGHT]를 넘지 않는다.
 */
internal fun seatCardHeight(room: Dp?, rows: Int, seatWidth: Dp): Dp {
    if (room == null || rows <= 0) return SEAT_CARD_HEIGHT
    val spare = (room - TAB_ROOM - SEAT_BASE_HEIGHT * rows - (SEAT_GAP + TAB_ROOM) * (rows - 1)) / rows
    val byHeight = SEAT_CARD_HEIGHT + spare * 0.75f
    // 자리 안쪽 여백(양쪽 8dp)과 카드 사이(4dp)를 빼고 두 장
    val byWidth = ((seatWidth - 20.dp) / 2) / CARD_ASPECT
    return minOf(byHeight, byWidth, SEAT_CARD_MAX_HEIGHT).coerceAtLeast(SEAT_CARD_HEIGHT)
}

/**
 * 테이블 건너편의 상대들. 가로로 밀어 보지 않아도 모두 한눈에 보이게 줄을 나눈다(1~3명은 한 줄, 4명은 2x2, 5명은 3+2).
 * 화면이 좁아 한 줄에 다 못 들어가면 줄 수를 늘린다. 대상을 고르는 중이면 고를 수 있는 자리를 눌러서 고를 수 있다([onTarget]).
 */
@Composable
internal fun OpponentSeats(
    state: GameUiState,
    texts: GameTexts,
    onZoom: (ZoomTarget) -> Unit,
    onTarget: ((PlayerId) -> Unit)?,
    anchors: TableAnchors = remember { TableAnchors() },
    /** 자리들에 줄 수 있는 높이. 남으면 카드 그림을 키운다(공개된 카드가 잘 보이게). null이면 기본 크기. */
    room: Dp? = null,
) {
    val opponents = state.opponents
    if (opponents.isEmpty()) return
    // 첫 줄 자리 위에 붙는 "차례" 탭이 잘리지 않게 위를 조금 띄운다
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = TAB_ROOM)) {
        val wanted = when (opponents.size) {
            1 -> 1
            2, 4 -> 2
            else -> 3
        }
        val fits = ((maxWidth + SEAT_GAP) / (SEAT_MIN_WIDTH + SEAT_GAP)).toInt().coerceAtLeast(1)
        val columns = minOf(wanted, fits)
        val seatWidth = ((maxWidth - SEAT_GAP * (columns - 1)) / columns).coerceAtMost(SEAT_MAX_WIDTH)
        val rows = (opponents.size + columns - 1) / columns
        val cardHeight = seatCardHeight(room, rows, seatWidth)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SEAT_GAP + TAB_ROOM)) {
            opponents.withIndex().chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(SEAT_GAP, Alignment.CenterHorizontally),
                ) {
                    row.forEach { (index, op) ->
                        Seat(
                            op = op,
                            index = index,
                            texts = texts,
                            onZoom = { role -> onZoom(ZoomTarget(role, op.name, ZoomKind.OPPONENT_LOST)) },
                            onTarget = if (onTarget != null && op.targetable) ({ onTarget(op.id) }) else null,
                            isTurn = op.id == state.turnPlayer,
                            anchors = anchors,
                            cardHeight = cardHeight,
                            modifier = Modifier.width(seatWidth).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Seat(
    op: OpponentUi,
    index: Int,
    texts: GameTexts,
    onZoom: (RoleId) -> Unit,
    onTarget: (() -> Unit)?,
    isTurn: Boolean,
    anchors: TableAnchors,
    cardHeight: Dp,
    modifier: Modifier,
) {
    val cardWidth = cardHeight * CARD_ASPECT
    // 카드 그림은 작아도 누르는 영역은 48dp를 채운다
    val slotWidth = maxOf(MIN_TOUCH, cardWidth)
    val busy = op.isAlive && (op.status == OpponentStatus.ACTING || op.status == OpponentStatus.THINKING)
    // 지금 움직이는 사람과 고를 수 있는 대상은 금색 테두리가 천천히 맥박친다(상태 글자도 함께 달라진다).
    // 맥박 값은 그리는 단계에서만 읽는다 — 매 프레임 자리를 다시 구성하지 않는다
    val highlight = when {
        op.targetable -> GoldBright
        busy -> Gold
        else -> null
    }
    val glow = if (highlight != null) pulse() else null
    val targetLabel = stringResource(R.string.game_target_tap)
    // 처음 그릴 때 살아 있던 자리가 탈락하면(그 순간을 화면이 봤으면) 도장이 떨어지며 찍힌다. 처음부터 탈락해 있던 자리는 그냥 찍혀 있다
    val sawAlive = remember { op.isAlive }
    val turn = isTurn && op.isAlive
    // 바깥 상자는 자리 테두리에 걸치는 "차례" 탭과 내민 카드를 자르지 않게 하고, 안쪽 상자가 자리 판이다
    Box(modifier) {
    Box(
        Modifier
            .fillMaxSize()
            .seatAnchor(anchors, op.id)
            .testTag(GameTags.opponent(op.id.value))
            // 차례인 자리는 금빛이 돌고 테두리가 금색이다(맥박치는 테두리는 그 위에 겹친다)
            .then(if (turn) Modifier.panel(top = TurnGlow, line = Gold, lineWidth = 2.dp) else Modifier.panel())
            .then(
                if (highlight == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val w = 2.dp.toPx()
                    drawRoundRect(
                        highlight.copy(alpha = 0.4f + 0.6f * (glow?.value ?: 1f)),
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(16.dp.toPx() - w / 2),
                        style = Stroke(w),
                    )
                },
            )
            .then(if (onTarget != null) Modifier.clickable(onClickLabel = targetLabel, onClick = onTarget) else Modifier),
    ) {
    Column(
        // 탈락한 자리는 흐려진다(도장은 흐려지지 않는다)
        Modifier.fillMaxWidth().alpha(fadeTo(if (op.isAlive) 1f else 0.5f)).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(op.name, seatTint(index), size = 30.dp, ring = if (busy) Gold else GoldDeep.copy(alpha = 0.6f))
            Column(Modifier.weight(1f, fill = false)) {
                Text(op.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CoinCount(op.coins, GameTags.coins(op.id.value), modifier = Modifier.purseAnchor(anchors, op.id), player = op.id)
            }
        }
        Box(contentAlignment = Alignment.Center) {
        FlowRow(horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 화면을 처음 그릴 때 이미 공개돼 있던 카드는 그대로 두고, 그 뒤에 공개되는 카드만 뒤집는다
            val settled = remember { op.revealed.size }
            val zoomLabel = stringResource(R.string.game_card_inspect)
            op.revealed.forEachIndexed { i, role ->
                // 공개된 카드는 탭하면 확대된다. 그림은 작지만 누르는 영역은 48dp를 채운다
                // 태그는 누르는 영역에 둔다: 안쪽 카드의 접근성 이름·뒤집힘 진행이 이 노드로 합쳐진다
                Box(
                    Modifier.size(slotWidth, cardHeight).testTag(GameTags.flip(op.id.value, i)).clickable(onClickLabel = zoomLabel) { onZoom(role) },
                    contentAlignment = Alignment.Center,
                ) {
                    FlipCard(role, animate = i >= settled, modifier = Modifier.size(cardWidth, cardHeight))
                }
            }
            // 가려진 카드는 무엇인지 모르므로 확대할 것이 없다
            repeat(op.hiddenCount) { i ->
                Box(Modifier.size(slotWidth, cardHeight), contentAlignment = Alignment.Center) {
                    RoleCard(role = null, faceUp = false, modifier = Modifier.size(cardWidth, cardHeight).testTag(GameTags.hidden(op.id.value, i)))
                }
            }
        }
        // 역할을 주장하는 동안 그 역할 카드를 카드들 위로 크게 들어 보인다(역할 색으로 빛난다). 카드 줄보다 커도 자리 크기는 그대로다
        op.claiming.firstOrNull()?.takeIf { op.isAlive }?.let { role ->
            Box(Modifier.matchParentSize().wrapContentSize(unbounded = true)) {
                ClaimCard(role, stringResource(R.string.game_claiming, texts.roles(op.claiming)), cardHeight * CLAIM_SCALE, Modifier.testTag(GameTags.claim(op.id.value)))
            }
        }
        }
        // 상태 줄(그림): 행동·생각 중이면 남은 시간 고리(시간 제한이 없으면 모래시계), 응답했으면 체크. 탈락은 도장이, 주장은 들어 보인 카드가 알린다.
        // 스크린리더에는 상태가 글로 읽힌다. 자리가 들썩이지 않게 한 줄 높이는 늘 차지한다
        val statusLabel = when {
            !op.isAlive -> null
            op.status == OpponentStatus.ACTING -> stringResource(R.string.game_status_acting)
            op.status == OpponentStatus.THINKING -> stringResource(R.string.game_status_thinking)
            op.status == OpponentStatus.RESPONDED -> stringResource(R.string.game_status_responded)
            else -> null
        }
        Row(
            Modifier.heightIn(min = 22.dp).then(if (statusLabel != null) Modifier.semantics { contentDescription = statusLabel } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val deadline = op.deadline
            when {
                statusLabel == null -> Unit
                op.status == OpponentStatus.RESPONDED -> Icon(CoupIcons.Check, contentDescription = null, tint = ParchmentDim, modifier = Modifier.size(15.dp))
                deadline != null && busy -> Countdown(deadline, GameTags.timer(op.id.value))
                else -> Icon(CoupIcons.Hourglass, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
            }
        }
    }
    if (!op.isAlive) EliminatedStamp(animate = sawAlive, Modifier.align(Alignment.Center).testTag(GameTags.stamp(op.id.value)))
    }
    if (turn) TurnTab(stringResource(R.string.game_turn_tab), Modifier.align(Alignment.TopCenter).offset(y = -TAB_ROOM).testTag(GameTags.turn(op.id.value)))
    }
}

/** 자리 위에 걸치는 탭이 들어갈 자리. */
private val TAB_ROOM = 10.dp

/** 차례인 자리 판의 위쪽 빛. */
private val TurnGlow = Color(0xFF4D3A18)

/** 차례인 자리(나면 내 이름 옆)에 붙는 금색 탭: 별과 "차례". */
@Composable
internal fun TurnTab(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(6.dp, CircleShape)
            .panel(CircleShape, top = GoldBright, bottom = Gold, line = GoldBright, lineWidth = 1.dp)
            .padding(horizontal = 9.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(CoupIcons.Star, contentDescription = null, tint = GoldInk, modifier = Modifier.size(11.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = GoldInk, maxLines = 1)
    }
}

/**
 * 역할을 주장하는 사람이 들어 보인 카드: 자리의 카드들 위로 기울어진 채 튀어나오고 역할 색으로 빛난다. 스크린리더에는 "[역할] 주장"으로 읽힌다.
 * 그림일 뿐이라 아래 카드를 누르는 것(확대)을 가로채지 않는다.
 */
@Composable
internal fun ClaimCard(role: RoleId, label: String, height: Dp, modifier: Modifier = Modifier) {
    val width = height * CARD_ASPECT
    val motion = LocalMotion.current
    val pop = remember(role) { androidx.compose.animation.core.Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(role) {
        if (pop.value < 1f) pop.animateTo(1f, androidx.compose.animation.core.tween(motion.ms(CLAIM_POP_MS), easing = SlamEasing))
    }
    val glow = RoleVisuals.of(role.value).accent
    Box(
        modifier
            .clearAndSetSemantics { contentDescription = label }
            .size(width = width + 24.dp, height = height + 12.dp)
            .graphicsLayer {
                val p = pop.value
                scaleX = 0.4f + 0.6f * p
                scaleY = 0.4f + 0.6f * p
                alpha = p.coerceIn(0f, 1f)
            }
            .drawBehind { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.6f), Color.Transparent), center = center, radius = size.maxDimension * 0.62f)) },
        contentAlignment = Alignment.Center,
    ) {
        RoleCard(
            role, faceUp = true,
            modifier = Modifier
                .graphicsLayer { rotationZ = 8f }
                .shadow(8.dp, RoundedCornerShape(4.dp))
                .size(width = width, height = height),
        )
    }
}

/** 들어 보인 카드는 자리의 카드보다 조금 크다. */
private const val CLAIM_SCALE = 1.12f
private const val CLAIM_POP_MS = 280

// ---- 기록 ---------------------------------------------------------------------------------------

/**
 * 진행 기록. 가장 최근 일이 맨 위에 크게 나오고 오래된 일일수록 아래로 내려간다 — 아래가 잘려도 방금 일어난 일은 항상 보인다.
 * 머리에는 몇 번째 턴인지가 붙는다. 폰에서는 표시줄 구석의 기록 버튼을 눌러야 뜨고([LogSheet]), 넓은 화면에서는 옆 단에 늘 있다.
 */
@Composable
internal fun LogList(state: GameUiState, texts: GameTexts, maxLines: Int) {
    val lines = state.log.mapNotNull { texts.log(it) }.takeLast(maxLines).asReversed()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(CoupIcons.History, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.game_log_title), style = MaterialTheme.typography.labelLarge, color = Gold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.game_turn, state.turnNumber), style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
        }
        lines.forEachIndexed { i, line ->
            val latest = i == 0
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = if (latest) 7.dp else 6.dp).size(if (latest) 6.dp else 4.dp).clip(CircleShape).background(if (latest) Gold else ParchmentDim.copy(alpha = 0.6f)))
                Text(
                    line,
                    style = if (latest) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = if (latest) Parchment else ParchmentDim,
                )
            }
        }
    }
}

/** 폰의 진행 기록 창: 표시줄 구석의 기록 버튼을 누르면 뜨고, 바깥을 누르거나 뒤로 가기로 닫는다. 판은 그대로 흘러간다. */
@Composable
internal fun LogSheet(state: GameUiState, texts: GameTexts, maxLines: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag(GameTags.LOG_SHEET)
                .panel(RoundedCornerShape(22.dp), top = PanelTop, bottom = Panel, line = Gold.copy(alpha = 0.5f))
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { LogList(state, texts, maxLines) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).testTag(GameTags.LOG_CLOSE)) { Text(stringResource(R.string.game_ability_close)) }
        }
    }
}

/** 판 위의 얇은 금색 구분선. */
@Composable
internal fun GoldRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Gold.copy(alpha = 0.45f), Color.Transparent))))
}
