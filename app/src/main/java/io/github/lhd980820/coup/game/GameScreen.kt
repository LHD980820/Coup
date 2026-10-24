package io.github.lhd980820.coup.game

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import io.github.lhd980820.coup.runtime.session.ConnectionState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.CardPurpose
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.DisabledReason
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.UiMessage
import io.github.lhd980820.coup.presentation.tutorial.TutorialExpect
import kotlinx.coroutines.flow.Flow

/** 자동화 테스트가 노드를 찾는 태그. 화면 문구(번역)에 의존하지 않기 위해 따로 둔다. */
internal object GameTags {
    fun action(id: String) = "action_$id"
    fun target(id: String) = "target_$id"
    fun opponent(id: String) = "opponent_$id"
    fun block(role: String) = "block_$role"
    fun card(id: Int) = "card_$id"
    const val PASS = "pass"
    const val CHALLENGE = "challenge"
    const val CONFIRM = "confirm"
    const val CANCEL_TARGET = "cancel_target"
    const val BANNER = "banner"
    const val RESULT = "result"
    const val RESULT_EXIT = "result_exit"
    const val RESULT_AGAIN = "result_again"
    const val CONNECTION = "game_connection"
    const val CONNECTION_LEAVE = "game_connection_leave"
    const val ABANDONED = "game_abandoned"
    const val ABANDONED_LEAVE = "game_abandoned_leave"
    const val CONCEDE = "concede"
    const val CONCEDE_CONFIRM = "concede_confirm"
    const val WAITING = "waiting"
    const val COACH = "coach"
    const val COACH_BACK = "coach_back"
}

/** 튜토리얼 안내: 지금 단계 문구와, 사용자가 해야 하는 한 수([expect]). null이면 제한 없음. */
internal data class GuideUi(val message: String, val step: Int, val total: Int, val expect: TutorialExpect?, val offScript: Int)

private fun TutorialExpect?.allowsAction(id: String) = this == null || (this is TutorialExpect.Action && action.value == id)
private fun TutorialExpect?.allowsPass() = this == null || this is TutorialExpect.Pass
private fun TutorialExpect?.allowsChallenge() = this == null || this is TutorialExpect.Challenge
private fun TutorialExpect?.allowsBlock(role: String) = this == null || (this is TutorialExpect.Block && this.role.value == role)

/** 안내가 가리키는 컨트롤은 테두리로 강조한다(색만으로 전달하지 않도록 코치 문구도 함께 있다). */
@Composable
private fun Modifier.guideRing(on: Boolean): Modifier =
    if (on) this.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else this

/**
 * 게임 화면(설계 §10.3). 상태는 [GameUiState] 하나이고 규칙 판단은 하지 않는다 — 버튼의 활성/블러핑 표시는 모두 상태에서 온다.
 * 모든 버튼은 [GameUiState.busy]일 때 잠긴다(중복 탭 방지).
 */
@Composable
internal fun GameScreen(
    state: GameUiState,
    messages: Flow<UiMessage>,
    actions: GameActions,
    onExit: () -> Unit,
    onPlayAgain: () -> Unit,
    modifier: Modifier = Modifier,
    guide: GuideUi? = null,
    /** 결과 대화상자 맨 아래에 덧붙일 내용(멀티플레이의 레이팅 변화). */
    resultExtra: (@Composable () -> Unit)? = null,
    @StringRes playAgainLabel: Int = R.string.game_result_again,
    /** 뒤로 가기를 기권 확인으로 받는다(멀티플레이: 화면을 그냥 닫으면 내 차례가 시간 초과로 흘러간다). 결과가 나온 뒤에는 [onExit]. */
    backConcedes: Boolean = false,
    /** 서버가 버려진 판으로 끝낸 게임에서 나간다(ADR 0016). 멀티플레이는 로비로 — 그 방은 곧 닫힌다. */
    onAbandoned: () -> Unit = onExit,
    /** 버려진 판 안내 문구. 오래 끊겼던 방장 본인과 기다리던 게스트가 다르다. */
    @StringRes abandonedBody: Int = R.string.mp_abandoned_body,
    /** 버려진 판 안내 아래에 덧붙일 내용(방장이 패배로 반영된 레이팅). */
    abandonedExtra: (@Composable () -> Unit)? = null,
) {
    val texts = GameTexts(LocalResources.current, state)
    val snackbar = remember { SnackbarHostState() }
    val rejected = stringResource(R.string.game_message_rejected)
    val network = stringResource(R.string.game_message_network)
    LaunchedEffect(messages) {
        messages.collect { m ->
            snackbar.showSnackbar(if (m is UiMessage.Rejected) rejected else network)
        }
    }
    var confirmConcede by remember { mutableStateOf(false) }
    // 방장이 오래 돌아오지 않아 서버가 판을 끝냈다: 더 둘 수 없으므로 기권 대신 안내하고 나가게 한다
    val abandoned = state.connection == ConnectionState.ABANDONED && state.result == null
    BackHandler(enabled = backConcedes && state.result == null) { if (abandoned) onAbandoned() else confirmConcede = true }
    BackHandler(enabled = backConcedes && state.result != null) { onExit() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            // 위: 상대와 기록(좁은 화면에서는 스크롤). 아래: 진행 배너·내 카드·결정은 항상 보이게 고정한다.
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpponentsRow(state, texts)
                    LogList(state, texts)
                }
                guide?.let { CoachCard(it) }
                ConnectionBanner(state.connection, onExit)
                BannerCard(state, texts)
                state.me?.let { MyArea(state, it, actions) }
                DecisionPanel(state, texts, actions, guide?.expect)
                if (guide != null) {
                    TextButton(onClick = onExit, modifier = Modifier.testTag(GameTags.COACH_BACK)) { Text(stringResource(R.string.tutorial_back)) }
                } else if (state.result == null && !abandoned) {
                    TextButton(onClick = { confirmConcede = true }, modifier = Modifier.testTag(GameTags.CONCEDE)) {
                        Text(stringResource(R.string.game_concede))
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (abandoned) {
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag(GameTags.ABANDONED),
            title = { Text(stringResource(R.string.mp_abandoned_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(abandonedBody))
                    abandonedExtra?.invoke()
                }
            },
            confirmButton = {
                Button(onClick = onAbandoned, modifier = Modifier.testTag(GameTags.ABANDONED_LEAVE)) { Text(stringResource(R.string.mp_abandoned_leave)) }
            },
        )
    }

    if (confirmConcede && !abandoned) {
        AlertDialog(
            onDismissRequest = { confirmConcede = false },
            text = { Text(stringResource(R.string.game_concede_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmConcede = false; actions.concede() },
                    modifier = Modifier.testTag(GameTags.CONCEDE_CONFIRM),
                ) { Text(stringResource(R.string.game_concede)) }
            },
            dismissButton = { TextButton(onClick = { confirmConcede = false }) { Text(stringResource(R.string.game_cancel)) } },
        )
    }

    state.result?.let { result ->
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag(GameTags.RESULT),
            title = { Text(stringResource(R.string.game_result_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(if (result.iWon) R.string.game_result_win else R.string.game_result_lose),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    result.myRank?.let { Text(stringResource(R.string.game_result_rank, it)) }
                    Text(stringResource(R.string.game_result_winner, texts.name(result.winner)))
                    result.ranking.forEachIndexed { i, id -> Text("${i + 1}. ${texts.name(id)}") }
                    resultExtra?.invoke()
                }
            },
            confirmButton = {
                Button(onClick = onPlayAgain, modifier = Modifier.testTag(GameTags.RESULT_AGAIN)) { Text(stringResource(playAgainLabel)) }
            },
            dismissButton = {
                TextButton(onClick = onExit, modifier = Modifier.testTag(GameTags.RESULT_EXIT)) { Text(stringResource(R.string.game_result_exit)) }
            },
        )
    }
}

// ---- 연결 상태 ----------------------------------------------------------------------------------

/** 멀티플레이 연결 상태. 다시 연결 중이면 알리고, 방장과 끊겼으면 대기실로 나갈 수 있게 한다(방장이 돌아오면 "게임으로 돌아가기"로 다시 들어온다). */
@Composable
private fun ConnectionBanner(connection: ConnectionState, onLeave: () -> Unit) {
    val message = when (connection) {
        // 방장 자리가 옮겨 가는 중(ADR 0017)도 잠깐 다시 연결하는 것으로 보인다
        ConnectionState.RECONNECTING, ConnectionState.HOST_MOVED -> R.string.mp_connection_reconnecting
        ConnectionState.HOST_LOST -> R.string.mp_connection_host_lost
        // 버려진 판은 배너 대신 안내 대화상자로 알린다
        ConnectionState.CONNECTED, ConnectionState.CLOSED, ConnectionState.ABANDONED -> return
    }
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(GameTags.CONNECTION),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(message), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (connection == ConnectionState.HOST_LOST) {
                TextButton(onClick = onLeave, modifier = Modifier.testTag(GameTags.CONNECTION_LEAVE)) { Text(stringResource(R.string.mp_connection_leave)) }
            }
        }
    }
}

// ---- 상대 ---------------------------------------------------------------------------------------

@Composable
private fun OpponentsRow(state: GameUiState, texts: GameTexts) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(state.opponents, key = { it.id.value }) { op -> OpponentCard(op, texts, highlight = op.targetable) }
    }
}

@Composable
private fun OpponentCard(op: OpponentUi, texts: GameTexts, highlight: Boolean) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .alpha(if (op.isAlive) 1f else 0.5f)
            .testTag(GameTags.opponent(op.id.value)),
        border = if (highlight) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(op.name, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.game_coins, op.coins), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                op.revealed.forEach { role -> CardImage(role = role, revealed = true, modifier = Modifier.size(width = 34.dp, height = 48.dp)) }
                repeat(op.hiddenCount) { CardImage(role = null, revealed = false, modifier = Modifier.size(width = 34.dp, height = 48.dp)) }
            }
            val status = when {
                !op.isAlive -> stringResource(R.string.game_eliminated)
                op.status == OpponentStatus.ACTING -> stringResource(R.string.game_status_acting)
                op.status == OpponentStatus.THINKING -> stringResource(R.string.game_status_thinking)
                op.status == OpponentStatus.RESPONDED -> stringResource(R.string.game_status_responded)
                else -> ""
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall)
            if (op.claiming.isNotEmpty()) {
                Text(stringResource(R.string.game_claiming, texts.roles(op.claiming)), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CoachCard(guide: GuideUi) {
    Card(
        Modifier.fillMaxWidth().testTag(GameTags.COACH),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        border = if (guide.offScript > 0) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.tutorial_coach_step, guide.step, guide.total), style = MaterialTheme.typography.labelSmall)
            Text(guide.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- 배너/로그 ----------------------------------------------------------------------------------

@Composable
private fun BannerCard(state: GameUiState, texts: GameTexts) {
    Card(Modifier.fillMaxWidth().testTag(GameTags.BANNER), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(texts.banner(state.banner), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.game_deck, state.deckSize), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LogList(state: GameUiState, texts: GameTexts) {
    val lines = state.log.mapNotNull { texts.log(it) }.takeLast(8)
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.game_log_title), style = MaterialTheme.typography.labelMedium)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- 내 영역 ------------------------------------------------------------------------------------

@Composable
private fun MyArea(state: GameUiState, me: io.github.lhd980820.coup.presentation.MyUi, actions: GameActions) {
    val pickCard = state.decision as? DecisionUi.PickCard
    val pickExchange = state.decision as? DecisionUi.PickExchange
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(me.name, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.game_coins, me.coins))
            if (!me.isAlive) Text(stringResource(R.string.game_eliminated))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 카드 선택 결정이 있으면 그 카드 목록(선택 상태 포함)이 손패 자리에 그려진다
            val cards = pickCard?.cards ?: pickExchange?.candidates ?: me.hand
            cards.forEach { card ->
                val onClick: (() -> Unit)? = when {
                    state.busy -> null
                    pickCard != null -> ({ actions.selectCard(card.id) })
                    pickExchange != null -> ({ actions.toggleExchange(card.id) })
                    else -> null
                }
                HandCard(card, onClick)
            }
        }
    }
}

@Composable
private fun HandCard(card: CardUi, onClick: (() -> Unit)?) {
    val name = stringResource(RoleUiCatalog.of(card.role).name)
    val stateText = buildList {
        if (card.revealed) add(stringResource(R.string.game_card_revealed))
        if (card.selected) add(stringResource(R.string.game_card_selected))
        if (card.satisfiesClaim) add(stringResource(R.string.game_card_proves))
    }.joinToString(", ")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag(GameTags.card(card.id.value))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .semantics(mergeDescendants = true) { contentDescription = if (stateText.isEmpty()) name else "$name ($stateText)" },
    ) {
        CardImage(
            role = card.role,
            revealed = true,
            modifier = Modifier
                .size(width = 62.dp, height = 88.dp)
                .alpha(if (card.revealed) 0.45f else 1f),
            border = if (card.selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        )
        Text(name, style = MaterialTheme.typography.labelMedium)
        if (stateText.isNotEmpty()) Text(stateText, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CardImage(role: io.github.lhd980820.coup.engine.model.RoleId?, revealed: Boolean, modifier: Modifier, border: BorderStroke? = null) {
    val res = if (role != null && revealed) RoleUiCatalog.of(role).art else RoleUiCatalog.cardBack
    Surface(shape = RoundedCornerShape(6.dp), border = border, modifier = modifier) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

// ---- 결정 ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecisionPanel(state: GameUiState, texts: GameTexts, actions: GameActions, expect: TutorialExpect? = null) {
    val decision = state.decision
    val enabled = !state.busy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (decision) {
            null -> if (state.result == null) {
                Text(stringResource(R.string.game_waiting_decision), modifier = Modifier.testTag(GameTags.WAITING))
            }
            is DecisionUi.Actions -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                decision.buttons.forEach { b ->
                    val allowed = expect.allowsAction(b.actionId.value)
                    ActionButton(b, texts, enabled && allowed, ring = expect != null && allowed) { actions.chooseAction(b.actionId) }
                }
            }
            is DecisionUi.PickTarget -> {
                Text(stringResource(R.string.game_pick_target))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    decision.targets.forEach { id ->
                        Button(onClick = { actions.chooseTarget(id) }, enabled = enabled, modifier = Modifier.testTag(GameTags.target(id.value))) {
                            Text(texts.name(id))
                        }
                    }
                    OutlinedButton(onClick = actions::cancelTarget, modifier = Modifier.testTag(GameTags.CANCEL_TARGET)) {
                        Text(stringResource(R.string.game_cancel))
                    }
                }
            }
            is DecisionUi.Respond -> {
                Text(texts.respondPrompt(decision.actor, decision.actionId, decision.windowKind))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions::pass,
                        enabled = enabled && expect.allowsPass(),
                        modifier = Modifier.guideRing(expect != null && expect.allowsPass()).testTag(GameTags.PASS),
                    ) {
                        Text(stringResource(R.string.game_pass))
                    }
                    if (decision.canChallenge) {
                        Button(
                            onClick = actions::challenge,
                            enabled = enabled && expect.allowsChallenge(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.guideRing(expect != null && expect.allowsChallenge()).testTag(GameTags.CHALLENGE),
                        ) { Text(stringResource(R.string.game_challenge)) }
                    }
                    decision.blocks.forEach { b ->
                        val allowed = expect.allowsBlock(b.role.value)
                        OutlinedButton(
                            onClick = { actions.block(b.role) },
                            enabled = enabled && allowed,
                            modifier = Modifier.guideRing(expect != null && allowed).testTag(GameTags.block(b.role.value)),
                        ) {
                            Text(stringResource(R.string.game_block_with, texts.role(b.role)) + if (b.bluff) " · " + stringResource(R.string.game_bluff) else "")
                        }
                    }
                }
            }
            is DecisionUi.PickCard -> {
                Text(
                    if (decision.purpose == CardPurpose.REVEAL) stringResource(R.string.game_pick_reveal, texts.roles(decision.claimedRoles))
                    else stringResource(R.string.game_pick_lose),
                )
                Button(onClick = actions::confirmCard, enabled = enabled && decision.selected != null, modifier = Modifier.testTag(GameTags.CONFIRM)) {
                    Text(stringResource(R.string.game_confirm))
                }
            }
            is DecisionUi.PickExchange -> {
                Text(stringResource(R.string.game_pick_exchange, decision.keepCount))
                Button(onClick = actions::confirmExchange, enabled = enabled && decision.canConfirm, modifier = Modifier.testTag(GameTags.CONFIRM)) {
                    Text(stringResource(R.string.game_confirm))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ActionButton(button: ActionButtonUi, texts: GameTexts, enabled: Boolean, ring: Boolean = false, onClick: () -> Unit) {
    val name = texts.actionName(button.actionId)
    val label = if (button.cost > 0) stringResource(R.string.game_action_cost, name, button.cost) else name
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled && button.enabled,
            modifier = Modifier.guideRing(ring && enabled && button.enabled).testTag(GameTags.action(button.actionId.value)),
        ) { Text(label) }
        // 색만으로 의미를 전달하지 않는다: 블러핑/필수/비활성 사유는 글자로도 표시
        val note = when {
            button.disabledReason == DisabledReason.INSUFFICIENT_COINS -> stringResource(R.string.game_reason_no_coins)
            button.disabledReason == DisabledReason.NO_VALID_TARGET -> stringResource(R.string.game_reason_no_target)
            button.forced -> stringResource(R.string.game_forced)
            button.bluff -> stringResource(R.string.game_bluff)
            else -> ""
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall)
    }
}
