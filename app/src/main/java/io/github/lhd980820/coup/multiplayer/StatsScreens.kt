package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
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

/** 랭킹 화면: 상위 순위와 내 자리. 구독·정렬·공동 순위는 코어([RankingModel])가 한다. */
@Composable
internal fun RankingScreen(model: RankingModel, myUid: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.run() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsHeader(R.string.stats_ranking_title, onBack)
            if (state.offline) OfflineNote()
            state.me?.let { MyStandingCard(it) }
            val entries = state.entries
            when {
                entries == null -> Text(stringResource(R.string.stats_ranking_loading), Modifier.testTag(StatsTags.LOADING))
                entries.isEmpty() -> Text(stringResource(R.string.stats_ranking_empty), Modifier.testTag(StatsTags.EMPTY))
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Card(Modifier.fillMaxWidth().testTag(StatsTags.ME), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun RankRow(entry: RankingEntry, mine: Boolean) {
    val colors = if (mine) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth().testTag(StatsTags.rank(entry.uid)), colors = colors) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.stats_rank, entry.rank), Modifier.width(48.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(if (mine) "${entry.nickname} ${stringResource(R.string.stats_row_me)}" else entry.nickname, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.stats_row_record, entry.plays, entry.wins), style = MaterialTheme.typography.bodySmall)
            }
            Text(entry.rating.toString(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 전적 화면: 내 rated 게임 기록(최근 것부터)과 요약. */
@Composable
internal fun HistoryScreen(model: HistoryModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.run() }
    val locale: Locale = LocalConfiguration.current.locales[0]
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsHeader(R.string.stats_history_title, onBack)
            if (state.offline) OfflineNote()
            val results = state.results
            val profile = state.profile
            if (profile != null && results != null) {
                val rate = state.winRate?.let { (it * 100).toInt() } ?: 0
                Text(stringResource(R.string.stats_history_summary, profile.plays, profile.wins, rate), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(StatsTags.SUMMARY))
                if (results.isNotEmpty()) {
                    Text(stringResource(R.string.stats_history_net, results.size, state.netDelta), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(StatsTags.NET))
                }
            }
            when {
                results == null -> Text(stringResource(R.string.stats_history_loading), Modifier.testTag(StatsTags.LOADING))
                results.isEmpty() -> Text(stringResource(R.string.stats_history_empty), Modifier.testTag(StatsTags.EMPTY))
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.gameId }) { HistoryRow(it, locale) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(result: AppliedResult, locale: Locale) {
    val date = remember(result.finishedAt, locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(result.finishedAt)) }
    val deltaDesc = stringResource(R.string.stats_history_delta_desc, result.delta)
    val won = result.rank == 0
    Card(Modifier.fillMaxWidth().testTag(StatsTags.history(result.gameId))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_history_row, result.playerCount, result.rank + 1), style = MaterialTheme.typography.titleSmall, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal)
                Text(date, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "%+d".format(result.delta),
                style = MaterialTheme.typography.titleMedium,
                color = if (result.delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = deltaDesc },
            )
        }
    }
}

@Composable
private fun StatsHeader(title: Int, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(StatsTags.BACK)) { Text(stringResource(R.string.mp_back)) }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun OfflineNote() {
    Text(
        stringResource(R.string.stats_offline),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(StatsTags.OFFLINE),
    )
}
