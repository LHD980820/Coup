package io.github.lhd980820.coup.game

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CoinIcon
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.PlayingCardBack
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.felt
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
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
 * 맨 위 표시줄: 몇 번째 턴인지, 바뀐 규칙(있으면), 기권. 항상 화면 맨 위에 고정돼 있어 응답 시트가 떠도 닿는다.
 */
@Composable
internal fun GameHud(state: GameUiState, onConcede: (() -> Unit)?, onShowRules: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.game_turn, state.turnNumber),
            style = MaterialTheme.typography.labelLarge,
            color = Gold,
            modifier = Modifier.panel(CircleShape, top = PanelTop, bottom = Panel).padding(horizontal = 12.dp, vertical = 5.dp),
        )
        Spacer(Modifier.weight(1f))
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
            TextButton(onClick = onConcede, modifier = Modifier.testTag(GameTags.CONCEDE)) {
                Icon(CoupIcons.Flag, contentDescription = null, modifier = Modifier.size(15.dp), tint = ParchmentDim)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.game_concede), color = ParchmentDim)
            }
        }
    }
}

// ---- 상대 자리 ----------------------------------------------------------------------------------

private val SEAT_GAP = 8.dp

/** 자리 하나의 최소 너비: 카드 두 장의 누르는 영역(48dp씩)이 들어가야 한다. */
private val SEAT_MIN_WIDTH = 104.dp
private val SEAT_MAX_WIDTH = 200.dp
private val SEAT_CARD_WIDTH = 36.dp
private val SEAT_CARD_HEIGHT = 50.dp

/**
 * 테이블 건너편의 상대들. 가로로 밀어 보지 않아도 모두 한눈에 보이게 줄을 나눈다(1~3명은 한 줄, 4명은 2x2, 5명은 3+2).
 * 화면이 좁아 한 줄에 다 못 들어가면 줄 수를 늘린다. 대상을 고르는 중이면 고를 수 있는 자리를 눌러서 고를 수 있다([onTarget]).
 */
@Composable
internal fun OpponentSeats(state: GameUiState, texts: GameTexts, onZoom: (ZoomTarget) -> Unit, onTarget: ((PlayerId) -> Unit)?) {
    val opponents = state.opponents
    if (opponents.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wanted = when (opponents.size) {
            1 -> 1
            2, 4 -> 2
            else -> 3
        }
        val fits = ((maxWidth + SEAT_GAP) / (SEAT_MIN_WIDTH + SEAT_GAP)).toInt().coerceAtLeast(1)
        val columns = minOf(wanted, fits)
        val seatWidth = ((maxWidth - SEAT_GAP * (columns - 1)) / columns).coerceAtMost(SEAT_MAX_WIDTH)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SEAT_GAP)) {
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
private fun Seat(op: OpponentUi, index: Int, texts: GameTexts, onZoom: (RoleId) -> Unit, onTarget: (() -> Unit)?, modifier: Modifier) {
    val busy = op.isAlive && (op.status == OpponentStatus.ACTING || op.status == OpponentStatus.THINKING)
    // 지금 움직이는 사람과 고를 수 있는 대상은 테두리가 밝아지며 천천히 맥박친다(테두리 굵기와 상태 글자도 함께 달라진다)
    val glow = if (busy || op.targetable) pulse().value else 1f
    val line = when {
        op.targetable -> GoldBright.copy(alpha = 0.5f + 0.5f * glow)
        busy -> Gold.copy(alpha = 0.4f + 0.6f * glow)
        else -> Hairline
    }
    val targetLabel = stringResource(R.string.game_target_tap)
    Column(
        modifier
            .alpha(fadeTo(if (op.isAlive) 1f else 0.55f))
            .testTag(GameTags.opponent(op.id.value))
            .panel(line = line, lineWidth = if (busy || op.targetable) 2.dp else 1.dp)
            .then(if (onTarget != null) Modifier.clickable(onClickLabel = targetLabel, onClick = onTarget) else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Avatar(op.name, seatTint(index), size = 30.dp, ring = if (busy) Gold else GoldDeep.copy(alpha = 0.6f))
            Column(Modifier.weight(1f, fill = false)) {
                Text(op.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CoinCount(op.coins, GameTags.coins(op.id.value))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 화면을 처음 그릴 때 이미 공개돼 있던 카드는 그대로 두고, 그 뒤에 공개되는 카드만 뒤집는다
            val settled = remember { op.revealed.size }
            val zoomLabel = stringResource(R.string.game_card_inspect)
            op.revealed.forEachIndexed { i, role ->
                // 공개된 카드는 탭하면 확대된다. 그림은 작지만 누르는 영역은 48dp를 채운다
                // 태그는 누르는 영역에 둔다: 안쪽 카드의 접근성 이름·뒤집힘 진행이 이 노드로 합쳐진다
                Box(
                    Modifier.size(MIN_TOUCH, SEAT_CARD_HEIGHT).testTag(GameTags.flip(op.id.value, i)).clickable(onClickLabel = zoomLabel) { onZoom(role) },
                    contentAlignment = Alignment.Center,
                ) {
                    FlipCard(role, animate = i >= settled, modifier = Modifier.size(SEAT_CARD_WIDTH, SEAT_CARD_HEIGHT))
                }
            }
            // 가려진 카드는 무엇인지 모르므로 확대할 것이 없다
            repeat(op.hiddenCount) { i ->
                Box(Modifier.size(MIN_TOUCH, SEAT_CARD_HEIGHT), contentAlignment = Alignment.Center) {
                    RoleCard(role = null, faceUp = false, modifier = Modifier.size(SEAT_CARD_WIDTH, SEAT_CARD_HEIGHT).testTag(GameTags.hidden(op.id.value, i)))
                }
            }
        }
        // 상태 줄: 탈락 > 주장 중인 역할 > 행동 중/생각 중/응답함. 남은 시간은 옆에 붙는다. 자리가 들썩이지 않게 한 줄 높이는 늘 차지한다
        FlowRow(
            Modifier.heightIn(min = 18.dp).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val small = MaterialTheme.typography.labelSmall
            when {
                !op.isAlive -> Text(stringResource(R.string.game_eliminated), style = small, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                op.claiming.isNotEmpty() -> Text(
                    stringResource(R.string.game_claiming, texts.roles(op.claiming)),
                    style = small,
                    fontWeight = FontWeight.Bold,
                    color = RoleVisuals.of(op.claiming.first().value).accent,
                )
                op.status == OpponentStatus.ACTING -> Text(stringResource(R.string.game_status_acting), style = small, color = Gold)
                op.status == OpponentStatus.THINKING -> Text(stringResource(R.string.game_status_thinking), style = small, color = Gold)
                op.status == OpponentStatus.RESPONDED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(CoupIcons.Check, contentDescription = null, tint = ParchmentDim, modifier = Modifier.size(11.dp))
                    Text(stringResource(R.string.game_status_responded), style = small, color = ParchmentDim)
                }
                else -> Unit
            }
            val deadline = op.deadline
            if (deadline != null && busy) Countdown(deadline, GameTags.timer(op.id.value))
        }
    }
}

// ---- 가운데 무대 --------------------------------------------------------------------------------

/**
 * 테이블 한가운데: 지금 무슨 일이 벌어지고 있는가. 왼쪽 문장(紋章)이 상황을 한눈에 알리고(내 차례 = 별, 도전 = 칼, 막기 = 방패, 역할 주장 = 그 역할),
 * 가운데에 문장으로 풀어 쓰며, 오른쪽에 덱이 놓인다. 내가 결정할 차례면 남은 시간이 막대로 붙는다(응답할 때는 응답 시트가 보인다).
 */
@Composable
internal fun StagePanel(state: GameUiState, texts: GameTexts) {
    val banner = state.banner
    Row(
        Modifier.fillMaxWidth().testTag(GameTags.BANNER).felt().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StageMedallion(banner)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(texts.banner(banner), style = MaterialTheme.typography.titleMedium, color = Parchment)
            val waiting = when (banner) {
                is Banner.ActionDeclared -> banner.waitingOn
                is Banner.BlockDeclared -> banner.waitingOn
                is Banner.BlockWindow -> banner.waitingOn
                else -> emptySet()
            }
            if (waiting.isNotEmpty()) {
                Text(
                    stringResource(R.string.game_waiting_on, waiting.joinToString(", ") { texts.name(it) }),
                    style = MaterialTheme.typography.labelMedium,
                    color = ParchmentDim,
                )
            }
            // 내가 결정할 차례일 때만 내 남은 시간을 보인다(끝난 판·기다리는 중에는 숨김)
            val deadline = state.myDeadline
            if (deadline != null && state.decision != null && state.decision !is DecisionUi.Respond && state.result == null) {
                Countdown(deadline, GameTags.TIMER_MINE, Modifier.fillMaxWidth(), style = TimerStyle.BAR)
            }
        }
        DeckPile(state.deckSize)
    }
}

private class Medal(val icon: ImageVector?, val top: Color, val bottom: Color, val tint: Color, val rim: Color = Gold)

@Composable
private fun StageMedallion(banner: Banner) {
    fun role(role: RoleId?, icon: ImageVector? = null): Medal {
        val visual = role?.let { RoleVisuals.of(it.value) } ?: return Medal(icon, PanelTop, Panel, Gold)
        return Medal(icon ?: visual.emblem, visual.top, visual.bottom, GoldBright, visual.accent)
    }
    val medal = when (banner) {
        is Banner.YourTurn -> Medal(CoupIcons.Star, GoldBright, Gold, GoldInk, GoldBright)
        is Banner.WaitingFor -> Medal(CoupIcons.Hourglass, PanelTop, Panel, ParchmentDim, Hairline)
        is Banner.ActionDeclared -> when {
            banner.claimedRoles.isNotEmpty() -> role(banner.claimedRoles.first())
            banner.actionId.value == "coup" -> Medal(CoupIcons.Bolt, Crimson, CrimsonDeep, Parchment, Crimson)
            // 수입·해외 원조처럼 역할을 주장하지 않는 행동은 코인으로 그린다
            else -> Medal(null, PanelTop, Panel, Gold)
        }
        is Banner.BlockDeclared -> role(banner.role, CoupIcons.Shield)
        is Banner.BlockWindow -> Medal(CoupIcons.Shield, PanelTop, Panel, Gold)
        is Banner.Challenged -> Medal(CoupIcons.Swords, Crimson, CrimsonDeep, Parchment, Crimson)
        is Banner.LosingInfluence -> Medal(CoupIcons.Cross, CrimsonDeep, Color(0xFF2A0A0E), Parchment, Crimson)
        is Banner.Exchanging -> role(RoleId("ambassador"))
        is Banner.GameOver -> Medal(CoupIcons.Crown, GoldBright, Gold, GoldInk, GoldBright)
    }
    // 내 차례에는 문장이 천천히 맥박친다
    val beat = if (banner is Banner.YourTurn) pulse().value else 1f
    Box(
        Modifier
            .size(42.dp)
            .alpha(0.75f + 0.25f * beat)
            .panel(CircleShape, top = medal.top, bottom = medal.bottom, line = medal.rim, lineWidth = 1.5.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (medal.icon != null) Icon(medal.icon, contentDescription = null, tint = medal.tint, modifier = Modifier.size(22.dp)) else CoinIcon(size = 22.dp)
    }
}

/** 덱: 겹쳐 놓인 카드 뒷면과 남은 장수. */
@Composable
private fun DeckPile(deckSize: Int) {
    val label = stringResource(R.string.game_deck, deckSize)
    Column(Modifier.clearAndSetSemantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(width = 32.dp, height = 40.dp)) {
            val layers = minOf(deckSize, 3)
            repeat(layers) { i ->
                PlayingCardBack(Modifier.align(Alignment.BottomStart).offset(x = (i * 3).dp, y = (-i * 2).dp).size(width = 26.dp, height = 36.dp))
            }
            if (layers == 0) {
                Box(Modifier.align(Alignment.BottomStart).size(width = 26.dp, height = 36.dp).panel(androidx.compose.foundation.shape.RoundedCornerShape(3.dp), top = Color.Transparent, bottom = Color.Transparent))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = ParchmentDim)
    }
}

// ---- 기록 ---------------------------------------------------------------------------------------

/** 진행 기록. 가장 최근 일이 맨 위에 크게 나오고 오래된 일일수록 아래로 내려간다 — 아래가 잘려도 방금 일어난 일은 항상 보인다. */
@Composable
internal fun LogList(state: GameUiState, texts: GameTexts, maxLines: Int) {
    val lines = state.log.mapNotNull { texts.log(it) }.takeLast(maxLines).asReversed()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.game_log_title), style = MaterialTheme.typography.labelMedium, color = Gold)
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

/** 판 위의 얇은 금색 구분선. */
@Composable
internal fun GoldRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Gold.copy(alpha = 0.45f), Color.Transparent))))
}
