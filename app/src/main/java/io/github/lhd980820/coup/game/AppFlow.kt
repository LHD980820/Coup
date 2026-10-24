package io.github.lhd980820.coup.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.multiplayer.HistoryScreen
import io.github.lhd980820.coup.multiplayer.MultiplayerFlow
import io.github.lhd980820.coup.multiplayer.MultiplayerGame
import io.github.lhd980820.coup.multiplayer.RankingScreen
import io.github.lhd980820.coup.multiplayer.ShellViewModel
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource
import io.github.lhd980820.coup.shell.stats.HistoryModel
import io.github.lhd980820.coup.shell.stats.RankingModel

private enum class Screen { HOME, SETUP, TUTORIALS, MULTI, RANKING, HISTORY }

/**
 * 앱의 화면 흐름: 홈 -> (AI와 대전: 설정 -> 게임) | (튜토리얼: 목록 -> 과정). 게임 중이면(컨트롤러가 있으면) 게임 화면이 우선한다.
 */
@Composable
internal fun AppFlow(
    viewModel: GameViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    shell: ShellViewModel? = null,
    google: GoogleTokenSource? = null,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val controller: GameController? = viewModel.controller
    val you = stringResource(R.string.game_you)
    val tutor = stringResource(R.string.game_tutor_name)

    // 멀티플레이 게임 중이면 그 게임 화면이 우선한다(나가면 대기실로)
    if (shell != null && shell.multiGame != null && controller != null) {
        MultiplayerGame(shell, viewModel, modifier)
        return
    }

    if (controller == null) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                onSingle = { screen = Screen.SETUP },
                onTutorial = { screen = Screen.TUTORIALS },
                modifier = modifier,
                onMultiplayer = shell?.let { { screen = Screen.MULTI } },
                profile = shell?.profile,
                onSaveNickname = { shell?.updateNickname(it) },
                onSignOut = { shell?.signOut() },
                onRanking = shell?.let { { screen = Screen.RANKING } },
                onHistory = shell?.let { { screen = Screen.HISTORY } },
            )
            Screen.RANKING -> {
                val services = shell?.services
                if (services != null) {
                    val model = remember(services) { RankingModel(services.users, services.user.uid) }
                    RankingScreen(model, services.user.uid, onBack = { screen = Screen.HOME }, modifier = modifier)
                } else {
                    screen = Screen.HOME // 로그아웃됐다
                }
            }
            Screen.HISTORY -> {
                val services = shell?.services
                if (services != null) {
                    val model = remember(services) { HistoryModel(services.users) }
                    HistoryScreen(model, onBack = { screen = Screen.HOME }, modifier = modifier)
                } else {
                    screen = Screen.HOME
                }
            }
            Screen.MULTI -> if (shell != null) MultiplayerFlow(shell, onBack = { screen = Screen.HOME }, modifier = modifier, google = google) else screen = Screen.HOME
            Screen.SETUP -> SinglePlayerSetupScreen(onStart = viewModel::start, modifier = modifier)
            Screen.TUTORIALS -> TutorialListScreen(
                lessons = TutorialLessons.all,
                completed = viewModel.completedLessons,
                onStart = { viewModel.startTutorial(it, you, tutor) },
                onBack = { screen = Screen.HOME },
                modifier = modifier,
            )
        }
        return
    }

    val guided = viewModel.tutorial
    val lesson = viewModel.tutorialLesson
    if (guided != null && lesson != null) {
        TutorialGame(
            controller = controller,
            guided = guided,
            lesson = lesson,
            actions = viewModel,
            onComplete = viewModel::completeTutorial,
            onBack = { viewModel.quit(); screen = Screen.TUTORIALS },
            onNext = { next -> viewModel.quit(); viewModel.startTutorial(next, you, tutor) },
            modifier = modifier,
        )
        return
    }

    val state by controller.state.collectAsState()
    val current = state
    if (current != null) {
        GameScreen(
            state = current,
            messages = controller.messages,
            actions = viewModel,
            onExit = { viewModel.quit(); onExit() },
            onPlayAgain = { viewModel.quit(); screen = Screen.SETUP }, // 설정으로 돌아가 다시 시작할 수 있다
            modifier = modifier,
        )
    }
}
