package io.github.lhd980820.coup.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.ui.theme.CoupTheme

/**
 * 새 UI 셸의 싱글플레이 진입점(설계 §10.2). 설정 -> 게임 -> 결과. 기존 Activity들은 그대로 두고 단계적으로 대체한다(Phase 5).
 */
class SinglePlayerActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels {
        viewModelFactory { initializer { GameViewModel(tutorialStore = PrefsTutorialStore(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoupTheme {
                SinglePlayerFlow(viewModel, onExit = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }
}

private enum class Screen { HOME, SETUP, TUTORIALS }

/**
 * 새 UI 셸의 화면 흐름: 홈 -> (AI와 대전: 설정 -> 게임) | (튜토리얼: 목록 -> 과정). 게임 중이면(컨트롤러가 있으면) 게임 화면이 우선한다.
 * 정식 내비게이션(Navigation Compose)은 화면이 늘어나는 Phase 5에서 도입한다.
 */
@Composable
internal fun SinglePlayerFlow(viewModel: GameViewModel, onExit: () -> Unit, modifier: Modifier = Modifier) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val controller: GameController? = viewModel.controller
    val you = stringResource(R.string.game_you)
    val tutor = stringResource(R.string.game_tutor_name)

    if (controller == null) {
        when (screen) {
            Screen.HOME -> HomeScreen(onSingle = { screen = Screen.SETUP }, onTutorial = { screen = Screen.TUTORIALS }, modifier = modifier)
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
