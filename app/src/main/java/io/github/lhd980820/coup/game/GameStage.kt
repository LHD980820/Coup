package io.github.lhd980820.coup.game

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.CARD_ASPECT
import io.github.lhd980820.coup.ui.kit.CoinIcon
import io.github.lhd980820.coup.ui.kit.CoinPile
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.felt
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim

// 테이블 한가운데의 "판"(docs/DESIGN.md "가운데 판"): 지금 벌어지는 일을 글이 아니라 그림 한 줄로 보인다.

/**
 * 판에 놓이는 한 장면: [actor]가 [card](주장한 역할 카드) 또는 [token](역할 없는 행동의 문양)을 내밀고 [verb](한두 낱말)를 한다.
 * [target]이 있으면 화살표 너머에 그 사람이 서고, [targetCard]는 그 사람의 도전받는 주장이다(물음표가 붙는다).
 * [gain]은 표준 규칙에서 행동한 사람이 얻는 코인(세금 +3 등). [waiting]은 아직 응답하지 않은 사람들.
 */
@Immutable
internal data class StageScene(
    val actor: PlayerId?,
    val card: RoleId?,
    val token: Token?,
    val verb: String,
    val accent: Color,
    val target: PlayerId? = null,
    val targetCard: RoleId? = null,
    val gain: Int? = null,
    val waiting: Set<PlayerId> = emptySet(),
    val big: Boolean = false,
)

/** 역할 카드 대신 놓이는 문양: 코인 한 닢, 코인 더미, 그 밖의 그림. */
internal sealed interface Token {
    data object Coin : Token
    data object Coins : Token
    data class Glyph(val icon: ImageVector, val tint: Color, val top: Color, val bottom: Color) : Token
}

/** 판에 쓰는 낱말들(문자열 리소스에서). */
internal data class StageWords(val yourTurn: String, val turn: String, val block: String, val challenge: String, val losing: String, val wins: String)

internal fun stageWordsOf(res: Resources) = StageWords(
    yourTurn = res.getString(R.string.game_turn_mine),
    turn = res.getString(R.string.game_turn_tab),
    block = res.getString(R.string.stage_block),
    challenge = res.getString(R.string.game_challenge),
    losing = res.getString(R.string.stage_losing),
    wins = res.getString(R.string.stage_wins),
)

/** 표준 규칙에서 행동한 사람이 얻는 코인. 규칙이 바뀐 판에서는 숫자를 보이지 않는다(틀릴 수 있다). */
private val STANDARD_GAIN = mapOf("income" to 1, "foreign_aid" to 2, "tax" to 3, "steal" to 2)

private fun actionToken(action: ActionId): Token = when (action.value) {
    "income" -> Token.Coin
    "foreign_aid" -> Token.Coins
    "coup" -> Token.Glyph(CoupIcons.Bolt, Parchment, Crimson, CrimsonDeep)
    else -> Token.Glyph(ActionUiCatalog.icon(action), GoldBright, PanelTop, Panel)
}

/** 진행 배너를 장면으로 바꾼다. [standardRules]가 아니면 얻는 코인 숫자를 붙이지 않는다. */
internal fun sceneOf(banner: Banner, me: PlayerId?, texts: GameTexts, standardRules: Boolean, words: StageWords): StageScene = when (banner) {
    // 내 차례: 내 얼굴은 바로 아래 내 자리에 있으므로 판에는 별과 "내 차례"만 놓는다
    is Banner.YourTurn -> StageScene(
        null, null,
        // 코인이 넘쳐 쿠만 할 수 있으면 별 대신 번개가 놓인다
        if (banner.forced) Token.Glyph(CoupIcons.Bolt, Parchment, Crimson, CrimsonDeep) else Token.Glyph(CoupIcons.Star, GoldInk, GoldBright, Gold),
        words.yourTurn, accent = GoldBright, big = true,
    )
    is Banner.WaitingFor -> StageScene(banner.actor, null, Token.Glyph(CoupIcons.Hourglass, ParchmentDim, PanelTop, Panel), words.turn, accent = Parchment)
    is Banner.ActionDeclared -> actionScene(banner.actor, banner.actionId, banner.target, banner.claimedRoles.firstOrNull(), banner.waitingOn, texts, standardRules)
    // 행동 도전이 실패한 뒤 막기만 남은 창: 그 행동의 장면 그대로(주장은 이미 증명됐다)
    is Banner.BlockWindow -> actionScene(banner.actor, banner.actionId, banner.target, null, banner.waitingOn, texts, standardRules)
    is Banner.BlockDeclared -> StageScene(
        banner.blocker, banner.role, null, words.block, accent = RoleVisuals.of(banner.role.value).accent,
        target = banner.actor, waiting = banner.waitingOn,
    )
    is Banner.Challenged -> StageScene(
        banner.challenger, null, Token.Glyph(CoupIcons.Swords, Parchment, Crimson, CrimsonDeep), words.challenge, accent = CrimsonLight,
        target = banner.challenged, targetCard = banner.claimedRoles.firstOrNull(),
    )
    is Banner.LosingInfluence -> StageScene(banner.player, null, Token.Glyph(CoupIcons.Cross, Parchment, CrimsonDeep, LOSS_BOTTOM), words.losing, accent = CrimsonLight)
    is Banner.Exchanging -> StageScene(banner.player, RoleId("ambassador"), null, texts.actionName(ActionId("exchange")), accent = RoleVisuals.of("ambassador").accent)
    is Banner.GameOver -> StageScene(banner.winner, null, Token.Glyph(CoupIcons.Crown, GoldInk, GoldBright, Gold), words.wins, accent = GoldBright, big = true)
}

private val LOSS_BOTTOM = Color(0xFF2A0A0E)

private fun actionScene(actor: PlayerId, action: ActionId, target: PlayerId?, claim: RoleId?, waiting: Set<PlayerId>, texts: GameTexts, standardRules: Boolean) = StageScene(
    actor = actor,
    card = claim,
    token = if (claim == null) actionToken(action) else null,
    verb = texts.actionName(action),
    accent = claim?.let { RoleVisuals.of(it.value).accent } ?: if (action.value == "coup") CrimsonLight else Gold,
    target = target,
    gain = STANDARD_GAIN[action.value]?.takeIf { standardRules },
    waiting = waiting,
)

/** 응답 시트 머리의 장면: 지금 응답해야 하는 일을 판과 같은 그림으로. */
internal fun respondScene(state: GameUiState, decision: DecisionUi.Respond, texts: GameTexts, words: StageWords): StageScene =
    when (val banner = state.banner) {
        is Banner.ActionDeclared, is Banner.BlockDeclared, is Banner.BlockWindow -> sceneOf(banner, state.me?.id, texts, !state.ruleSet.isCustom, words)
        // 진행 배너가 응답 창과 맞지 않으면(있을 수 없지만) 결정에 담긴 것만으로 그린다
        else -> StageScene(
            decision.actor, decision.claimedRoles.firstOrNull(),
            if (decision.claimedRoles.isEmpty()) actionToken(decision.actionId) else null,
            texts.actionName(decision.actionId), accent = Gold,
        )
    }

/** 스크린리더가 읽을 문장: 진행 문장과, 아직 응답하지 않은 사람들. */
internal fun spokenScene(res: Resources, sentence: String, scene: StageScene, texts: GameTexts): String =
    if (scene.waiting.isEmpty()) sentence else sentence + ". " + res.getString(R.string.game_waiting_on, scene.waiting.joinToString(", ") { texts.name(it) })

/**
 * 테이블 한가운데의 판. 지금 벌어지는 일을 그림 한 줄로 그린다(누가 → 무슨 카드 → 무엇을 → 누구에게). 글은 한두 낱말뿐이고,
 * 스크린리더에는 진행 문장 전체가 읽힌다. 내가 결정할 차례면 남은 시간이 막대로 붙는다(응답할 때는 응답 시트가 이 자리를 대신한다).
 */
@Composable
internal fun StagePanel(state: GameUiState, texts: GameTexts, compact: Boolean = false, anchors: TableAnchors? = null) {
    val res = LocalResources.current
    // 판이 놓인 곳을 적어 두면 화살표가 판 아래로 지나간다. 판이 사라지면(응답 시트가 대신하면) 지운다
    if (anchors != null) DisposableEffect(anchors) { onDispose { anchors.stage = null } }
    val scene = sceneOf(state.banner, state.me?.id, texts, standardRules = !state.ruleSet.isCustom, stageWordsOf(res))
    val spoken = spokenScene(res, texts.banner(state.banner), scene, texts)
    val motion = LocalMotion.current
    Column(
        Modifier
            .fillMaxWidth()
            .then(anchors?.stageArea() ?: Modifier)
            .testTag(GameTags.BANNER)
            .felt()
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 내가 결정할 차례일 때만 내 남은 시간을 보인다(끝난 판·기다리는 중에는 숨김). 낮은 화면에서는 장면 옆에 짧게 붙인다
        val deadline = state.myDeadline?.takeIf { state.decision != null && state.decision !is DecisionUi.Respond && state.result == null }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
                if (!motion.enabled) {
                    SceneRow(scene, state, texts, compact)
                } else {
                    // 장면이 바뀌면 새 장면이 아래에서 올라온다(응답을 기다리는 사람이 줄어드는 것처럼 같은 장면 안의 변화에는 움직이지 않는다)
                    AnimatedContent(
                        targetState = scene,
                        contentKey = { it.copy(waiting = emptySet()) },
                        transitionSpec = {
                            val ms = motion.ms(STAGE_MS)
                            (fadeIn(tween(ms)) + slideInVertically(tween(ms)) { it / 3 }) togetherWith fadeOut(tween(ms / 2))
                        },
                        label = "stage",
                    ) { SceneRow(it, state, texts, compact) }
                }
            }
            if (deadline != null && compact) Countdown(deadline, GameTags.TIMER_MINE, Modifier.width(COMPACT_TIMER_WIDTH), style = TimerStyle.BAR)
        }
        if (deadline != null && !compact) Countdown(deadline, GameTags.TIMER_MINE, Modifier.fillMaxWidth(), style = TimerStyle.BAR)
    }
}

private val COMPACT_TIMER_WIDTH = 110.dp

private const val STAGE_MS = 260

/**
 * 장면 한 줄: [사람] [카드/문양] [낱말 / +코인·기다리는 얼굴] (→ [사람] [의심받는 카드]).
 * 가운데 판과 응답 시트의 머리에서 같이 쓴다.
 */
@Composable
internal fun SceneRow(scene: StageScene, state: GameUiState, texts: GameTexts, compact: Boolean) {
    val figure = if (compact) 26.dp else 36.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scene.actor?.let { Figure(it, state, texts, figure) }
        Played(scene.card, scene.token, scene.accent, if (compact) 28.dp else 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                scene.verb,
                style = if (scene.big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                color = scene.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (scene.gain != null || scene.waiting.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scene.gain?.let { GainChip(it) }
                    if (scene.waiting.isNotEmpty()) WaitingFaces(scene.waiting, state, texts)
                }
            }
        }
        scene.target?.let { target ->
            Icon(CoupIcons.ArrowRight, contentDescription = null, tint = scene.accent, modifier = Modifier.size(22.dp))
            Figure(target, state, texts, figure)
            scene.targetCard?.let { MarkedCard(it, if (compact) 24.dp else 28.dp, Mark.DOUBT) }
        }
    }
}

/** 사람: 자리 색 아바타와 이름(나는 금색 사람 그림). 판·응답 시트·극적인 순간의 띠에서 같이 쓴다. */
@Composable
internal fun Figure(id: PlayerId, state: GameUiState, texts: GameTexts, size: Dp) {
    val mine = id == state.me?.id
    val name = texts.name(id)
    Column(Modifier.widthIn(max = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Avatar(name, if (mine) Gold else seatTint(state.opponents.indexOfFirst { it.id == id }), size = size, ring = if (mine) GoldBright else GoldDeep, icon = if (mine) CoupIcons.Person else null)
        Text(name, style = MaterialTheme.typography.labelSmall, color = if (mine) GoldBright else Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** 내민 카드(역할 색으로 빛나며 살짝 기울었다) 또는 문양. */
@Composable
private fun Played(card: RoleId?, token: Token?, accent: Color, width: Dp) {
    if (card != null) {
        val glow = RoleVisuals.of(card.value).accent
        Box(
            Modifier
                .size(width = width + 10.dp, height = width / CARD_ASPECT + 6.dp)
                .drawBehind { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.5f), Color.Transparent), center = center, radius = size.maxDimension * 0.6f)) },
            contentAlignment = Alignment.Center,
        ) {
            RoleCard(
                card, faceUp = true,
                modifier = Modifier
                    .graphicsLayer { rotationZ = -6f }
                    .shadow(6.dp, RoundedCornerShape(4.dp))
                    .size(width = width, height = width / CARD_ASPECT),
            )
        }
        return
    }
    when (token) {
        Token.Coin -> Box(Modifier.size(width), contentAlignment = Alignment.Center) { CoinIcon(size = width * 0.72f) }
        Token.Coins -> Box(Modifier.size(width), contentAlignment = Alignment.Center) { CoinPile(3, size = width * 0.62f) }
        is Token.Glyph -> Box(
            Modifier.size(width).panel(CircleShape, top = token.top, bottom = token.bottom, line = accent, lineWidth = 1.5.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(token.icon, contentDescription = null, tint = token.tint, modifier = Modifier.size(width * 0.55f)) }
        null -> Unit
    }
}

/** 작은 카드에 붙는 표: 의심(붉은 ?), 참(금색 체크), 거짓·잃음(붉은 X). */
internal enum class Mark { DOUBT, TRUE, FALSE }

/**
 * 표가 붙은 작은 카드: 도전받는 주장(?), 증명된 주장(체크), 들통난 주장(X). [role]이 null이면 뒷면(누군가 잃는 카드 한 장)이다.
 */
@Composable
internal fun MarkedCard(role: RoleId?, width: Dp, mark: Mark) {
    Box(Modifier.size(width = width + 6.dp, height = width / CARD_ASPECT + 6.dp)) {
        RoleCard(role, faceUp = role != null, modifier = Modifier.align(Alignment.BottomStart).graphicsLayer { rotationZ = 6f }.size(width = width, height = width / CARD_ASPECT))
        val (top, bottom, line) = if (mark == Mark.TRUE) Triple(GoldBright, Gold, GoldBright) else Triple(Crimson, CrimsonDeep, CrimsonLight)
        Box(
            Modifier.align(Alignment.TopEnd).size(18.dp).panel(CircleShape, top = top, bottom = bottom, line = line, lineWidth = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (mark) {
                Mark.DOUBT -> Text("?", color = Parchment, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
                Mark.TRUE -> Icon(CoupIcons.Check, contentDescription = null, tint = GoldInk, modifier = Modifier.size(12.dp))
                Mark.FALSE -> Icon(CoupIcons.Cross, contentDescription = null, tint = Parchment, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** 행동한 사람이 얻는 코인: 코인 그림과 +N. */
@Composable
private fun GainChip(gain: Int) {
    Row(
        Modifier.panel(CircleShape, top = PanelTop, bottom = Panel, line = Gold.copy(alpha = 0.6f)).padding(start = 3.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CoinIcon(size = 14.dp)
        Text("+$gain", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = GoldBright)
    }
}

/** 아직 응답하지 않은 사람들: 모래시계와 작은 얼굴들. */
@Composable
private fun WaitingFaces(waiting: Set<PlayerId>, state: GameUiState, texts: GameTexts) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CoupIcons.Hourglass, contentDescription = null, tint = ParchmentDim, modifier = Modifier.size(13.dp))
        waiting.forEach { id ->
            val mine = id == state.me?.id
            Avatar(texts.name(id), if (mine) Gold else seatTint(state.opponents.indexOfFirst { it.id == id }), size = 20.dp, icon = if (mine) CoupIcons.Person else null)
        }
    }
}
