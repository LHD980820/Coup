package io.github.lhd980820.coup.multiplayer

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.lhd980820.coup.game.SinglePlayerFlow
import io.github.lhd980820.coup.game.ShellTags
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource
import io.github.lhd980820.coup.ui.theme.CoupTheme
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment

/** 멀티플레이 화면 테스트의 공통 틀: 가짜 서버 위에서 실제 화면을 띄우고 버튼·입력으로 조작한다. */
abstract class MpScreenTest {
    @get:Rule
    val compose = createComposeRule()

    internal lateinit var fx: MpFixture

    @Before
    fun setUpFixture() {
        fx = MpFixture()
    }

    @After
    fun tearDownFixture() {
        fx.close()
    }

    internal fun show(google: GoogleTokenSource? = null, sharer: CodeSharer? = null) {
        compose.setContent {
            CoupTheme {
                CompositionLocalProvider(LocalCodeSharer provides sharer) { SinglePlayerFlow(fx.game, onExit = {}, shell = fx.shell, google = google) }
            }
        }
    }

    protected fun string(@StringRes id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    protected fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    protected fun waitFor(tag: String, timeoutMillis: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMillis) { exists(tag) }
            compose.waitForIdle() // 노드가 생긴 직후에는 배치가 끝나지 않아 "보이지 않음"으로 읽힐 수 있다
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            error("'$tag'가 화면에 나타나지 않았다. 현재 화면 태그=${tags()}")
        }
    }

    protected fun waitGone(tag: String, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis) { !exists(tag) }
    }

    protected fun tags(): List<String> = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag") { true })
        .fetchSemanticsNodes().mapNotNull { n -> n.config.firstOrNull { it.key == androidx.compose.ui.semantics.SemanticsProperties.TestTag }?.value as? String }

    /** 스크롤 영역 안에 있으면 보이게 스크롤한다(영역 밖의 고정 컨트롤이면 그대로). */
    protected fun node(tag: String): SemanticsNodeInteraction {
        val n = compose.onNodeWithTag(tag)
        return try {
            n.performScrollTo()
        } catch (e: AssertionError) {
            n
        }
    }

    protected fun click(tag: String) {
        node(tag).assertIsDisplayed().performClick()
    }

    protected fun type(tag: String, text: String) {
        node(tag).performTextInput(text)
    }

    protected fun assertEnabled(tag: String, enabled: Boolean) {
        if (enabled) node(tag).assertIsEnabled() else node(tag).assertIsNotEnabled()
    }

    /** 비동기로 바뀌는 문구가 나타나 레이아웃까지 끝난 뒤 화면에 보이는지 확인한다(문구가 바뀐 직후의 옛 크기를 보지 않도록). */
    protected fun assertShown(text: String, timeoutMillis: Long = 10_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    protected fun assertText(tag: String, text: String) {
        node(tag).assertIsDisplayed().assertTextEquals(text)
    }

    /** 홈 -> 멀티플레이 -> (로그인 화면). */
    protected fun openMultiplayer() {
        waitFor(ShellTags.HOME_MULTI)
        click(ShellTags.HOME_MULTI)
    }

    /** 로그인 화면에서 입력하고 제출한다. */
    protected fun signInWith(email: String, password: String) {
        waitFor(LoginTags.EMAIL)
        type(LoginTags.EMAIL, email)
        type(LoginTags.PASSWORD, password)
        click(LoginTags.SUBMIT)
    }

    /** 계정을 만들어 로그인하고 로비가 열릴 때까지. */
    internal fun signInAndOpenLobby(email: String = "kim@test.co", password: String = "secret-123", sharer: CodeSharer? = null) {
        fx.auth.addAccount(email, password)
        show(sharer = sharer)
        openMultiplayer()
        signInWith(email, password)
        waitFor(LobbyTags.CREATE)
    }

    /** 방 화면을 연 채로 상대(다른 기기)가 방에 있는지 등 서버 상태를 기다린다. */
    protected fun until(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis) { condition() }
    }
}
