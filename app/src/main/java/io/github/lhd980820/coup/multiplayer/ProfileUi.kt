package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.SecondaryButton
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Avatar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
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
 *
 * 모습: 얼굴(이름 첫 글자), 닉네임과 판·승 수, 오른쪽에 별과 레이팅. 아래 줄에 랭킹·내 전적(그림과 낱말), 구석에 고치기·로그아웃(그림).
 * 스크린리더에는 머리가 "kim · 레이팅 1048"로 읽힌다.
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
    val line = stringResource(R.string.mp_profile_line, profile.nickname, profile.rating)

    Column(modifier.fillMaxWidth().testTag(ProfileTags.CARD).panel().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = line },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(profile.nickname, Gold, size = 42.dp, ring = GoldBright)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Parchment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.mp_profile_stats, profile.plays, profile.wins), style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
            }
            Row(
                Modifier.panel(CircleShape, top = PanelTop, bottom = Panel, line = Gold.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(CoupIcons.Star, contentDescription = null, tint = GoldBright, modifier = Modifier.size(14.dp))
                Text(profile.rating.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = GoldBright)
            }
        }
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text(stringResource(R.string.mp_nickname)) },
                leadingIcon = { Icon(CoupIcons.Pencil, contentDescription = null, tint = Gold) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(it)) } },
                modifier = Modifier.fillMaxWidth().testTag(ProfileTags.NICKNAME),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    stringResource(R.string.mp_save),
                    {
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
                    Modifier.weight(1f).testTag(ProfileTags.SAVE), enabled = !busy, icon = CoupIcons.Check,
                )
                SecondaryButton(
                    stringResource(R.string.mp_cancel), { editing = false; error = null },
                    Modifier.weight(1f).testTag(ProfileTags.CANCEL), line = Hairline, content = ParchmentDim,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRanking?.let { ProfileLink(CoupIcons.Trophy, stringResource(R.string.stats_ranking_open), it, ProfileTags.RANKING) }
                onHistory?.let { ProfileLink(CoupIcons.Scroll, stringResource(R.string.stats_history_open), it, ProfileTags.HISTORY) }
                Spacer(Modifier.weight(1f))
                BarButton(CoupIcons.Pencil, stringResource(R.string.mp_profile_edit), { editing = true }, ProfileTags.EDIT, tint = ParchmentDim)
                BarButton(CoupIcons.Exit, stringResource(R.string.mp_sign_out), onSignOut, ProfileTags.SIGN_OUT, tint = ParchmentDim)
            }
        }
    }
}

/** 프로필 카드의 바로가기: 그림과 짧은 낱말의 알약 버튼(누르는 영역 48dp). */
@Composable
private fun ProfileLink(icon: ImageVector, label: String, onClick: () -> Unit, tag: String) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.panel(CircleShape, top = PanelTop, bottom = Panel, line = Gold.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Parchment)
        }
    }
}
