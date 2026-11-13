package io.github.lhd980820.coup.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CARD_ASPECT
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.ParchmentDim

// ---- 내 영역 ------------------------------------------------------------------------------------

private val HAND_GAP = 10.dp
private val HAND_CARD_WIDTH = 92.dp
private val HAND_CARD_WIDTH_COMPACT = 64.dp

/** 고른 카드가 위로 올라오는 높이. */
private val LIFT = 8.dp
private const val LIFT_MS = 160

/**
 * 내 자리: 이름·코인과 손에 든 카드. 카드는 화면에서 가장 큰 그림이다 — 내가 무엇을 들고 있는지가 모든 판단의 출발이다.
 * [compact]는 화면이 낮을 때(작은 폰): 카드를 줄여 결정 버튼이 잘리지 않게 한다.
 */
@Composable
internal fun MyArea(state: GameUiState, me: MyUi, actions: GameActions, compact: Boolean, onZoom: (CardUi) -> Unit) {
    val pickCard = state.decision as? DecisionUi.PickCard
    val pickExchange = state.decision as? DecisionUi.PickExchange
    val picking = pickCard != null || pickExchange != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(me.name, Gold, size = if (compact) 26.dp else 32.dp, ring = GoldBright, icon = CoupIcons.Person)
            Text(me.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            CoinCount(me.coins, GameTags.MY_COINS, large = true)
            if (!me.isAlive) Text(stringResource(R.string.game_eliminated), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 카드 선택 결정이 있으면 그 카드 목록(선택 상태 포함)이 손패 자리에 그려진다
            val cards = pickCard?.cards ?: pickExchange?.candidates ?: me.hand
            val widest = if (compact) HAND_CARD_WIDTH_COMPACT else HAND_CARD_WIDTH
            val width = if (cards.isEmpty()) widest else minOf(widest, (maxWidth - HAND_GAP * (cards.size - 1)) / cards.size)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HAND_GAP, Alignment.CenterHorizontally)) {
                cards.forEach { card ->
                    // 고르는 중에는 탭이 선택(처리 중에는 잠긴다)이고, 아니면 탭이 확대다. 길게 누르면 언제나 확대한다
                    val onClick: () -> Unit = when {
                        pickCard != null -> ({ if (!state.busy) actions.selectCard(card.id) })
                        pickExchange != null -> ({ if (!state.busy) actions.toggleExchange(card.id) })
                        else -> ({ onZoom(card) })
                    }
                    HandCard(card, width, onClick = onClick, onLongClick = { onZoom(card) }, tapZooms = !picking, picking = picking)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HandCard(card: CardUi, width: Dp, onClick: () -> Unit, onLongClick: () -> Unit, tapZooms: Boolean, picking: Boolean) {
    val name = stringResource(RoleUiCatalog.of(card.role).name)
    val stateText = buildList {
        if (card.revealed) add(stringResource(R.string.game_card_revealed))
        if (card.selected) add(stringResource(R.string.game_card_selected))
        if (card.satisfiesClaim) add(stringResource(R.string.game_card_proves))
    }.joinToString(", ")
    val zoomLabel = stringResource(R.string.game_card_inspect)
    val height = width / CARD_ASPECT
    // 고른 카드는 위로 올라온다(테두리와 "선택됨" 글자도 함께 바뀐다)
    val lift = fadeTo(if (card.selected) 1f else 0f, LIFT_MS)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag(GameTags.card(card.id.value))
            .combinedClickable(onClickLabel = if (tapZooms) zoomLabel else null, onClick = onClick, onLongClickLabel = zoomLabel, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = if (stateText.isEmpty()) name else "$name ($stateText)" },
    ) {
        Box(Modifier.size(width, height + if (picking) LIFT else 0.dp)) {
            RoleCard(
                role = card.role,
                faceUp = true,
                lost = card.revealed,
                selected = card.selected,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -LIFT * lift)
                    .size(width, height)
                    .alpha(fadeTo(if (card.revealed) 0.6f else 1f)),
            )
            if (card.satisfiesClaim) {
                // 도전받은 주장을 증명하는 카드: 금색 표식(글자로도 아래에 나온다)
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = if (picking) LIFT * (1f - lift) - 4.dp else (-4).dp).size(22.dp).clip(CircleShape).background(GoldBright),
                    contentAlignment = Alignment.Center,
                ) { Icon(CoupIcons.Check, contentDescription = null, tint = GoldInk, modifier = Modifier.size(14.dp)) }
            }
        }
        // 상태는 색만이 아니라 글자로도 알린다. 고르는 중에는 고를 때마다 아래가 들썩이지 않게 한 줄을 늘 차지한다
        if (stateText.isNotEmpty() || picking) {
            Text(
                stateText,
                style = MaterialTheme.typography.labelSmall,
                color = if (card.selected) GoldBright else ParchmentDim,
                fontWeight = if (card.selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(width).padding(top = 3.dp),
            )
        }
    }
}

// ---- 카드 확대 ----------------------------------------------------------------------------------

/**
 * 카드를 크게 보여 주는 창: 큰 카드, 이름, 누구의 어떤 카드인지, 이 역할로 할 수 있는 행동·막기(기본 규칙 기준).
 * 내 카드(탭, 또는 고르는 중이면 길게 누르기)와 상대의 공개된 카드(탭)에서 같이 쓴다. 바깥을 누르거나 뒤로 가기로 닫는다.
 * 확대해 보는 동안에도 내 결정의 시간은 흐르므로 [deadline]이 있으면 남은 시간을 함께 보인다.
 */
@Composable
internal fun CardZoom(target: ZoomTarget, rulesChanged: Boolean, deadline: Long?, onDismiss: () -> Unit) {
    val entry = RoleUiCatalog.of(target.role)
    val name = stringResource(entry.name)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = PanelHigh, border = BorderStroke(1.dp, Hairline), modifier = Modifier.testTag(GameTags.CARD_ZOOM)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoleCard(
                    role = target.role,
                    faceUp = true,
                    modifier = Modifier.height(ZOOM_CARD_HEIGHT).aspectRatio(CARD_ASPECT).semantics { contentDescription = name },
                )
                // 이름은 카드에 크게 새겨져 있다(스크린리더에는 카드의 설명으로 읽힌다)
                Text(
                    when (target.kind) {
                        ZoomKind.MINE -> stringResource(R.string.game_zoom_mine)
                        ZoomKind.MINE_LOST -> stringResource(R.string.game_zoom_mine_lost)
                        ZoomKind.OPPONENT_LOST -> stringResource(R.string.game_zoom_opponent_lost, target.owner)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = ParchmentDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(GameTags.CARD_ZOOM_OWNER),
                )
                GoldRule()
                Text(stringResource(entry.ability), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                // 표준 규칙의 판에서는 설명이 그대로 맞다. 규칙이 바뀐 판에서만 달라질 수 있다고 알린다
                if (rulesChanged) Text(stringResource(R.string.game_ability_note), style = MaterialTheme.typography.labelSmall, color = ParchmentDim, textAlign = TextAlign.Center)
                deadline?.let { Countdown(it, GameTags.TIMER_ZOOM) }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(GameTags.CARD_ZOOM_CLOSE)) { Text(stringResource(R.string.game_ability_close)) }
            }
        }
    }
}

private val ZOOM_CARD_HEIGHT = 320.dp
