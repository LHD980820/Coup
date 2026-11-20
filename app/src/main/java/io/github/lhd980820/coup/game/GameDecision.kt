package io.github.lhd980820.coup.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import io.github.lhd980820.coup.engine.model.RoleId
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.BlockButtonUi
import io.github.lhd980820.coup.presentation.CardPurpose
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.DisabledReason
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.tutorial.TutorialExpect
import io.github.lhd980820.coup.ui.kit.Avatar
import io.github.lhd980820.coup.ui.kit.ButtonShape
import io.github.lhd980820.coup.ui.kit.CoinIcon
import io.github.lhd980820.coup.ui.kit.CoinPile
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.RoleVisual
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.kit.SecondaryButton
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.theme.ChallengeRed
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.PanelLow
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim

private val TILE_GAP = 8.dp

/** 막기 버튼은 반 폭에 "사령관으로 막기"가 한 줄로 들어가야 한다. */
private val BLOCK_PADDING = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
private val TILE_SHAPE = RoundedCornerShape(14.dp)

/** 행동 칸 하나의 최소 너비(글자 크기 설정이 크면 함께 커진다 — 한 줄에 놓는 칸 수가 준다). */
private val TILE_MIN_WIDTH = 66.dp
private const val TILE_FONT_SCALE_CAP = 1.35f

/** 안내가 가리키는 컨트롤은 테두리로 강조한다(색만으로 전달하지 않도록 코치 문구도 함께 있다). */
private fun Modifier.guideRing(on: Boolean): Modifier = if (on) this.border(3.dp, GoldBright, RoundedCornerShape(18.dp)).padding(3.dp) else this

private fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

// ---- 결정 ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecisionPanel(state: GameUiState, texts: GameTexts, actions: GameActions, expect: TutorialExpect? = null) {
    val decision = state.decision
    val enabled = !state.busy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (decision) {
            // 내가 할 일이 없으면 아무것도 그리지 않는다(누구를 기다리는지는 가운데 판이 그림으로 보인다)
            null -> Unit
            is DecisionUi.Actions -> ActionGrid(decision.buttons, texts, enabled, expect, actions::chooseAction)
            is DecisionUi.PickTarget -> {
                Prompt(stringResource(R.string.game_pick_target), CoupIcons.Target)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    decision.targets.forEach { id ->
                        val name = texts.name(id)
                        Button(
                            onClick = { actions.chooseTarget(id) },
                            enabled = enabled,
                            shape = ButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = PanelTop, contentColor = Parchment),
                            border = BorderStroke(1.5.dp, Gold),
                            modifier = Modifier.heightIn(min = 52.dp).testTag(GameTags.target(id.value)),
                        ) {
                            Avatar(name, seatTint(state.opponents.indexOfFirst { it.id == id }), size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontWeight = FontWeight.Bold)
                        }
                    }
                    SecondaryButton(stringResource(R.string.game_cancel), actions::cancelTarget, Modifier.testTag(GameTags.CANCEL_TARGET), line = Hairline, content = ParchmentDim)
                }
            }
            is DecisionUi.Respond -> Unit // 아래에 붙는 응답 시트([ResponseSheet])가 그린다
            is DecisionUi.PickCard -> {
                if (decision.purpose == CardPurpose.REVEAL) {
                    // 도전받은 주장: 그 역할 카드를 작게 보이고 "공개할 카드"를 고르게 한다(증명하는 카드에는 손패에서 금색 체크가 붙는다)
                    Prompt(stringResource(R.string.game_pick_reveal), roles = decision.claimedRoles.toList())
                } else {
                    Prompt(stringResource(R.string.game_pick_lose), CoupIcons.Cross, tint = CrimsonLight)
                }
                PrimaryButton(
                    stringResource(R.string.game_confirm), actions::confirmCard,
                    Modifier.fillMaxWidth().testTag(GameTags.CONFIRM), enabled = enabled && decision.selected != null, icon = CoupIcons.Check,
                )
            }
            is DecisionUi.PickExchange -> {
                Prompt(stringResource(R.string.game_pick_exchange, decision.keepCount), CoupIcons.Swap, tint = RoleVisuals.of("ambassador").accent)
                PrimaryButton(
                    stringResource(R.string.game_confirm), actions::confirmExchange,
                    Modifier.fillMaxWidth().testTag(GameTags.CONFIRM), enabled = enabled && decision.canConfirm, icon = CoupIcons.Check,
                )
            }
        }
    }
}

/** 결정 안내 한 줄: 앞에 그림(또는 작은 역할 카드)이 붙는다. */
@Composable
private fun Prompt(text: String, icon: ImageVector? = null, tint: Color = Gold, roles: List<RoleId> = emptyList()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        roles.forEach { RoleCard(it, faceUp = true, modifier = Modifier.size(width = 20.dp, height = 28.dp)) }
        Text(text, style = MaterialTheme.typography.titleSmall, color = Parchment)
    }
}

/**
 * 내 차례의 행동들. 위 줄은 누구나 하는 행동(수입·해외 원조·쿠), 아래 줄은 역할을 주장하는 행동이다.
 * 역할 행동은 그 역할의 색·문양으로 그리고, **내가 그 역할을 들고 있으면 색을 채우고 블러핑이면 점선 테두리만** 그린다 — 손패를 보지 않아도 진실과 거짓이 갈린다.
 */
@Composable
private fun ActionGrid(buttons: List<ActionButtonUi>, texts: GameTexts, enabled: Boolean, expect: TutorialExpect?, onChoose: (ActionId) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, TILE_FONT_SCALE_CAP)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + TILE_GAP) / (TILE_MIN_WIDTH * fontScale + TILE_GAP)).toInt().coerceIn(2, 4)
        val (general, claims) = buttons.partition { it.claimedRoles.isEmpty() }
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            listOf(general, claims).filter { it.isNotEmpty() }.forEach { group ->
                // 한 줄에 다 못 놓으면 줄마다 고르게 나눈다(넷을 세 칸에 놓으면 3+1이 아니라 2+2)
                val lines = (group.size + columns - 1) / columns
                val rows = group.chunked((group.size + lines - 1) / lines)
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        row.forEach { b ->
                            val allowed = expect.allowsAction(b.actionId.value)
                            ActionTile(b, texts, enabled && allowed, ring = expect != null && allowed, modifier = Modifier.weight(1f).fillMaxHeight()) { onChoose(b.actionId) }
                        }
                        // 여러 줄로 나뉜 묶음의 마지막 줄은 빈 칸으로 채워 칸 너비를 맞춘다
                        if (rows.size > 1) repeat(rows.first().size - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(button: ActionButtonUi, texts: GameTexts, enabled: Boolean, ring: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val on = enabled && button.enabled
    val visual = button.claimedRoles.firstOrNull()?.let { RoleVisuals.of(it.value) }
    val truthful = visual != null && !button.bluff
    val coup = button.actionId.value == "coup"
    val name = texts.actionName(button.actionId)
    val label = if (button.cost > 0) stringResource(R.string.game_action_cost, name, button.cost) else name
    // 색만으로 의미를 전달하지 않는다: 할 수 없음(자물쇠)·필수(느낌표)·블러핑(가면)은 이름 앞의 작은 그림으로 보이고, 스크린리더에는 글로 읽힌다
    val badge: Pair<ImageVector, String>? = when {
        button.disabledReason == DisabledReason.INSUFFICIENT_COINS -> CoupIcons.Lock to stringResource(R.string.game_reason_no_coins)
        button.disabledReason == DisabledReason.NO_VALID_TARGET -> CoupIcons.Lock to stringResource(R.string.game_reason_no_target)
        button.forced -> CoupIcons.Exclaim to stringResource(R.string.game_forced)
        button.bluff -> CoupIcons.Mask to stringResource(R.string.game_bluff)
        else -> null
    }
    val roleName = button.claimedRoles.firstOrNull()?.let { texts.role(it) }
    // 스크린리더에는 이름(드는 코인)이 먼저, 그다음 주장하는 역할, 그다음 표시 그림이 읽힌다: "암살 (-3), 암살자, 블러핑"
    val spokenName = if (button.cost > 0 || roleName != null) listOfNotNull(label, roleName).joinToString(", ") else null
    val fill = when {
        !on -> listOf(PanelLow, PanelLow)
        truthful -> listOf(visual!!.top, visual.bottom)
        coup -> listOf(CrimsonDeep, Color(0xFF3A0D12))
        else -> listOf(PanelTop, Panel)
    }
    val line = when {
        !on -> Hairline.copy(alpha = 0.12f)
        visual != null -> visual.accent
        coup -> Crimson
        else -> Gold.copy(alpha = 0.7f)
    }
    val tint = when {
        !on -> ParchmentDim.copy(alpha = 0.55f)
        truthful -> GoldBright
        visual != null -> visual.accent
        coup -> CrimsonLight
        else -> Gold
    }
    val dashed = on && visual != null && button.bluff
    Box(
        modifier
            .guideRing(ring && on)
            .testTag(GameTags.action(button.actionId.value))
            .heightIn(min = 58.dp)
            .clip(TILE_SHAPE)
            .background(Brush.verticalGradient(fill))
            .then(if (dashed) Modifier.dashedBorder(line, 14.dp) else Modifier.border(if (on) 1.5.dp else 1.dp, line, TILE_SHAPE))
            .clickable(enabled = on, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 문양 옆에 드는 코인: 코인 그림 + 숫자(스크린리더에는 이름과 함께 "암살 (-3)"으로 읽힌다)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ActionGlyph(button.actionId, visual, tint, on)
                if (button.cost > 0) {
                    Row(
                        Modifier.alpha(if (on) 1f else 0.55f).clearAndSetSemantics { },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("−${button.cost}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (on) GoldBright else ParchmentDim)
                        CoinIcon(size = 11.dp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // 이름은 한 줄에 들어가게 필요하면 글자를 줄인다(긴 영어 이름이 낱말 중간에서 꺾이지 않게)
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Parchment else ParchmentDim.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f, fill = false).then(if (spokenName != null) Modifier.semantics { contentDescription = spokenName } else Modifier),
                )
                badge?.let { (icon, desc) ->
                    val badgeTint = when {
                        !on -> ParchmentDim.copy(alpha = 0.8f)
                        icon == CoupIcons.Exclaim -> GoldBright
                        // 역할 색으로 채운 칸이 아니므로(블러핑은 테두리만) 역할 색 가면이 잘 보인다
                        else -> visual?.accent ?: Parchment
                    }
                    Icon(icon, contentDescription = desc, tint = badgeTint, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** 행동의 문양: 역할 행동은 그 역할의 문양, 수입은 코인 한 닢, 해외 원조는 코인 더미, 쿠는 번개. */
@Composable
private fun ActionGlyph(action: ActionId, visual: RoleVisual?, tint: Color, on: Boolean) {
    val size = 22.dp
    when {
        visual != null -> Icon(visual.emblem, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        action.value == "income" -> CoinIcon(Modifier.alpha(if (on) 1f else 0.45f), size = 18.dp)
        action.value == "foreign_aid" -> CoinPile(3, Modifier.alpha(if (on) 1f else 0.45f), size = 18.dp)
        else -> Icon(ActionUiCatalog.icon(action), contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

// ---- 응답 시트 ----------------------------------------------------------------------------------

/**
 * 응답할 차례(허용·도전·막기)에 맨 아래에 붙는 시트(설계 §10.3). 모달이 아니라서 결정에 필요한 표·기록·내 카드가 그대로 보인다.
 * 맨 위에 남은 시간 막대, 그 아래 누구의 무슨 주장인지(주장하는 역할의 카드와 함께), 그리고 큰 버튼들이 온다 —
 * 허용은 테두리만, 도전은 붉은색, 막기는 그 역할의 색(블러핑이면 테두리만)이다. 나타날 때 스크린리더가 시트 이름을 읽는다.
 * 안내(튜토리얼)가 있으면 가리키는 버튼만 켜진다.
 */
@Composable
internal fun ResponseSheet(state: GameUiState, decision: DecisionUi.Respond, texts: GameTexts, actions: GameActions, expect: TutorialExpect?) {
    val enabled = !state.busy
    val paneTitle = stringResource(R.string.game_respond_pane)
    val claim = decision.claimedRoles.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { this.paneTitle = paneTitle }
            .testTag(GameTags.RESPONSE_SHEET)
            .panel(RoundedCornerShape(22.dp), top = PanelTop, bottom = PanelHigh, line = Gold.copy(alpha = 0.6f), lineWidth = 1.5.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.myDeadline?.let { Countdown(it, GameTags.TIMER_MINE, Modifier.fillMaxWidth(), style = TimerStyle.BAR) }
        // 이 줄이 응답하는 동안의 "지금 벌어지는 일"이다(가운데 판을 대신한다): 판과 같은 그림 한 줄. 스크린리더에는 문장으로 읽힌다
        val res = LocalResources.current
        val scene = respondScene(state, decision, texts, stageWordsOf(res))
        val claimLine = claim?.let { ". " + texts.name(decision.claimant) + " · " + stringResource(R.string.game_claiming, texts.roles(decision.claimedRoles)) }.orEmpty()
        val spoken = spokenScene(res, texts.respondHeadline(state.banner, decision.actor, decision.actionId, decision.windowKind) + claimLine, scene, texts)
        Box(Modifier.fillMaxWidth().testTag(GameTags.BANNER).clearAndSetSemantics { contentDescription = spoken }) {
            SceneRow(scene, state, texts, compact = false)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                stringResource(R.string.game_pass), actions::pass,
                Modifier.weight(1f).guideRing(expect != null && expect.allowsPass()).testTag(GameTags.PASS),
                enabled = enabled && expect.allowsPass(), icon = CoupIcons.Check,
            )
            if (decision.canChallenge) {
                PrimaryButton(
                    stringResource(R.string.game_challenge), actions::challenge,
                    Modifier.weight(1f).guideRing(expect != null && expect.allowsChallenge()).testTag(GameTags.CHALLENGE),
                    enabled = enabled && expect.allowsChallenge(), icon = CoupIcons.Swords, container = ChallengeRed, content = Color.White,
                )
            }
        }
        decision.blocks.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b -> BlockButton(b, texts, enabled, expect, Modifier.weight(1f).fillMaxHeight()) { actions.block(b.role) } }
            }
        }
    }
}

@Composable
private fun BlockButton(block: BlockButtonUi, texts: GameTexts, enabled: Boolean, expect: TutorialExpect?, modifier: Modifier, onClick: () -> Unit) {
    val allowed = expect.allowsBlock(block.role.value)
    val visual = RoleVisuals.of(block.role.value)
    val label = Josa.fix(stringResource(R.string.game_block_with, texts.role(block.role)))
    val tagged = modifier.guideRing(expect != null && allowed).testTag(GameTags.block(block.role.value))
    if (block.bluff) {
        // 그 역할 카드가 없는 막기(블러핑)는 테두리만 있고 방패 대신 가면이 그려진다. 스크린리더에는 "블러핑"이 덧붙어 읽힌다
        val bluff = stringResource(R.string.game_bluff)
        SecondaryButton(
            label, onClick, tagged.semantics { contentDescription = "$label · $bluff" },
            enabled = enabled && allowed, icon = CoupIcons.Mask, line = visual.accent, content = visual.accent, contentPadding = BLOCK_PADDING,
        )
    } else {
        PrimaryButton(label, onClick, tagged, enabled = enabled && allowed, icon = CoupIcons.Shield, container = visual.top, content = Parchment, contentPadding = BLOCK_PADDING)
    }
}
