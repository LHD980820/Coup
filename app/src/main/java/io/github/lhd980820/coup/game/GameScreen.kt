package io.github.lhd980820.coup.game

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.game.sound.SoundCue
import io.github.lhd980820.coup.game.sound.SoundCues
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.ResultUi
import io.github.lhd980820.coup.presentation.UiMessage
import io.github.lhd980820.coup.presentation.tutorial.TutorialExpect
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.TableSurface
import io.github.lhd980820.coup.ui.kit.fadingEdges
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim
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
    const val CARD_ZOOM = "card_zoom"
    const val CARD_ZOOM_CLOSE = "card_zoom_close"
    const val CARD_ZOOM_OWNER = "card_zoom_owner"
    const val TIMER_ZOOM = "timer_zoom"
    fun hidden(id: String, index: Int) = "hidden_${id}_$index"
    const val TIMER_MINE = "timer_mine"
    const val RESPONSE_SHEET = "response_sheet"
    const val MY_COINS = "coins_mine"
    fun coins(id: String) = "coins_$id"
    fun flip(id: String, index: Int) = "flip_${id}_$index"
    fun timer(id: String) = "timer_$id"
}

/** 이 너비 이상이면 테이블 | 기록 두 단으로 배치한다(dp). */
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp

/** 화면이 이보다 낮으면(작은 폰) 내 카드를 줄여 결정 버튼이 잘리지 않게 한다(dp). */
private val COMPACT_HEIGHT = 620.dp
private const val COMPACT_LOG_LINES = 8
private const val WIDE_LOG_LINES = 40

/** 남은 시간을 그릴 때 쓰는 현재 시각(epoch millis). 마감 시각을 만든 시계와 같은 시계(기기 시계)이며, 테스트는 고정 값으로 바꾼다. */
internal val LocalNowMillis = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** 효과음을 내는 함수. 기본은 무음이다 — 미리보기와 자동 테스트는 조용하고, 앱이 설정에 맞춰 채운다. */
internal val LocalSounds = staticCompositionLocalOf<(SoundCue) -> Unit> { {} }

/** 확대해서 보는 카드: 어느 역할이고, 누구의 어떤 카드인가. */
internal data class ZoomTarget(val role: RoleId, val owner: String, val kind: ZoomKind)

internal enum class ZoomKind {
    /** 내 손패(아직 가리고 있는 영향력). */
    MINE,

    /** 내가 잃어서 공개된 카드. */
    MINE_LOST,

    /** 상대가 잃어서 공개된 카드. */
    OPPONENT_LOST,
}

/** 튜토리얼 안내: 지금 단계 문구와, 사용자가 해야 하는 한 수([expect]). null이면 제한 없음. */
internal data class GuideUi(val message: String, val step: Int, val total: Int, val expect: TutorialExpect?, val offScript: Int)

internal fun TutorialExpect?.allowsAction(id: String) = this == null || (this is TutorialExpect.Action && action.value == id)
internal fun TutorialExpect?.allowsPass() = this == null || this is TutorialExpect.Pass
internal fun TutorialExpect?.allowsChallenge() = this == null || this is TutorialExpect.Challenge
internal fun TutorialExpect?.allowsBlock(role: String) = this == null || (this is TutorialExpect.Block && this.role.value == role)

/**
 * 게임 화면(설계 §10.3, 모습은 docs/DESIGN.md). 상태는 [GameUiState] 하나이고 규칙 판단은 하지 않는다 — 버튼의 활성/블러핑 표시는 모두 상태에서 온다.
 * 모든 버튼은 [GameUiState.busy]일 때 잠긴다(중복 탭 방지).
 *
 * 위에서 아래로 테이블에 앉은 모습이다: 표시줄(턴·규칙·기권) -> 건너편의 상대들과 기록(이 부분만 스크롤) ->
 * 가운데 무대(지금 벌어지는 일) -> 내 카드 -> 내가 누를 것. 아래 셋은 항상 보이게 고정한다.
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
    // 결과가 나온 뒤의 뒤로 가기는 아래 결과 대화상자가 받는다(대화상자는 별도 창이라 화면의 BackHandler에 닿지 않는다)

    // 상태가 바뀔 때마다 이전 상태와 비교해 소리를 낸다(처음 그릴 때나 회전으로 다시 그릴 때는 이전이 없어 무음)
    val playSound = LocalSounds.current
    val lastState = remember { arrayOfNulls<GameUiState>(1) }
    LaunchedEffect(state) {
        SoundCues.between(lastState[0], state).forEach(playSound)
        lastState[0] = state
    }
    var zoomed by remember { mutableStateOf<ZoomTarget?>(null) }
    var showRules by remember { mutableStateOf(false) }

    // 대상을 고르는 중이면 고를 수 있는 상대의 자리를 눌러서도 고를 수 있다(아래의 이름 버튼과 같은 동작)
    val onTarget: ((PlayerId) -> Unit)? = if (state.decision is DecisionUi.PickTarget && !state.busy) actions::chooseTarget else null

    TableSurface(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < COMPACT_HEIGHT
            val respond = state.decision as? DecisionUi.Respond

            val hud: @Composable () -> Unit = {
                // 기권은 표시줄에 있다 — 응답 시트가 떠도 항상 닿고, 작은 화면에서 자리를 따로 먹지 않는다
                GameHud(
                    state,
                    onConcede = if (guide == null && state.result == null && !abandoned) ({ confirmConcede = true }) else null,
                    onShowRules = if (state.ruleSet.isCustom) ({ showRules = true }) else null,
                )
            }
            // 아래: 가운데 무대·내 카드·결정은 항상 보이게 고정한다.
            val bottom: @Composable ColumnScope.() -> Unit = {
                guide?.let { CoachCard(it) }
                ConnectionBanner(state.connection, onExit)
                // 응답할 차례에는 아래의 응답 시트가 무대를 대신한다(누가 무엇을 하려는지 + 남은 시간 + 내 선택이 한 판에 모인다)
                if (respond == null) StagePanel(state, texts)
                state.me?.let { MyArea(state, it, actions, compact, onZoom = { card -> zoomed = ZoomTarget(card.role, it.name, if (card.revealed) ZoomKind.MINE_LOST else ZoomKind.MINE) }) }
                DecisionPanel(state, texts, actions, guide?.expect)
                if (guide != null) {
                    TextButton(onClick = onExit, modifier = Modifier.testTag(GameTags.COACH_BACK)) { Text(stringResource(R.string.tutorial_back)) }
                }
                // 응답할 차례가 오면 맨 아래에 응답 시트가 붙는다(표·기록을 가리지 않는 비모달 시트)
                respond?.let { SheetEntrance { ResponseSheet(state, it, texts, actions, guide?.expect) } }
            }

            // 큰 화면(태블릿·폴더블 펼침): 테이블 | 기록 두 단. 좁은 화면은 위(상대·기록)만 스크롤하고 아래는 고정한다.
            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        hud()
                        val scroll = rememberScrollState()
                        Column(Modifier.weight(1f).fadingEdges(scroll).verticalScroll(scroll)) { OpponentSeats(state, texts, onZoom = { zoomed = it }, onTarget = onTarget) }
                        bottom()
                    }
                    Column(
                        Modifier.weight(2f).fillMaxHeight().panel().verticalScroll(rememberScrollState()).padding(12.dp).testTag(GameTags.LOG_SIDE),
                    ) {
                        LogList(state, texts, WIDE_LOG_LINES)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hud()
                    val scroll = rememberScrollState()
                    Column(Modifier.weight(1f).fadingEdges(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OpponentSeats(state, texts, onZoom = { zoomed = it }, onTarget = onTarget)
                        LogList(state, texts, COMPACT_LOG_LINES)
                    }
                    bottom()
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    zoomed?.let { target ->
        // 확대해 보는 동안에도 내 결정의 시간은 흐르므로 남은 시간을 함께 보인다
        val deadline = state.myDeadline.takeIf { state.decision != null && state.result == null }
        CardZoom(target, rulesChanged = state.ruleSet.isCustom, deadline = deadline, onDismiss = { zoomed = null })
    }
    if (showRules) state.ruleSet?.let { RulesDialog(it, onDismiss = { showRules = false }) }

    if (abandoned) {
        AlertDialog(
            // 뒤로 가기 = "로비로". 바깥을 눌러서는 닫히지 않는다
            onDismissRequest = onAbandoned,
            properties = DialogProperties(dismissOnClickOutside = false),
            modifier = Modifier.testTag(GameTags.ABANDONED),
            title = { Text(stringResource(R.string.mp_abandoned_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(abandonedBody))
                    abandonedExtra?.invoke()
                }
            },
            confirmButton = {
                PrimaryButton(stringResource(R.string.mp_abandoned_leave), onAbandoned, Modifier.testTag(GameTags.ABANDONED_LEAVE))
            },
        )
    }

    if (confirmConcede && !abandoned) {
        AlertDialog(
            onDismissRequest = { confirmConcede = false },
            icon = { Icon(CoupIcons.Flag, contentDescription = null, tint = Crimson) },
            text = { Text(stringResource(R.string.game_concede_confirm), style = MaterialTheme.typography.bodyLarge, color = Parchment) },
            confirmButton = {
                TextButton(
                    onClick = { confirmConcede = false; actions.concede() },
                    modifier = Modifier.testTag(GameTags.CONCEDE_CONFIRM),
                ) { Text(stringResource(R.string.game_concede), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmConcede = false }) { Text(stringResource(R.string.game_cancel)) } },
        )
    }

    state.result?.let { result ->
        ResultDialog(result, state, texts, onPlayAgain, onExit, playAgainLabel, resultExtra)
    }
}

// ---- 결과 ---------------------------------------------------------------------------------------

/** 판이 끝났다: 이겼는지 졌는지를 가장 크게, 그 아래 순위표. */
@Composable
private fun ResultDialog(
    result: ResultUi,
    state: GameUiState,
    texts: GameTexts,
    onPlayAgain: () -> Unit,
    onExit: () -> Unit,
    @StringRes playAgainLabel: Int,
    extra: (@Composable () -> Unit)?,
) {
    val won = result.iWon
    AlertDialog(
        // 뒤로 가기 = 결과 확인 뒤 다음 화면으로("다시 하기/대기실로"와 같은 길). 바깥을 눌러서는 닫히지 않는다
        onDismissRequest = onPlayAgain,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(GameTags.RESULT),
        icon = {
            Box(
                Modifier.size(56.dp).panel(
                    CircleShape,
                    top = if (won) GoldBright else PanelTop,
                    bottom = if (won) Gold else PanelHigh,
                    line = if (won) GoldBright else Hairline,
                    lineWidth = 1.5.dp,
                ),
                contentAlignment = Alignment.Center,
            ) { Icon(if (won) CoupIcons.Crown else CoupIcons.Cross, contentDescription = null, tint = if (won) GoldInk else ParchmentDim, modifier = Modifier.size(30.dp)) }
        },
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.game_result_title), style = MaterialTheme.typography.labelLarge, color = ParchmentDim)
                Text(
                    stringResource(if (won) R.string.game_result_win else R.string.game_result_lose),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (won) GoldBright else Parchment,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                    result.myRank?.let { Text(stringResource(R.string.game_result_rank, it), style = MaterialTheme.typography.titleMedium, color = Gold) }
                    Text(stringResource(R.string.game_result_winner, texts.name(result.winner)), style = MaterialTheme.typography.titleMedium, color = Parchment)
                }
                GoldRule(Modifier.padding(vertical = 4.dp))
                result.ranking.forEachIndexed { i, id ->
                    val mine = id == state.me?.id
                    val index = state.opponents.indexOfFirst { it.id == id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (mine) Modifier.clip(MaterialTheme.shapes.small).background(Gold.copy(alpha = 0.12f)) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Avatar(texts.name(id), if (mine) Gold else seatTint(index), size = 24.dp)
                        Text(
                            "${i + 1}. ${texts.name(id)}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == 0) GoldBright else Parchment,
                        )
                    }
                }
                extra?.invoke()
            }
        },
        confirmButton = {
            PrimaryButton(stringResource(playAgainLabel), onPlayAgain, Modifier.testTag(GameTags.RESULT_AGAIN))
        },
        dismissButton = {
            TextButton(onClick = onExit, modifier = Modifier.testTag(GameTags.RESULT_EXIT)) { Text(stringResource(R.string.game_result_exit)) }
        },
    )
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
        colors = CardDefaults.cardColors(containerColor = CrimsonDeep, contentColor = Parchment),
        border = BorderStroke(1.dp, Crimson),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(message), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (connection == ConnectionState.HOST_LOST) {
                TextButton(onClick = onLeave, modifier = Modifier.testTag(GameTags.CONNECTION_LEAVE)) { Text(stringResource(R.string.mp_connection_leave), color = Parchment) }
            }
        }
    }
}

// ---- 튜토리얼 안내 ------------------------------------------------------------------------------

@Composable
private fun CoachCard(guide: GuideUi) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(GameTags.COACH)
            .panel(
                top = MaterialTheme.colorScheme.primaryContainer,
                bottom = MaterialTheme.colorScheme.primaryContainer,
                // 안내와 다른 수를 두면 테두리가 붉어진다(문구도 바뀐다)
                line = if (guide.offScript > 0) MaterialTheme.colorScheme.error else GoldBright.copy(alpha = 0.7f),
                lineWidth = if (guide.offScript > 0) 2.dp else 1.dp,
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CoupIcons.Scroll, contentDescription = null, tint = GoldBright, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.tutorial_coach_step, guide.step, guide.total), style = MaterialTheme.typography.labelSmall, color = GoldBright)
            Text(guide.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
