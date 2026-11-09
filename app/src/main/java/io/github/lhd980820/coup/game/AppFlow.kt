package io.github.lhd980820.coup.game

import androidx.activity.compose.BackHandler
import android.widget.Toast
import io.github.lhd980820.coup.game.sound.SoundPlayer
import io.github.lhd980820.coup.game.sound.SoundLevel
import io.github.lhd980820.coup.game.sound.SoundCue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import android.provider.Settings
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

internal enum class Screen { HOME, SETUP, TUTORIALS, MULTI, RANKING, HISTORY, SETTINGS }

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
    /** 앱 설정(애니메이션 속도·효과음). null이면 설정 화면이 없고 연출과 소리는 꺼진다. */
    settings: AppSettings? = null,
    /** 효과음을 내는 쪽. null이면 소리가 없다. */
    sounds: SoundPlayer? = null,
    /** 홈에서 뒤로 가기를 처음 눌렀을 때의 안내("한 번 더 누르면 종료"). null이면 토스트. */
    onExitHint: (() -> Unit)? = null,
    /** 현재 시각(밀리초). 홈에서 뒤로 가기 두 번의 간격을 잴 때 쓴다. 테스트가 바꾼다. */
    now: () -> Long = System::currentTimeMillis,
) {
    // 연출 속도는 설정과 기기의 "애니메이션 없음"을 함께 본다
    val systemScale = rememberSystemAnimatorScale()
    val motion = motionFor(settings, systemScale)
    val level = settings?.sound ?: SoundLevel.OFF
    val playSound = remember(sounds, level) { soundSink(sounds, level) }
    CompositionLocalProvider(LocalMotion provides motion, LocalSounds provides playSound) {
        val exitGuard = remember(now) { DoubleBackExit(now) }
        val context = LocalContext.current
        val hintText = stringResource(R.string.back_exit_hint) // 설정(언어)이 바뀌면 따라 바뀌도록 compose 방식으로 읽는다
        val hint = onExitHint ?: { Toast.makeText(context, hintText, Toast.LENGTH_SHORT).show() }
        AppScreens(viewModel, { if (exitGuard.onBack()) onExit() else hint() }, modifier, shell, google, settings)
    }
}

/** 화면이 소리를 낼 때 부르는 함수: 플레이어가 없거나 소리를 껐으면 아무것도 하지 않고, 아니면 설정한 크기로 낸다. */
internal fun soundSink(player: SoundPlayer?, level: SoundLevel): (SoundCue) -> Unit =
    if (player == null || level == SoundLevel.OFF) { _ -> } else { cue -> player.play(cue, level.volume) }

/** 기기의 전환 애니메이션 배율(개발자 옵션/접근성의 "애니메이션 없음"이면 0). 읽을 수 없으면 1. */
@Composable
private fun rememberSystemAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
}

@Composable
private fun AppScreens(
    viewModel: GameViewModel,
    onExit: () -> Unit,
    modifier: Modifier,
    shell: ShellViewModel?,
    google: GoogleTokenSource?,
    settings: AppSettings?,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val controller: GameController? = viewModel.controller

    // 뒤로 가기 규칙(백로그 3). 게임이 없을 때: 홈이 아닌 화면은 홈으로, 홈에서는 두 번 눌러야 앱이 꺼진다.
    // 멀티플레이는 안쪽 화면(로그인 모드, 방 만들기, 대기실)이 먼저 받는다 — 나중에 등록된 BackHandler가 우선하기 때문이다.
    BackHandler(enabled = controller == null) { if (screen == Screen.HOME) onExit() else screen = Screen.HOME }
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
                onSettings = settings?.let { { screen = Screen.SETTINGS } },
            )
            Screen.SETTINGS -> if (settings != null) SettingsScreen(settings, onBack = { screen = Screen.HOME }, modifier = modifier) else screen = Screen.HOME
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
            Screen.SETUP -> SinglePlayerSetupScreen(
                onStart = viewModel::start,
                modifier = modifier,
                remembered = settings?.lastSetup,
                onRemember = { settings?.rememberSetup(it) },
            )
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
        // 진행 중인 판에서 뒤로 가기는 바로 나가지 않고 한 번 묻는다(실수로 판을 잃지 않게). 결과가 나온 뒤에는 결과 대화상자가 받는다.
        var confirmLeave by rememberSaveable { mutableStateOf(false) }
        BackHandler(enabled = current.result == null) { confirmLeave = true }
        GameScreen(
            state = current,
            messages = controller.messages,
            actions = viewModel,
            // 결과의 "나가기"는 앱을 끄지 않고 홈으로 간다
            onExit = { viewModel.quit(); screen = Screen.HOME },
            onPlayAgain = { viewModel.quit(); screen = Screen.SETUP }, // 설정으로 돌아가 다시 시작할 수 있다
            modifier = modifier,
        )
        if (confirmLeave && current.result == null) {
            ConfirmLeaveDialog(
                text = R.string.back_leave_game,
                confirm = R.string.back_confirm_leave,
                stay = R.string.back_stay_continue,
                onConfirm = { confirmLeave = false; viewModel.quit(); screen = Screen.SETUP },
                onStay = { confirmLeave = false },
            )
        }
    }
}
