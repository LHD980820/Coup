package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.game.GameScreen
import io.github.lhd980820.coup.game.GameViewModel
import io.github.lhd980820.coup.remote.Settlement
import io.github.lhd980820.coup.shell.account.AccountState
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource

internal object FlowTags {
    const val ACCOUNT_LOADING = "mp_account_loading"
    const val ACCOUNT_FAILED = "mp_account_failed"
    const val ACCOUNT_RETRY = "mp_account_retry"
    const val ACCOUNT_BACK = "mp_account_back"
    const val RATING = "mp_result_rating"
}

/**
 * 홈에서 들어오는 멀티플레이 영역: 로그인 -> 계정 준비 -> 로비 -> 방 만들기/대기실. 게임 화면은 [MultiplayerGame]이 맡는다.
 * 어느 화면을 보일지는 로그인 상태와 [ShellViewModel]의 상태가 정한다.
 */
@Composable
internal fun MultiplayerFlow(shell: ShellViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, google: GoogleTokenSource? = null) {
    val user by shell.user.collectAsState()
    if (user == null) {
        LoginScreen(shell.authForm, onBack = onBack, modifier = modifier, google = google)
        return
    }
    when (shell.accountState) {
        AccountState.Loading -> CenteredMessage(R.string.mp_account_loading, FlowTags.ACCOUNT_LOADING, onBack = null, onRetry = null, modifier = modifier)
        AccountState.Failed -> CenteredMessage(R.string.mp_account_failed, FlowTags.ACCOUNT_FAILED, onBack = onBack, onRetry = shell::retryAccount, modifier = modifier)
        AccountState.Ready -> {
            val lobby = shell.lobby
            if (lobby == null) {
                LaunchedEffect(shell.profile?.uid) { shell.openLobby() } // 프로필이 늦게 오면 다시 시도한다
                return
            }
            when (shell.screen) {
                MultiScreen.LOBBY -> LobbyScreen(
                    lobby = lobby,
                    notice = shell.notice,
                    onEnterRoom = { shell.enterRoom(it) },
                    onCreate = shell::openCreate,
                    onBack = onBack,
                    modifier = modifier,
                )
                MultiScreen.CREATE -> CreateRoomScreen(
                    lobby = lobby,
                    defaultTitle = stringResource(R.string.mp_create_default_name, shell.profile?.nickname.orEmpty()),
                    onEnterRoom = { shell.enterRoom(it) },
                    onBack = shell::closeCreate,
                    modifier = modifier,
                )
                MultiScreen.ROOM -> shell.room?.let { WaitingRoomScreen(it, onEvent = shell::onRoomEvent, modifier = modifier) }
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: Int, tag: String, onBack: (() -> Unit)?, onRetry: (() -> Unit)?, modifier: Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag))
            onRetry?.let { Button(onClick = it, modifier = Modifier.testTag(FlowTags.ACCOUNT_RETRY)) { Text(stringResource(R.string.mp_retry)) } }
            onBack?.let { TextButton(onClick = it, modifier = Modifier.testTag(FlowTags.ACCOUNT_BACK)) { Text(stringResource(R.string.mp_back)) } }
        }
    }
}

/** 멀티플레이 게임 화면. 싱글플레이와 같은 [GameScreen]에 결과의 레이팅 변화를 덧붙이고, 나가면 대기실로 돌아간다. */
@Composable
internal fun MultiplayerGame(shell: ShellViewModel, game: GameViewModel, modifier: Modifier = Modifier) {
    val controller = game.controller ?: return
    val state by controller.state.collectAsState()
    val current = state ?: return
    GameScreen(
        state = current,
        messages = controller.messages,
        actions = game,
        onExit = shell::finishGame,
        onPlayAgain = shell::finishGame,
        modifier = modifier,
        resultExtra = { RatingNote(shell) },
        playAgainLabel = R.string.mp_result_to_room,
        backConcedes = true,
    )
}

/** 결과 대화상자의 레이팅 줄: 서버가 확인하는 중 -> 변화 / 레이팅 제외 / 무효. */
@Composable
private fun RatingNote(shell: ShellViewModel) {
    val text = when (val s = shell.settlement) {
        is Settlement.Applied -> stringResource(R.string.mp_result_rating, s.rating ?: shell.profile?.rating ?: 0, s.record.delta)
        Settlement.Unrated -> stringResource(R.string.mp_result_rating_unrated)
        Settlement.Rejected -> stringResource(R.string.mp_result_rating_rejected)
        Settlement.NotApplied -> return
        Settlement.Verifying, null -> stringResource(R.string.mp_result_rating_pending)
    }
    Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(FlowTags.RATING), style = MaterialTheme.typography.bodyMedium)
}
