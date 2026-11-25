package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.ui.kit.TableSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.kit.seatTint
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.felt
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Avatar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import io.github.lhd980820.coup.account.AppliedResult
import io.github.lhd980820.coup.account.RankingEntry
import io.github.lhd980820.coup.shell.stats.HistoryModel
import io.github.lhd980820.coup.shell.stats.MyStanding
import io.github.lhd980820.coup.shell.stats.RankingModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal object StatsTags {
    const val BACK = "stats_back"
    const val OFFLINE = "stats_offline"
    const val EMPTY = "stats_empty"
    const val LOADING = "stats_loading"
    const val ME = "stats_me"
    const val SUMMARY = "stats_summary"
    const val NET = "stats_net"
    fun rank(uid: String) = "rank_row_$uid"
    fun history(gameId: String) = "history_row_$gameId"
}

/**
 * 랭킹 화면: 상위 순위와 내 자리. 구독·정렬·공동 순위는 코어([RankingModel])가 한다.
 * 모습: 맨 위에 내 자리(트로피), 그 아래 순위 줄 — 1~3위는 금·은·동 메달, 얼굴, 이름과 판·승, 별과 레이팅. 내 줄은 금빛이 돈다.
 */
@Composable
internal fun RankingScreen(model: RankingModel, myUid: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.run() }
    TableSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenGutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenBar(stringResource(R.string.stats_ranking_title), onBack, StatsTags.BACK)
            if (state.offline) OfflineNote()
            state.me?.let { MyStandingCard(it) }
            val entries = state.entries
            when {
                entries == null -> Loading(R.string.stats_ranking_loading)
                entries.isEmpty() -> EmptyState(CoupIcons.Trophy, stringResource(R.string.stats_ranking_empty), StatsTags.EMPTY)
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.uid }) { RankRow(it, mine = it.uid == myUid) }
                }
            }
        }
    }
}

@Composable
private fun MyStandingCard(me: MyStanding) {
    val text = when (me) {
        is MyStanding.InTop -> stringResource(R.string.stats_me_in_top, me.entry.rank, me.entry.rating)
        is MyStanding.BelowTop -> stringResource(R.string.stats_me_below, me.profile.rating, RankingModel.DEFAULT_LIMIT)
        is MyStanding.Unranked -> stringResource(R.string.stats_me_unranked)
    }
    Row(
        Modifier.fillMaxWidth().testTag(StatsTags.ME).felt().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).panel(CircleShape, top = GoldBright, bottom = Gold, line = GoldBright), contentAlignment = Alignment.Center) {
            Icon(CoupIcons.Trophy, contentDescription = null, tint = GoldInk, modifier = Modifier.size(22.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Parchment)
    }
}

@Composable
private fun RankRow(entry: RankingEntry, mine: Boolean) {
    val medal = medalOf(entry.rank)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(StatsTags.rank(entry.uid))
            .panel(top = if (mine) MineWarm else PanelHigh, line = when {
                mine -> GoldBright
                medal != null -> medal.copy(alpha = 0.6f)
                else -> Hairline
            }, lineWidth = if (mine) 1.5.dp else 1.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RankMedal(stringResource(R.string.stats_rank, entry.rank), medal)
        Avatar(entry.nickname, if (mine) Gold else seatTint(entry.uid.hashCode()), size = 34.dp, icon = if (mine) CoupIcons.Person else null)
        Column(Modifier.weight(1f)) {
            Text(
                if (mine) "${entry.nickname} ${stringResource(R.string.stats_row_me)}" else entry.nickname,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.stats_row_record, entry.plays, entry.wins), style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(CoupIcons.Star, contentDescription = null, tint = Gold, modifier = Modifier.size(14.dp))
            Text(entry.rating.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = GoldBright)
        }
    }
}

/** 1~3위의 메달 색(금·은·동). 그 밖은 없다. */
private fun medalOf(rank: Int): Color? = when (rank) {
    1 -> GoldBright
    2 -> Silver
    3 -> Bronze
    else -> null
}

/** 순위 동그라미: 1~3위는 메달 색으로 채우고, 그 밖은 어두운 판에 숫자. */
@Composable
private fun RankMedal(text: String, medal: Color?) {
    Box(
        Modifier.size(40.dp).panel(CircleShape, top = medal ?: PanelTop, bottom = medal?.let { lerp(it, Color.Black, 0.25f) } ?: Panel, line = medal ?: Hairline),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = if (medal != null) GoldInk else Parchment, maxLines = 1)
    }
}

private val Silver = Color(0xFFD9DEE4)
private val Bronze = Color(0xFFD9955A)
private val MineWarm = Color(0xFF3A2C16)

/**
 * 전적 화면: 내 rated 게임 기록(최근 것부터)과 요약.
 * 모습: 맨 위에 판·승·승률 세 칸(스크린리더에는 한 문장), 최근 레이팅 변화, 그 아래 판마다 한 줄 — 1위는 왕관, 몇 인전 몇 위, 날짜, 오르내린 레이팅(화살표와 색).
 */
@Composable
internal fun HistoryScreen(model: HistoryModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.run() }
    val locale: Locale = LocalConfiguration.current.locales[0]
    TableSurface(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenGutter, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenBar(stringResource(R.string.stats_history_title), onBack, StatsTags.BACK)
            if (state.offline) OfflineNote()
            val results = state.results
            val profile = state.profile
            if (profile != null && results != null) {
                val rate = state.winRate?.let { (it * 100).toInt() } ?: 0
                SummaryTiles(profile.plays, profile.wins, rate, stringResource(R.string.stats_history_summary, profile.plays, profile.wins, rate))
                if (results.isNotEmpty()) {
                    Text(
                        stringResource(R.string.stats_history_net, results.size, state.netDelta),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.netDelta >= 0) GoldBright else CrimsonLight,
                        modifier = Modifier.testTag(StatsTags.NET),
                    )
                }
            }
            when {
                results == null -> Loading(R.string.stats_history_loading)
                results.isEmpty() -> EmptyState(CoupIcons.Scroll, stringResource(R.string.stats_history_empty), StatsTags.EMPTY)
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.gameId }) { HistoryRow(it, locale) }
                }
            }
        }
    }
}

/** 판·승·승률 세 칸. 스크린리더에는 [spoken] 한 문장으로 읽힌다. */
@Composable
private fun SummaryTiles(plays: Int, wins: Int, rate: Int, spoken: String) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clearAndSetSemantics { contentDescription = spoken }.testTag(StatsTags.SUMMARY),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(CoupIcons.Swords, plays.toString(), stringResource(R.string.stats_tile_plays), Modifier.weight(1f).fillMaxHeight())
        StatTile(CoupIcons.Crown, wins.toString(), stringResource(R.string.stats_tile_wins), Modifier.weight(1f).fillMaxHeight())
        StatTile(CoupIcons.Star, "$rate%", stringResource(R.string.stats_tile_rate), Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Column(modifier.panel().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Parchment)
        Text(label, style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
    }
}

@Composable
private fun HistoryRow(result: AppliedResult, locale: Locale) {
    val date = remember(result.finishedAt, locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(result.finishedAt)) }
    val deltaDesc = stringResource(R.string.stats_history_delta_desc, result.delta)
    val won = result.rank == 0
    val up = result.delta >= 0
    Row(
        Modifier.fillMaxWidth().testTag(StatsTags.history(result.gameId)).panel(line = if (won) Gold.copy(alpha = 0.7f) else Hairline).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1위는 왕관, 그 밖은 순위 숫자
        Box(
            Modifier.size(38.dp).panel(CircleShape, top = if (won) GoldBright else PanelTop, bottom = if (won) Gold else Panel, line = if (won) GoldBright else Hairline),
            contentAlignment = Alignment.Center,
        ) {
            if (won) Icon(CoupIcons.Crown, contentDescription = null, tint = GoldInk, modifier = Modifier.size(20.dp))
            else Text((result.rank + 1).toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Parchment)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.stats_history_row, result.playerCount, result.rank + 1), style = MaterialTheme.typography.titleSmall, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal, color = Parchment)
            Text(date, style = MaterialTheme.typography.labelSmall, color = ParchmentDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(if (up) CoupIcons.ArrowUp else CoupIcons.ArrowDown, contentDescription = null, tint = if (up) GoldBright else CrimsonLight, modifier = Modifier.size(16.dp))
            // 숫자는 그대로 보이고, 스크린리더에는 "레이팅 +12"처럼 읽힌다
            Text(
                "%+d".format(result.delta),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = if (up) GoldBright else CrimsonLight,
                modifier = Modifier.semantics { contentDescription = deltaDesc },
            )
        }
    }
}

/** 불러오는 중: 도는 고리와 한 줄. */
@Composable
private fun Loading(text: Int) {
    Row(Modifier.testTag(StatsTags.LOADING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Gold, strokeWidth = 2.dp)
        Text(stringResource(text), color = ParchmentDim)
    }
}

@Composable
private fun OfflineNote() {
    NoticeBar(stringResource(R.string.stats_offline), StatsTags.OFFLINE, error = true)
}
