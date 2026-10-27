package io.github.lhd980820.coup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lhd980820.coup.game.GameViewModel
import io.github.lhd980820.coup.game.PrefsTutorialStore
import io.github.lhd980820.coup.game.AppFlow
import io.github.lhd980820.coup.game.AppSettings
import io.github.lhd980820.coup.game.PrefsSettingsStore
import io.github.lhd980820.coup.game.sound.SoundPoolPlayer
import io.github.lhd980820.coup.multiplayer.AndroidCodeSharer
import io.github.lhd980820.coup.multiplayer.CredentialManagerGoogleTokenSource
import io.github.lhd980820.coup.multiplayer.LocalCodeSharer
import io.github.lhd980820.coup.multiplayer.FirebaseBackend
import io.github.lhd980820.coup.multiplayer.PrefsActiveRoomStore
import io.github.lhd980820.coup.multiplayer.ShellViewModel
import io.github.lhd980820.coup.ui.theme.CoupTheme

/** 앱의 유일한 Activity(설계 §10.2). 홈·싱글플레이·튜토리얼·로그인·로비·대기실·게임·랭킹·전적이 모두 이 안의 Compose 화면이다. */
class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels {
        viewModelFactory { initializer { GameViewModel(tutorialStore = PrefsTutorialStore(applicationContext)) } }
    }

    private val shell: ShellViewModel by viewModels {
        viewModelFactory { initializer { ShellViewModel(FirebaseBackend(appVersion()), viewModel, activeRooms = PrefsActiveRoomStore(applicationContext)) } }
    }

    /** 구글 로그인(Credential Manager). 웹 클라이언트 ID는 google-services 플러그인이 만든 리소스다. */
    private val google by lazy { CredentialManagerGoogleTokenSource(this, getString(R.string.default_web_client_id)) }

    private val settings by lazy { AppSettings(PrefsSettingsStore(applicationContext)) }

    private var sounds: SoundPoolPlayer? = null

    private val codeSharer by lazy { AndroidCodeSharer(this) }

    private fun appVersion(): Int = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt()

    override fun onDestroy() {
        sounds?.release()
        sounds = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sounds = SoundPoolPlayer(applicationContext)
        setContent {
            CoupTheme {
                CompositionLocalProvider(LocalCodeSharer provides codeSharer) {
                    AppFlow(viewModel, onExit = ::finish, modifier = Modifier.safeDrawingPadding(), shell = shell, google = google, settings = settings, sounds = sounds)
                }
            }
        }
    }
}
