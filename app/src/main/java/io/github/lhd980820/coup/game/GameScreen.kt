package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.lhd980820.coup.engine.model.RoleId
import kotlinx.coroutines.delay
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
    const val LOG_SIDE = "log_side"
    const val ABILITY_SHEET = "ability_sheet"
    const val ABILITY_CLOSE = "ability_close"
    const val TIMER_MINE = "timer_mine"
    fun timer(id: String) = "timer_$id"
}

/** 이 너비 이상이면 테이블 | 기록 두 단으로 배치한다(dp). */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private const val COMPACT_LOG_LINES = 8
private const val WIDE_LOG_LINES = 40

/** 남은 시간을 그릴 때 쓰는 현재 시각(epoch millis). 마감 시각을 만든 시계와 같은 시계(기기 시계)이며, 테스트는 고정 값으로 바꾼다. */
internal val LocalNowMillis = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

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

    var inspected by remember { mutableStateOf<RoleId?>(null) }

    // 아래: 진행 배너·내 카드·결정은 항상 보이게 고정한다.
    val bottom: @Composable ColumnScope.() -> Unit = {
        guide?.let { CoachCard(it) }
        ConnectionBanner(state.connection, onExit)
        BannerCard(state, texts)
        state.me?.let { MyArea(state, it, actions, onInspect = { role -> inspected = role }) }
        DecisionPanel(state, texts, actions, guide?.expect)
        if (guide != null) {
            TextButton(onClick = onExit, modifier = Modifier.testTag(GameTags.COACH_BACK)) { Text(stringResource(R.string.tutorial_back)) }
        } else if (state.result == null && !abandoned) {
            TextButton(onClick = { confirmConcede = true }, modifier = Modifier.testTag(GameTags.CONCEDE)) {
                Text(stringResource(R.string.game_concede))
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 큰 화면(태블릿·폴더블 펼침): 테이블 | 기록 두 단. 좁은 화면은 위(상대·기록)만 스크롤하고 아래는 고정한다.
            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { OpponentsRow(state, texts) }
                        bottom()
                    }
                    Column(Modifier.weight(2f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag(GameTags.LOG_SIDE)) {
                        LogList(state, texts, WIDE_LOG_LINES)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OpponentsRow(state, texts)
                        LogList(state, texts, COMPACT_LOG_LINES)
                    }
                    bottom()
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    inspected?.let { role -> AbilitySheet(role, onDismiss = { inspected = null }) }

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
            val deadline = op.deadline
            if (deadline != null && op.isAlive && (op.status == OpponentStatus.ACTING || op.status == OpponentStatus.THINKING)) {
                Countdown(deadline, GameTags.timer(op.id.value))
            }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.game_deck, state.deckSize), style = MaterialTheme.typography.labelSmall)
                // 내가 결정할 차례일 때만 내 남은 시간을 보인다(끝난 판·기다리는 중에는 숨김)
                val deadline = state.myDeadline
                if (deadline != null && state.decision != null && state.result == null) Countdown(deadline, GameTags.TIMER_MINE)
            }
        }
    }
}

@Composable
private fun LogList(state: GameUiState, texts: GameTexts, maxLines: Int) {
    val lines = state.log.mapNotNull { texts.log(it) }.takeLast(maxLines)
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.game_log_title), style = MaterialTheme.typography.labelMedium)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- 내 영역 ------------------------------------------------------------------------------------

@Composable
private fun MyArea(state: GameUiState, me: io.github.lhd980820.coup.presentation.MyUi, actions: GameActions, onInspect: (RoleId) -> Unit) {
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
                    else -> ({ onInspect(card.role) }) // 고르는 중이 아니면 탭해서 능력을 본다
                }
                HandCard(card, onClick, inspect = pickCard == null && pickExchange == null)
            }
        }
    }
}

@Composable
private fun HandCard(card: CardUi, onClick: (() -> Unit)?, inspect: Boolean) {
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
            .let {
                if (onClick == null) it
                else if (inspect) it.clickable(onClickLabel = stringResource(R.string.game_card_inspect), onClick = onClick)
                else it.clickable(onClick = onClick)
            }
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

// ---- 남은 시간 ----------------------------------------------------------------------------------

/**
 * 마감까지 남은 시간: 줄어드는 링 + 초. 링의 전체 길이는 이 마감을 처음 본 순간의 남은 시간이다.
 * 5초 이하면 링이 경고색이 되고 글씨가 굵어진다(숫자는 항상 글자로도 보인다). 스크린리더에는 "남은 시간 N초"로 읽히되 매초 알리지는 않는다.
 */
@Composable
private fun Countdown(deadline: Long, tag: String, modifier: Modifier = Modifier) {
    val now = LocalNowMillis.current
    val remaining by produceState(initialValue = millisLeft(deadline, now), deadline, now) {
        while (true) {
            value = millisLeft(deadline, now)
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }
    val total = remember(deadline) { remaining.coerceAtLeast(1) }
    val seconds = ((remaining + 999) / 1000).toInt()
    val urgent = seconds <= URGENT_SECONDS
    val label = stringResource(R.string.game_timer_label, seconds)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CircularProgressIndicator(
            progress = { (remaining.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.size(18.dp),
            strokeWidth = 3.dp,
            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.game_timer_seconds, seconds),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (urgent) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun millisLeft(deadline: Long, now: () -> Long) = (deadline - now()).coerceAtLeast(0)

private const val COUNTDOWN_TICK_MILLIS = 250L
private const val URGENT_SECONDS = 5

// ---- 역할 능력 시트 -----------------------------------------------------------------------------

/** 내 카드를 탭하면 올라오는 시트: 카드 그림, 이름, 이 역할로 할 수 있는 행동·막기(기본 규칙 기준). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbilitySheet(role: RoleId, onDismiss: () -> Unit) {
    val entry = RoleUiCatalog.of(role)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(GameTags.ABILITY_SHEET)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CardImage(role = role, revealed = true, modifier = Modifier.size(width = 62.dp, height = 88.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(entry.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(entry.ability), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.game_ability_note), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(GameTags.ABILITY_CLOSE)) { Text(stringResource(R.string.game_ability_close)) }
            }
        }
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
