package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.launch

internal object ProfileTags {
    const val CARD = "profile_card"
    const val EDIT = "profile_edit"
    const val SIGN_OUT = "profile_sign_out"
    const val RANKING = "profile_ranking"
    const val HISTORY = "profile_history"
    const val NICKNAME = "profile_nickname"
    const val SAVE = "profile_save"
    const val CANCEL = "profile_cancel"
}

/**
 * 홈 화면의 내 프로필(닉네임·레이팅·전적)과 닉네임 바꾸기·로그아웃. 닉네임은 카드 안에서 바로 고친다(대화상자를 따로 띄우지 않는다).
 * 검사는 코어(`Nicknames`)가 하고 [onSaveNickname]이 이유를 돌려준다(null이면 성공). 서버 오류는 [TransportException]으로 올라온다.
 */
@Composable
internal fun ProfileCard(
    profile: UserProfile,
    onSaveNickname: suspend (String) -> NicknameError?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    onRanking: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(profile.nickname, editing) { mutableStateOf(profile.nickname) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(modifier.fillMaxWidth().testTag(ProfileTags.CARD), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.mp_profile_line, profile.nickname, profile.rating), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.mp_profile_stats, profile.plays, profile.wins), style = MaterialTheme.typography.bodyMedium)
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.mp_nickname)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth().testTag(ProfileTags.NICKNAME),
                )
                Row {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                error = try {
                                    onSaveNickname(text)?.let { MultiplayerTexts.nickname(it) }
                                } catch (e: TransportException) {
                                    R.string.mp_nickname_save_failed
                                }
                                busy = false
                                if (error == null) editing = false
                            }
                        },
                        modifier = Modifier.testTag(ProfileTags.SAVE),
                    ) { Text(stringResource(R.string.mp_save)) }
                    TextButton(onClick = { editing = false; error = null }, modifier = Modifier.testTag(ProfileTags.CANCEL)) {
                        Text(stringResource(R.string.mp_cancel))
                    }
                }
            } else {
                if (onRanking != null || onHistory != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        onRanking?.let { TextButton(onClick = it, modifier = Modifier.testTag(ProfileTags.RANKING)) { Text(stringResource(R.string.stats_ranking_open)) } }
                        onHistory?.let { TextButton(onClick = it, modifier = Modifier.testTag(ProfileTags.HISTORY)) { Text(stringResource(R.string.stats_history_open)) } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = true }, modifier = Modifier.testTag(ProfileTags.EDIT)) { Text(stringResource(R.string.mp_profile_edit)) }
                    TextButton(onClick = onSignOut, modifier = Modifier.testTag(ProfileTags.SIGN_OUT)) { Text(stringResource(R.string.mp_sign_out)) }
                }
            }
        }
    }
}
