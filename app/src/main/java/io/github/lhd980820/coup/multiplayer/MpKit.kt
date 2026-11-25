package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.theme.Crimson
import io.github.lhd980820.coup.ui.theme.CrimsonDeep
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim

// 멀티플레이 화면의 공통 부품(docs/DESIGN.md "멀티플레이 화면"): 게임 화면과 같은 테이블 모습으로 맞춘다.

/** 화면 가장자리의 여백. */
internal val ScreenGutter = 16.dp

/**
 * 맨 위 줄: 뒤로(그림 버튼, [onBack]이 있을 때), 세리프 제목, 오른쪽 그림 버튼들([actions]). 그림 버튼의 이름은 스크린리더가 읽는다.
 */
@Composable
internal fun ScreenBar(
    title: String?,
    onBack: (() -> Unit)?,
    backTag: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            BarButton(CoupIcons.ArrowLeft, stringResource(R.string.mp_back), onBack, backTag)
        } else {
            Spacer(Modifier.width(4.dp))
        }
        if (title != null) {
            Text(
                title,
                Modifier.weight(1f).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Parchment,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** 맨 위 줄의 그림 버튼(누르는 영역 48dp). */
@Composable
internal fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, enabled: Boolean = true, tint: Color = Parchment) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else ParchmentDim.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
    }
}

/** 판 하나로 묶은 구역: 금색 작은 머리(그림 + 이름)와 그 아래 내용. */
@Composable
internal fun Section(title: String?, icon: ImageVector?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let { Icon(it, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp)) }
                Text(title, style = MaterialTheme.typography.labelLarge, color = Gold)
            }
        }
        content()
    }
}

/**
 * 알림 띠: 문제(붉은색, 느낌표)나 안내(금색). 바뀌면 스크린리더가 읽는다.
 */
@Composable
internal fun NoticeBar(text: String, tag: String, error: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .panel(top = if (error) CrimsonDeep else PanelTop, bottom = if (error) Color(0xFF3A0D12) else Panel, line = if (error) Crimson else Gold.copy(alpha = 0.5f))
            // 띠 하나가 한 덩어리로 읽힌다(그림은 장식)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (error) CoupIcons.Exclaim else CoupIcons.Scroll, contentDescription = null, tint = if (error) CrimsonLight else Gold, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Parchment)
    }
}

/** 그림과 짧은 낱말의 작은 표(레이팅·봇·바꾼 규칙 등). */
@Composable
internal fun IconChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = ParchmentDim) {
    Row(
        modifier.panel(CircleShape, top = PanelTop, bottom = Panel, line = color.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * 자리 표: 앉은 사람은 금색 사람 그림, 빈 자리는 점선 동그라미. 몇 명이 앉았는지 한눈에 보인다(스크린리더에는 [label]).
 */
@Composable
internal fun SeatDots(filled: Int, max: Int, label: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Row(modifier.clearAndSetSemantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(max) { i ->
            if (i < filled) {
                Box(Modifier.size(size).panel(CircleShape, top = GoldBright, bottom = Gold, line = GoldBright, lineWidth = 0.5.dp), contentAlignment = Alignment.Center) {
                    Icon(CoupIcons.Person, contentDescription = null, tint = io.github.lhd980820.coup.ui.theme.GoldInk, modifier = Modifier.size(size * 0.7f))
                }
            } else {
                Box(Modifier.size(size).border(1.dp, ParchmentDim.copy(alpha = 0.5f), CircleShape))
            }
        }
    }
}

/** 화면 가운데의 빈 상태: 큰 그림과 한두 줄. */
@Composable
internal fun EmptyState(icon: ImageVector, text: String, tag: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp).panel(CircleShape, top = PanelTop, bottom = Panel, line = Gold.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(34.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ParchmentDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 둥근 −/+ 그림 버튼(누르는 영역 48dp). */
@Composable
internal fun StepButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }.testTag(tag)) {
        Box(
            Modifier.size(38.dp).panel(CircleShape, top = PanelTop, bottom = Panel, line = if (enabled) Gold.copy(alpha = 0.7f) else io.github.lhd980820.coup.ui.kit.Hairline),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (enabled) GoldBright else ParchmentDim.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) }
    }
}
