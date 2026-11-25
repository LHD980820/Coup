package io.github.lhd980820.coup.multiplayer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.remote.WriteOp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 멀티플레이 화면 스크린샷 모음(docs/DESIGN.md "멀티플레이 화면"). 가짜 서버 위에서 실제 화면을 띄워 PNG를 만든다.
 * 평소 테스트에서는 건너뛰고 `-Pscreenshots`로 요청할 때만 만든다([io.github.lhd980820.coup.ScreenshotGalleryTest]와 같은 폴더).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-w360dp-h740dp-xhdpi")
class MpGalleryTest : MpScreenTest() {
    private val dir: File? = System.getProperty("coup.screenshots")?.let(::File)

    @Before
    fun onlyOnDemand() {
        assumeTrue("스크린샷은 -Pscreenshots 로 요청할 때만 만든다", dir != null)
        dir!!.mkdirs()
    }

    private fun save(name: String, bitmap: Bitmap) {
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        save(name, compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap())
        val dialogs = compose.onAllNodes(isDialog())
        if (dialogs.fetchSemanticsNodes().isNotEmpty()) save("${name}_dialog", dialogs[0].captureToImage().asAndroidBitmap())
    }

    private fun seedProfile(uid: String, nickname: String, rating: Int, plays: Int, wins: Int) = runBlocking {
        fx.fs.admin().write(
            listOf(
                WriteOp.Set(
                    "users/$uid",
                    buildJsonObject {
                        put("nickname", nickname); put("rating", rating); put("plays", plays); put("wins", wins)
                        put("createdAt", 1L); put("migratedFromLegacy", false)
                    },
                ),
            ),
        )
    }

    private fun seedResult(uid: String, gameId: String, rank: Int, delta: Int, players: Int, finishedAt: Long) = runBlocking {
        fx.fs.admin().write(
            listOf(
                WriteOp.Set(
                    "users/$uid/appliedResults/$gameId",
                    buildJsonObject { put("rank", rank); put("delta", delta); put("playerCount", players); put("finishedAt", finishedAt); put("appliedAt", finishedAt + 1) },
                ),
            ),
        )
    }

    @Test
    fun `로그인`() {
        show()
        openMultiplayer()
        waitFor(LoginTags.EMAIL)
        shoot("mp_login")
        click(LoginTags.GO_REGISTER)
        waitFor(LoginTags.CONFIRM)
        shoot("mp_register")
    }

    @Test
    fun `홈 - 내 프로필`() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        seedProfile("kim", "kim", 1048, 12, 5)
        click(LobbyTags.BACK)
        waitFor(ProfileTags.CARD)
        shoot("mp_home_profile")
    }

    @Test
    fun `로비`() {
        val a = fx.remote("lee@test.co", "이도윤")
        val b = fx.remote("park@test.co", "박서연")
        runBlocking {
            a.signUp(); b.signUp()
            a.createRoom("금요일 한 판", max = 4)
            b.createRoom("초보 환영", max = 6)
        }
        signInAndOpenLobby()
        click(LobbyTags.REFRESH)
        compose.waitUntil(10_000) { tags().count { it.startsWith("lobby_room_") } >= 2 }
        shoot("mp_lobby")
    }

    @Test
    fun `로비 - 빈 방 목록`() {
        signInAndOpenLobby()
        waitFor(LobbyTags.EMPTY)
        shoot("mp_lobby_empty")
    }

    @Test
    fun `방 만들기`() {
        signInAndOpenLobby()
        click(LobbyTags.CREATE)
        waitFor(CreateTags.TITLE)
        shoot("mp_create")
    }

    @Test
    fun `대기실 - 방장`() {
        signInAndOpenLobby(sharer = FakeCodeSharer())
        click(LobbyTags.CREATE)
        waitFor(CreateTags.TITLE)
        click(CreateTags.PRIVATE)
        click(CreateTags.SUBMIT)
        waitFor(WaitingTags.ADD_BOT_NORMAL)
        click(WaitingTags.ADD_BOT_NORMAL)
        compose.waitUntil(10_000) { tags().count { it.startsWith("room_seat_") } >= 2 }
        shoot("mp_room_host")
    }

    @Test
    fun `대기실 - 손님`() {
        val host = fx.remote("host@test.co", "방장님")
        runBlocking { host.signUp() }
        val room = runBlocking { host.createRoom("금요일 한 판", max = 4, visibility = Visibility.PUBLIC) }
        val model = host.waitingRoom(room.id)
        fx.scope.launch { model.run() }
        signInAndOpenLobby()
        click(LobbyTags.REFRESH)
        waitFor(LobbyTags.join(room.id))
        click(LobbyTags.join(room.id))
        waitFor(WaitingTags.READY)
        runBlocking { model.addBot(BotLevel.EASY) }
        compose.waitUntil(10_000) { tags().count { it.startsWith("room_seat_") } >= 3 }
        shoot("mp_room_guest")
    }

    @Test
    fun `랭킹`() {
        seedProfile("a", "이도윤", 1286, 40, 22)
        seedProfile("b", "박서연", 1203, 31, 14)
        seedProfile("c", "최민준", 1150, 18, 8)
        seedProfile("d", "정하은", 1099, 9, 3)
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        seedProfile("kim", "kim", 1120, 12, 5)
        click(LobbyTags.BACK)
        waitFor(ProfileTags.RANKING)
        click(ProfileTags.RANKING)
        waitFor(StatsTags.rank("kim"))
        shoot("mp_ranking")
    }

    @Test
    fun `전적`() {
        fx.auth.addAccount("kim@test.co", "secret-123")
        show()
        openMultiplayer()
        signInWith("kim@test.co", "secret-123")
        waitFor(LobbyTags.CREATE)
        seedProfile("kim", "kim", 1048, 5, 2)
        val day = 86_400_000L
        val base = 1_763_000_000_000L
        seedResult("kim", "g1", rank = 0, delta = 24, players = 4, finishedAt = base)
        seedResult("kim", "g2", rank = 2, delta = -11, players = 4, finishedAt = base + day)
        seedResult("kim", "g3", rank = 1, delta = -3, players = 3, finishedAt = base + 2 * day)
        seedResult("kim", "g4", rank = 0, delta = 31, players = 5, finishedAt = base + 3 * day)
        seedResult("kim", "g5", rank = 3, delta = -16, players = 6, finishedAt = base + 4 * day)
        click(LobbyTags.BACK)
        waitFor(ProfileTags.HISTORY)
        click(ProfileTags.HISTORY)
        waitFor(StatsTags.history("g5"))
        shoot("mp_history")
    }
}
