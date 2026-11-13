package io.github.lhd980820.coup

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.game.GameActions
import io.github.lhd980820.coup.game.GameScreen
import io.github.lhd980820.coup.game.GameTags
import io.github.lhd980820.coup.game.HomeScreen
import io.github.lhd980820.coup.game.LocalNowMillis
import io.github.lhd980820.coup.game.SinglePlayerSetupScreen
import io.github.lhd980820.coup.presentation.ActionButtonUi
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.BlockButtonUi
import io.github.lhd980820.coup.presentation.CardPurpose
import io.github.lhd980820.coup.presentation.CardUi
import io.github.lhd980820.coup.presentation.DecisionUi
import io.github.lhd980820.coup.presentation.DisabledReason
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry
import io.github.lhd980820.coup.presentation.MyUi
import io.github.lhd980820.coup.presentation.OpponentStatus
import io.github.lhd980820.coup.presentation.OpponentUi
import io.github.lhd980820.coup.presentation.ResultUi
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.ui.theme.CoupTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 화면 스크린샷 모음. 기기 없이 실제 렌더링으로 주요 화면의 PNG를 만든다 — 디자인을 고칠 때 눈으로 확인하는 용도다.
 * 평소 테스트에서는 건너뛰고, `./gradlew :app:testDebugUnitTest --tests '*ScreenshotGalleryTest*' -Pscreenshots=` 로 돌리면
 * `app/build/screenshots/`에 쌓인다(`-Pscreenshots=<폴더>`로 다른 곳에 쓸 수 있다).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-w360dp-h740dp-xhdpi")
class ScreenshotGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir: File? = System.getProperty("coup.screenshots")?.let(::File)

    @Before
    fun onlyOnDemand() {
        assumeTrue("스크린샷은 -Pscreenshots 로 요청할 때만 만든다", dir != null)
        dir!!.mkdirs()
    }

    private val now = 1_000_000L
    private val me = PlayerId("me")
    private val a1 = PlayerId("a1")
    private val a2 = PlayerId("a2")
    private val a3 = PlayerId("a3")

    private fun role(id: String) = RoleId(id)
    private fun action(id: String, cost: Int = 0, enabled: Boolean = true, bluff: Boolean = false, claim: String? = null, reason: DisabledReason? = null) =
        ActionButtonUi(ActionId(id), cost, enabled, reason, bluff, setOfNotNull(claim?.let(::RoleId)), needsTarget = id in setOf("coup", "assassinate", "steal"), validTargets = listOf(a1, a2), forced = false)

    private val actions = DecisionUi.Actions(
        listOf(
            action("income"),
            action("foreign_aid"),
            action("coup", cost = 7, enabled = false, reason = DisabledReason.INSUFFICIENT_COINS),
            action("tax", claim = "duke"),
            action("assassinate", cost = 3, bluff = true, claim = "assassin"),
            action("steal", bluff = true, claim = "captain"),
            action("exchange", bluff = true, claim = "ambassador"),
        ),
    )

    private val log = listOf(
        LogEntry.ActionDeclared(a1, ActionId("tax"), null, setOf(role("duke"))),
        LogEntry.ActionDeclared(a2, ActionId("steal"), me, setOf(role("captain"))),
        LogEntry.Blocked(me, role("ambassador"), ActionId("steal")),
        LogEntry.Challenged(a2, me, setOf(role("ambassador"))),
        LogEntry.Revealed(me, role("ambassador"), proven = true),
        LogEntry.InfluenceLost(a2, role("contessa"), LossReason.ChallengeLost),
        LogEntry.ActionDeclared(a3, ActionId("income"), null, emptySet()),
    )

    private fun state(
        banner: Banner = Banner.YourTurn(false),
        decision: DecisionUi? = actions,
        deadline: Long? = now + 22_000,
        opponents: List<OpponentUi> = listOf(
            OpponentUi(a1, "AI 1", 5, 2, emptyList(), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a2, "AI 2", 1, 1, listOf(role("contessa")), true, OpponentStatus.IDLE, emptySet(), null, false),
            OpponentUi(a3, "AI 3", 0, 0, listOf(role("captain"), role("assassin")), false, OpponentStatus.ELIMINATED, emptySet(), null, false),
        ),
        hand: List<CardUi> = listOf(CardUi(CardId(1), role("duke"), false), CardUi(CardId(2), role("ambassador"), false)),
        result: ResultUi? = null,
        coins: Int = 4,
    ) = GameUiState(
        gameId = "g", turnNumber = 7, banner = banner, opponents = opponents,
        me = MyUi(me, "나", coins, hand, true, OpponentStatus.IDLE),
        deckSize = 9, decision = decision, myDeadline = deadline, connection = ConnectionState.CONNECTED, result = result, busy = false, log = log,
    )

    private object NoActions : GameActions {
        override fun chooseAction(id: ActionId) = Unit
        override fun chooseTarget(id: PlayerId) = Unit
        override fun cancelTarget() = Unit
        override fun pass() = Unit
        override fun challenge() = Unit
        override fun block(role: RoleId) = Unit
        override fun selectCard(id: CardId) = Unit
        override fun confirmCard() = Unit
        override fun toggleExchange(id: CardId) = Unit
        override fun confirmExchange() = Unit
        override fun concede() = Unit
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalNowMillis provides { now }) { CoupTheme { content() } } }
        compose.waitForIdle()
    }

    private fun game(state: GameUiState) = show { GameScreen(state, emptyFlow(), NoActions, onExit = {}, onPlayAgain = {}) }

    private fun save(name: String, bitmap: Bitmap) {
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 화면 전체를 저장한다. 대화상자가 떠 있으면 대화상자도 따로 저장한다(별도 창이라 화면 캡처에 안 들어간다). */
    private fun shoot(name: String) {
        save(name, compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
        val dialogs = compose.onAllNodes(isDialog())
        if (dialogs.fetchSemanticsNodes().isNotEmpty()) save("${name}_dialog", dialogs[0].captureToImage().asAndroidBitmap())
    }

    @Test
    fun `게임 - 내 차례`() {
        game(state())
        shoot("game_my_turn")
    }

    @Test
    fun `게임 - 응답`() {
        val respond = DecisionUi.Respond(WindowKind.ACTION, ActionId("assassinate"), a1, me, a1, setOf(role("assassin")), true, listOf(BlockButtonUi(role("contessa"), bluff = true)))
        game(
            state(
                banner = Banner.ActionDeclared(a1, ActionId("assassinate"), me, setOf(role("assassin")), setOf(me)),
                decision = respond,
                deadline = now + 9_000,
                opponents = listOf(
                    OpponentUi(a1, "AI 1", 2, 2, emptyList(), true, OpponentStatus.ACTING, setOf(role("assassin")), null, false),
                    OpponentUi(a2, "AI 2", 1, 1, listOf(role("contessa")), true, OpponentStatus.RESPONDED, emptySet(), null, false),
                    OpponentUi(a3, "AI 3", 0, 0, listOf(role("captain"), role("assassin")), false, OpponentStatus.ELIMINATED, emptySet(), null, false),
                ),
            ),
        )
        shoot("game_respond")
    }

    @Test
    fun `게임 - 상대 차례 6인`() {
        val ops = (1..5).map { i ->
            OpponentUi(PlayerId("p$i"), "AI $i", i * 2 - 1, if (i == 4) 1 else 2, if (i == 4) listOf(role("duke")) else emptyList(), true, if (i == 2) OpponentStatus.ACTING else OpponentStatus.IDLE, emptySet(), if (i == 2) now + 14_000 else null, false)
        }
        game(state(banner = Banner.WaitingFor(PlayerId("p2")), decision = null, deadline = null, opponents = ops))
        shoot("game_waiting_6p")
    }

    @Test
    fun `게임 - 잃을 카드 고르기`() {
        val cards = listOf(CardUi(CardId(1), role("duke"), false, selected = true), CardUi(CardId(2), role("ambassador"), false))
        game(state(banner = Banner.LosingInfluence(me, LossReason.ActionEffect(ActionId("coup"))), decision = DecisionUi.PickCard(CardPurpose.LOSE, cards, emptySet(), LossReason.ActionEffect(ActionId("coup")), CardId(1)), deadline = now + 4_000))
        shoot("game_pick_card")
    }

    @Test
    fun `게임 - 대상 고르기`() {
        game(state(decision = DecisionUi.PickTarget(ActionId("steal"), listOf(a1, a2))))
        shoot("game_pick_target")
    }

    @Test
    fun `게임 - 결과`() {
        game(state(banner = Banner.GameOver(me, listOf(me, a1, a2, a3)), decision = null, deadline = null, result = ResultUi(me, listOf(me, a1, a2, a3), iWon = true, myRank = 1)))
        shoot("game_result")
    }

    @Test
    fun `게임 - 카드 확대`() {
        game(state())
        compose.onNodeWithTag(GameTags.card(1)).performClick()
        compose.waitForIdle()
        shoot("game_zoom")
    }

    @Test
    fun `홈`() {
        show { HomeScreen(onSingle = {}, onTutorial = {}, onMultiplayer = {}, onSettings = {}) }
        shoot("home")
    }

    @Test
    fun `튜토리얼 목록`() {
        val lessons = io.github.lhd980820.coup.presentation.tutorial.TutorialLessons.all
        show { io.github.lhd980820.coup.game.TutorialListScreen(lessons, completed = setOf(lessons.first().id), onStart = {}, onBack = {}) }
        shoot("tutorial_list")
    }

    @Test
    fun `설정`() {
        show { io.github.lhd980820.coup.game.SettingsScreen(io.github.lhd980820.coup.game.AppSettings(io.github.lhd980820.coup.game.InMemorySettingsStore(io.github.lhd980820.coup.game.MotionSpeed.NORMAL)), onBack = {}) }
        shoot("settings")
    }

    @Test
    fun `게임 - 튜토리얼 안내`() {
        show {
            GameScreen(
                state(), emptyFlow(), NoActions, onExit = {}, onPlayAgain = {},
                guide = io.github.lhd980820.coup.game.GuideUi("세금은 공작의 행동입니다. 공작이 없어도 있다고 주장할 수 있어요. 세금을 눌러 보세요.", 2, 5, io.github.lhd980820.coup.presentation.tutorial.TutorialExpect.Action(ActionId("tax")), 0),
            )
        }
        shoot("game_tutorial")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ko-w320dp-h568dp-xhdpi")
    fun `게임 - 작은 화면`() {
        game(state())
        shoot("game_small")
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-w360dp-h740dp-xhdpi")
    fun `게임 - 영어`() {
        game(state())
        shoot("game_english")
    }

    @Test
    fun `싱글플레이 설정`() {
        show { SinglePlayerSetupScreen(onStart = {}) }
        shoot("setup")
    }
}
