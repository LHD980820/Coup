package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.shell.lobby.LobbyEvent
import io.github.lhd980820.coup.shell.lobby.LobbyModel
import io.github.lhd980820.coup.shell.lobby.LobbyProblem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class LobbyModelTest {
    private fun RoomResult<*>.ok() = assertThat(this).isInstanceOf(RoomResult.Ok::class)

    @Test
    fun `목록 - 불러오기 전에는 null, 공개 대기 방만 최신순으로`() = fixture { f ->
        val lobby = LobbyModel(f.roomService("me"), f.user("me"))
        assertThat(lobby.state.value.rooms).isNull()
        listOf("h1", "h2", "h3").forEach { f.signedUp(it) }
        f.roomService("h1").createRoom(f.user("h1"), "첫째", Visibility.PUBLIC, 4, f.classic).ok()
        delay(5)
        f.roomService("h2").createRoom(f.user("h2"), "둘째", Visibility.PUBLIC, 4, f.classic).ok()
        f.roomService("h3").createRoom(f.user("h3"), "비공개", Visibility.PRIVATE, 4, f.classic).ok()
        lobby.refresh()
        assertThat(lobby.state.value.rooms!!.map { it.title }).isEqualTo(listOf("둘째", "첫째"))
        assertThat(lobby.state.value.refreshing).isFalse()
    }

    @Test
    fun `방 만들기 - 성공하면 그 방으로 들어가라고 알린다`() = fixture { f ->
        f.signedUp("me")
        val lobby = LobbyModel(f.roomService("me"), f.user("me"))
        lobby.createRoom("우리 방", Visibility.PUBLIC, 4, f.classic)
        val event = withTimeout(5_000) { lobby.events.first() }
        assertThat(event).isInstanceOf(LobbyEvent.EnterRoom::class)
        val roomId = (event as LobbyEvent.EnterRoom).roomId
        assertThat(f.roomService("me").observe(roomId).first()!!.title).isEqualTo("우리 방")
        assertThat(lobby.state.value.busy).isFalse()
    }

    @Test
    fun `방 만들기 - 제목이 비면 규칙 오류로 알리고 이동하지 않는다`() = fixture { f ->
        f.signedUp("me")
        val lobby = LobbyModel(f.roomService("me"), f.user("me"))
        lobby.createRoom("   ", Visibility.PUBLIC, 4, f.classic)
        assertThat(lobby.state.value.problem).isEqualTo(LobbyProblem.Room(RoomError.INVALID_TITLE))
        lobby.dismissProblem()
        assertThat(lobby.state.value.problem).isNull()
    }

    @Test
    fun `입장 - 가득 찬 방과 정산 안 한 사용자는 이유를 알려준다`() = fixture { f ->
        listOf("host", "a", "b").forEach { f.signedUp(it) }
        val room = (f.roomService("host").createRoom(f.user("host"), "둘이서", Visibility.PUBLIC, 2, f.classic) as RoomResult.Ok).value
        val a = LobbyModel(f.roomService("a"), f.user("a"))
        a.join(room.id)
        assertThat(withTimeout(5_000) { a.events.first() }).isEqualTo(LobbyEvent.EnterRoom(room.id))
        val b = LobbyModel(f.roomService("b"), f.user("b"))
        b.join(room.id)
        assertThat(b.state.value.problem).isEqualTo(LobbyProblem.Room(RoomError.ROOM_FULL))
    }

    @Test
    fun `참가 코드 - 대소문자와 공백을 무시하고, 틀리면 알려준다`() = fixture { f ->
        listOf("host", "a").forEach { f.signedUp(it) }
        val room = (f.roomService("host").createRoom(f.user("host"), "비밀", Visibility.PRIVATE, 4, f.classic) as RoomResult.Ok).value
        val lobby = LobbyModel(f.roomService("a"), f.user("a"))
        assertThat(lobby.isCodeComplete("abc")).isFalse()
        assertThat(lobby.isCodeComplete(" ${room.joinCode!!.lowercase()} ")).isTrue()
        lobby.joinByCode("ZZZZZZ")
        assertThat(lobby.state.value.problem).isEqualTo(LobbyProblem.Room(RoomError.ROOM_NOT_FOUND))
        lobby.joinByCode("abc")
        assertThat(lobby.state.value.problem).isEqualTo(LobbyProblem.Room(RoomError.WRONG_CODE))
        lobby.joinByCode(" ${room.joinCode!!.lowercase()} ")
        assertThat(withTimeout(5_000) { lobby.events.first() }).isEqualTo(LobbyEvent.EnterRoom(room.id))
        assertThat(lobby.state.value.problem).isNull()
    }

    @Test
    fun `네트워크 오류 - 알리고, 다시 읽으면 사라진다`() = fixture { f ->
        f.signedUp("me")
        val flaky = FlakyStore(f.store("me"))
        val lobby = LobbyModel(f.roomService("me", flaky), f.user("me"))
        flaky.fail = true
        lobby.createRoom("방", Visibility.PUBLIC, 4, f.classic)
        assertThat(lobby.state.value.problem).isEqualTo(LobbyProblem.Network)
        assertThat(lobby.state.value.busy).isFalse()
        flaky.fail = false
        lobby.refresh()
        assertThat(lobby.state.value.problem).isNull()
        assertThat(lobby.state.value.rooms).isEqualTo(emptyList())
    }

    @Test
    fun `run - 화면이 보이는 동안 주기적으로 새로 읽는다`() = fixture { f ->
        f.signedUp("h")
        val lobby = LobbyModel(f.roomService("me"), f.user("me"), refreshIntervalMillis = 20)
        val job = launch { lobby.run() }
        lobby.state.first { it.rooms != null }
        f.roomService("h").createRoom(f.user("h"), "새 방", Visibility.PUBLIC, 4, f.classic).ok()
        withTimeout(5_000) { lobby.state.first { s -> s.rooms?.any { it.title == "새 방" } == true } }
        job.cancel()
    }

    @Test
    fun `공유 메시지를 통째로 붙여 넣어도 코드를 찾아 입장한다`() = fixture { f ->
        listOf("host", "a").forEach { f.signedUp(it) }
        val room = (f.roomService("host").createRoom(f.user("host"), "비밀", Visibility.PRIVATE, 4, f.classic) as RoomResult.Ok).value
        val lobby = LobbyModel(f.roomService("a"), f.user("a"))
        val message = "Coup 방 '비밀'에 들어오세요!\n참가 코드: ${room.joinCode!!.lowercase()}"
        assertThat(lobby.isCodeComplete(message)).isTrue()
        assertThat(lobby.isCodeComplete("그냥 아무 말이나 적었어요")).isFalse()
        lobby.joinByCode(message)
        assertThat(withTimeout(5_000) { lobby.events.first() }).isEqualTo(LobbyEvent.EnterRoom(room.id))
    }
}
