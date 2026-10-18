package io.github.lhd980820.coup.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.ui.theme.CoupTheme

/**
 * 새 UI 셸의 싱글플레이 진입점(설계 §10.2). 설정 -> 게임 -> 결과. 기존 Activity들은 그대로 두고 단계적으로 대체한다(Phase 5).
 */
class SinglePlayerActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoupTheme {
                SinglePlayerFlow(viewModel, onExit = ::finish, modifier = Modifier.safeDrawingPadding())
            }
        }
    }
}

@Composable
internal fun SinglePlayerFlow(viewModel: GameViewModel, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val controller: GameController? = viewModel.controller
    if (controller == null) {
        SinglePlayerSetupScreen(onStart = viewModel::start, modifier = modifier)
    } else {
        val state by controller.state.collectAsState()
        val current = state
        if (current != null) {
            GameScreen(
                state = current,
                messages = controller.messages,
                actions = viewModel,
                onExit = { viewModel.quit(); onExit() },
                onPlayAgain = { viewModel.quit() }, // 설정 화면으로 돌아가 같은 설정으로 다시 시작할 수 있다
                modifier = modifier,
            )
        }
    }
}
