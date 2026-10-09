package io.github.lhd980820.coup.lobby

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.session.HostSeat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RoomTest {
    private val registry = BuiltinRules.registry()
    private val client = ClientInfo(10, registry)
    private val oldClient = ClientInfo(5, registry)
    private val host = LobbyUser("host", "방장")
    private fun user(n: Int) = LobbyUser("u$n", "유저$n")
    private val classic = BuiltinRules.classicConfig()
    private val houseConfig = classic.copy(houseRules = setOf("last_stand"))

    private fun service(store: RoomStore = InMemoryRoomStore(), time: Long = 1_000, seed: Int = 1) =
        RoomService(store, registry, client, Clock { time }, Random(seed))

    private fun <T> RoomResult<T>.ok(): T = (this as RoomResult.Ok).value
    private fun RoomResult<*>.err(): RoomError = (this as RoomResult.Err).error

    private fun newRoom(visibility: Visibility = Visibility.PUBLIC, max: Int = 4, config: RuleSetConfig = classic, code: String? = null) =
        RoomRules.create("r1", "  재밌는 방  ", host, visibility, code, max, config, client, registry, 100).ok()

    // ---- 생성 --------------------------------------------------------------------------------

    @Test
    fun `방 생성 - 제목은 다듬어지고 방장은 준비 상태로 앉는다`() {
        val room = newRoom()
        assertThat(room.title).isEqualTo("재밌는 방")
        assertThat(room.seats).hasSize(1)
        assertThat(room.seats.single().ready).isTrue()
        assertThat(room.minAppVersion).isEqualTo(10)
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `방 생성 - 잘못된 입력은 거절된다`() {
        fun create(title: String = "t", vis: Visibility = Visibility.PUBLIC, code: String? = null, max: Int = 4, cfg: RuleSetConfig = classic) =
            RoomRules.create("r", title, host, vis, code, max, cfg, client, registry, 0).err()
        assertThat(create(title = "   ")).isEqualTo(RoomError.INVALID_TITLE)
        assertThat(create(title = "가".repeat(31))).isEqualTo(RoomError.INVALID_TITLE)
        assertThat(create(vis = Visibility.PRIVATE)).isEqualTo(RoomError.JOIN_CODE_REQUIRED)
        assertThat(create(max = 1)).isEqualTo(RoomError.INVALID_PLAYER_COUNT)
        assertThat(create(max = 7)).isEqualTo(RoomError.INVALID_PLAYER_COUNT)
        assertThat(create(cfg = classic.copy(houseRules = setOf("nope")))).isEqualTo(RoomError.INVALID_RULES)
    }

    // ---- 입장/퇴장 ---------------------------------------------------------------------------

    @Test
    fun `입장 - 가득 차면 거절, 재입장은 그대로 성공`() {
        var room = newRoom(max = 2)
        room = RoomRules.join(room, user(1), client, null).ok()
        assertThat(RoomRules.join(room, user(2), client, null).err()).isEqualTo(RoomError.ROOM_FULL)
        assertThat(RoomRules.join(room, user(1), client, null).ok()).isEqualTo(room)
    }

    @Test
    fun `입장 - 앱 버전이 낮으면 업데이트 필요`() {
        val room = newRoom()
        assertThat(RoomRules.join(room, user(1), oldClient, null).err()).isEqualTo(RoomError.APP_UPDATE_REQUIRED)
    }

    @Test
    fun `입장 - 모르는 룰셋이면 업데이트 필요`() {
        val room = newRoom().copy(ruleSetConfig = classic.copy(houseRules = setOf("future_rule")))
        assertThat(RoomRules.join(room, user(1), client, null).err()).isEqualTo(RoomError.APP_UPDATE_REQUIRED)
    }

    @Test
    fun `입장 - 비공개 방은 코드가 맞아야 하고 대소문자와 공백은 무시한다`() {
        val room = newRoom(Visibility.PRIVATE, code = "ABC234")
        assertThat(RoomRules.join(room, user(1), client, null).err()).isEqualTo(RoomError.WRONG_CODE)
        assertThat(RoomRules.join(room, user(1), client, "ZZZ999").err()).isEqualTo(RoomError.WRONG_CODE)
        assertThat(RoomRules.join(room, user(1), client, " abc234 ").ok().seats).hasSize(2)
    }

    @Test
    fun `입장 - 시작된 방에는 새로 들어올 수 없지만 앉아 있던 사람은 재접속할 수 있다`() {
        var room = newRoom()
        room = RoomRules.join(room, user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        room = RoomRules.start(room, "host", "g1", registry).ok()
        assertThat(RoomRules.join(room, user(2), client, null).err()).isEqualTo(RoomError.ROOM_NOT_WAITING)
        assertThat(RoomRules.join(room, user(1), client, null).ok()).isEqualTo(room)
    }

    @Test
    fun `방장이 나가면 방이 닫히고 게스트가 나가면 자리만 빈다`() {
        var room = RoomRules.join(newRoom(), user(1), client, null).ok()
        val afterGuest = RoomRules.leave(room, "u1").ok()
        assertThat(afterGuest.seats).hasSize(1)
        assertThat(RoomRules.leave(room, "stranger").err()).isEqualTo(RoomError.NOT_IN_ROOM)
        room = RoomRules.leave(room, "host").ok()
        assertThat(room.status).isEqualTo(RoomStatus.CLOSED)
    }

    @Test
    fun `게스트는 게임 중에 방을 나갈 수 없다(기권으로 처리)`() {
        var room = RoomRules.join(newRoom(), user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        room = RoomRules.start(room, "host", "g", registry).ok()
        assertThat(RoomRules.leave(room, "u1").err()).isEqualTo(RoomError.ROOM_NOT_WAITING)
    }

    @Test
    fun `강퇴 - 방장만 가능하고 강퇴된 사람은 다시 못 들어온다`() {
        val room = RoomRules.join(newRoom(), user(1), client, null).ok()
        assertThat(RoomRules.kick(room, "u1", "host").err()).isEqualTo(RoomError.NOT_HOST)
        assertThat(RoomRules.kick(room, "host", "host").err()).isEqualTo(RoomError.NOT_ALLOWED)
        assertThat(RoomRules.kick(room, "host", "ghost").err()).isEqualTo(RoomError.TARGET_NOT_FOUND)
        val kicked = RoomRules.kick(room, "host", "u1").ok()
        assertThat(kicked.seats).hasSize(1)
        assertThat(RoomRules.join(kicked, user(1), client, null).err()).isEqualTo(RoomError.KICKED)
    }

    // ---- 준비/봇/규칙 변경 -------------------------------------------------------------------

    @Test
    fun `준비 - 방장은 바꿀 수 없고 모르는 사람은 거절`() {
        val room = RoomRules.join(newRoom(), user(1), client, null).ok()
        assertThat(RoomRules.setReady(room, "host", false).err()).isEqualTo(RoomError.NOT_ALLOWED)
        assertThat(RoomRules.setReady(room, "x", true).err()).isEqualTo(RoomError.NOT_IN_ROOM)
        assertThat(RoomRules.setReady(room, "u1", true).ok().seats.last().ready).isTrue()
    }

    @Test
    fun `봇 추가와 제거는 게스트 준비를 초기화한다`() {
        var room = RoomRules.join(newRoom(), user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        room = RoomRules.addBot(room, "host", "bot-1", "Bot 1", BotLevel.EASY, BotStyle.CAUTIOUS).ok()
        assertThat(room.seats.first { it.id == "u1" }.ready).isFalse()
        assertThat(room.seats.first { it.id == "bot-1" }.ready).isTrue()
        room = RoomRules.setReady(room, "u1", true).ok()
        room = RoomRules.removeBot(room, "host", "bot-1").ok()
        assertThat(room.seats.first { it.id == "u1" }.ready).isFalse()
        assertThat(RoomRules.addBot(room, "u1", "b", "B", BotLevel.EASY, BotStyle.BALANCED).err()).isEqualTo(RoomError.NOT_HOST)
        assertThat(RoomRules.removeBot(room, "host", "bot-9").err()).isEqualTo(RoomError.TARGET_NOT_FOUND)
    }

    @Test
    fun `봇은 빈 자리까지만 추가된다`() {
        val room = newRoom(max = 2)
        val withBot = RoomRules.addBot(room, "host", "bot-1", "B", BotLevel.NORMAL, BotStyle.BALANCED).ok()
        assertThat(RoomRules.addBot(withBot, "host", "bot-2", "B", BotLevel.NORMAL, BotStyle.BALANCED).err()).isEqualTo(RoomError.ROOM_FULL)
    }

    @Test
    fun `규칙 변경 - 게스트 준비 초기화, 같은 설정이면 유지`() {
        var room = RoomRules.join(newRoom(), user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        assertThat(RoomRules.updateRules(room, "host", classic, registry).ok()).isEqualTo(room)
        val changed = RoomRules.updateRules(room, "host", houseConfig, registry).ok()
        assertThat(changed.ruleSetConfig).isEqualTo(houseConfig)
        assertThat(changed.seats.first { it.id == "u1" }.ready).isFalse()
        assertThat(RoomRules.updateRules(room, "u1", houseConfig, registry).err()).isEqualTo(RoomError.NOT_HOST)
        assertThat(RoomRules.updateRules(room, "host", classic.copy(houseRules = setOf("zzz")), registry).err()).isEqualTo(RoomError.INVALID_RULES)
    }

    @Test
    fun `규칙 변경 - 앉은 인원이 새 최대 인원을 넘으면 충돌`() {
        var room = newRoom(max = 4)
        room = RoomRules.join(room, user(1), client, null).ok()
        room = RoomRules.join(room, user(2), client, null).ok()
        val tight = classic.copy(paramOverrides = mapOf("maxPlayers" to "2"))
        assertThat(RoomRules.updateRules(room, "host", tight, registry).err()).isEqualTo(RoomError.RULES_CONFLICT)
        val roomy = newRoom(max = 5)
        assertThat(RoomRules.updateRules(roomy, "host", tight, registry).ok().maxPlayers).isEqualTo(2)
    }

    @Test
    fun `최대 인원 변경 - 앉은 인원보다 작거나 범위 밖이면 거절, 준비는 유지`() {
        var room = RoomRules.join(newRoom(max = 4), user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        assertThat(RoomRules.setMaxPlayers(room, "host", 1, registry).err()).isEqualTo(RoomError.INVALID_PLAYER_COUNT)
        assertThat(RoomRules.setMaxPlayers(room, "host", 9, registry).err()).isEqualTo(RoomError.INVALID_PLAYER_COUNT)
        val changed = RoomRules.setMaxPlayers(room, "host", 6, registry).ok()
        assertThat(changed.maxPlayers).isEqualTo(6)
        assertThat(changed.seats.first { it.id == "u1" }.ready).isTrue()
    }

    // ---- 시작/종료 ---------------------------------------------------------------------------

    @Test
    fun `시작 조건 - 인원 부족과 미준비를 모두 알려준다`() {
        val alone = newRoom()
        assertThat(RoomRules.startBlockers(alone, registry)).containsExactly(RoomError.NOT_ENOUGH_PLAYERS)
        val room = RoomRules.join(alone, user(1), client, null).ok()
        assertThat(RoomRules.startBlockers(room, registry)).containsExactly(RoomError.NOT_ALL_READY)
        assertThat(RoomRules.startBlockers(RoomRules.setReady(room, "u1", true).ok(), registry)).isEmpty()
        assertThat(RoomRules.start(room, "u1", "g", registry).err()).isEqualTo(RoomError.NOT_HOST)
        assertThat(RoomRules.start(room, "host", "g", registry).err()).isEqualTo(RoomError.NOT_ALL_READY)
    }

    @Test
    fun `봇만 있어도 시작할 수 있다`() {
        val room = RoomRules.addBot(newRoom(), "host", "bot-1", "B", BotLevel.NORMAL, BotStyle.BALANCED).ok()
        assertThat(RoomRules.start(room, "host", "g", registry).ok().status).isEqualTo(RoomStatus.PLAYING)
    }

    @Test
    fun `게임이 끝나면 대기실로 돌아가고 게스트는 다시 준비해야 한다`() {
        var room = RoomRules.join(newRoom(), user(1), client, null).ok()
        room = RoomRules.setReady(room, "u1", true).ok()
        assertThat(RoomRules.endGame(room, "host").err()).isEqualTo(RoomError.NOT_ALLOWED)
        room = RoomRules.start(room, "host", "g", registry).ok()
        assertThat(RoomRules.endGame(room, "u1").err()).isEqualTo(RoomError.NOT_HOST)
        room = RoomRules.endGame(room, "host").ok()
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)
        assertThat(room.gameId).isNull()
        assertThat(room.seats.first { it.id == "u1" }.ready).isFalse()
    }

    @Test
    fun `레이팅 여부 - 봇, 하우스룰, 파라미터 변경이 없을 때만`() {
        val plain = newRoom()
        assertThat(plain.isRated).isTrue()
        assertThat(RoomRules.addBot(plain, "host", "bot-1", "B", BotLevel.EASY, BotStyle.BALANCED).ok().isRated).isFalse()
        assertThat(plain.copy(ruleSetConfig = houseConfig).isRated).isFalse()
        assertThat(plain.copy(ruleSetConfig = classic.copy(paramOverrides = mapOf("startingCoins" to "3"))).isRated).isFalse()
    }

    // ---- 참가 코드 ---------------------------------------------------------------------------

    @Test
    fun `참가 코드 - 6자리이며 헷갈리는 글자를 쓰지 않는다`() {
        val random = Random(3)
        repeat(200) {
            val code = JoinCodes.generate(random)
            assertThat(code.length).isEqualTo(6)
            assertThat(JoinCodes.isWellFormed(code)).isTrue()
            assertThat(code.any { it in "0O1ILl" }).isFalse()
        }
        assertThat(JoinCodes.isWellFormed("abc")).isFalse()
        assertThat(JoinCodes.isWellFormed("ABC0EF")).isFalse()
        assertThat(JoinCodes.normalize(" ab c ")).isEqualTo("AB C")
    }

    // ---- 서비스 + 저장소 ---------------------------------------------------------------------

    @Test
    fun `마지막 자리에 동시에 들어오면 정확히 한 명만 성공한다`() = runBlocking {
        repeat(20) { round ->
            val svc = service(seed = round)
            val room = svc.createRoom(host, "방", Visibility.PUBLIC, 2, classic).ok()
            val results = (1..8).map { n -> async(kotlinx.coroutines.Dispatchers.Default) { svc.join(room.id, user(n)) } }.awaitAll()
            assertThat(results.count { it is RoomResult.Ok }).isEqualTo(1)
            assertThat(results.filterIsInstance<RoomResult.Err>().map { it.error }.toSet()).isEqualTo(setOf(RoomError.ROOM_FULL))
        }
    }

    @Test
    fun `존재하지 않는 방은 ROOM_NOT_FOUND`() = runBlocking {
        assertThat(service().join("nope", user(1)).err()).isEqualTo(RoomError.ROOM_NOT_FOUND)
        assertThat(service().startBlockers("nope")).containsExactly(RoomError.ROOM_NOT_FOUND)
    }

    @Test
    fun `비공개 방은 목록에 없고 코드로만 들어간다`() = runBlocking {
        val svc = service()
        val priv = svc.createRoom(host, "비밀", Visibility.PRIVATE, 4, classic).ok()
        assertThat(JoinCodes.isWellFormed(priv.joinCode!!)).isTrue()
        assertThat(svc.listOpen()).isEmpty()
        assertThat(svc.joinByCode(user(1), "????").err()).isEqualTo(RoomError.WRONG_CODE)
        assertThat(svc.joinByCode(user(1), "AAAAAA").err()).isEqualTo(RoomError.ROOM_NOT_FOUND)
        assertThat(svc.joinByCode(user(1), priv.joinCode.lowercase()).ok().seats).hasSize(2)
    }

    @Test
    fun `공개 목록은 대기 중인 공개 방만 최신순으로 보여주고 요약을 담는다`() = runBlocking {
        var now = 0L
        val store = InMemoryRoomStore()
        val svc = RoomService(store, registry, client, Clock { now }, Random(9))
        now = 10
        val a = svc.createRoom(host, "A", Visibility.PUBLIC, 4, classic).ok()
        now = 20
        val b = svc.createRoom(LobbyUser("h2", "방장2"), "B", Visibility.PUBLIC, 4, houseConfig).ok()
        now = 30
        val c = svc.createRoom(LobbyUser("h3", "방장3"), "C", Visibility.PUBLIC, 2, classic).ok()
        svc.addBot(c.id, "h3").ok()
        svc.start(c.id, "h3", 1).ok() // 시작된 방은 목록에서 빠진다
        val list = svc.listOpen()
        assertThat(list.map { it.id }).containsExactly(b.id, a.id)
        assertThat(list.first().isRated).isFalse()
        assertThat(list.first().hasCustomRules).isTrue()
        assertThat(list.last().hostName).isEqualTo("방장")
        assertThat(list.last().isRated).isTrue()
    }

    @Test
    fun `봇 ID는 겹치지 않는다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 5, classic).ok()
        svc.addBot(room.id, "host").ok()
        svc.addBot(room.id, "host").ok()
        svc.removeBot(room.id, "host", "bot-1").ok()
        val after = svc.addBot(room.id, "host").ok()
        assertThat(after.botSeats.map { it.id }).containsExactlyInAnyOrder("bot-1", "bot-2")
    }

    @Test
    fun `저장소는 변경마다 version을 올리고 변화가 없으면 올리지 않는다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 4, classic).ok()
        assertThat(room.version).isEqualTo(1)
        val joined = svc.join(room.id, user(1)).ok()
        assertThat(joined.version).isEqualTo(2)
        assertThat(svc.join(room.id, user(1)).ok().version).isEqualTo(2)
    }

    @Test
    fun `같은 참가 코드를 가진 방은 만들 수 없다`() = runBlocking {
        val store = InMemoryRoomStore()
        val a = RoomRules.create("a", "A", host, Visibility.PRIVATE, "ABC234", 4, classic, client, registry, 0).ok()
        val b = RoomRules.create("b", "B", host, Visibility.PRIVATE, "abc234", 4, classic, client, registry, 0).ok()
        store.create(a).ok()
        assertThat(store.create(b).err()).isEqualTo(RoomError.JOIN_CODE_TAKEN)
        assertThat(store.create(a).err()).isNotEqualTo(RoomError.JOIN_CODE_TAKEN)
    }

    @Test
    fun `방 구독 - 변경이 흘러나오고 방장이 나가면 닫힘이 보인다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 4, classic).ok()
        val seen = mutableListOf<RoomStatus?>()
        svc.join(room.id, user(1)).ok()
        seen += svc.observe(room.id).first()?.status
        svc.leave(room.id, "host").ok()
        seen += svc.observe(room.id).first()?.status
        assertThat(seen).containsExactly(RoomStatus.WAITING, RoomStatus.CLOSED)
    }

    // ---- 게임 시작 계획 ----------------------------------------------------------------------

    @Test
    fun `시작 계획 - 같은 seed면 같은 좌석 순서이고 모든 좌석이 한 번씩 들어간다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 5, classic).ok()
        svc.join(room.id, user(1)).ok()
        svc.join(room.id, user(2)).ok()
        svc.setReady(room.id, "u1", true).ok()
        svc.setReady(room.id, "u2", true).ok()
        assertThat(svc.start(room.id, "u1", 1).err()).isEqualTo(RoomError.NOT_HOST)
        val plan = svc.start(room.id, "host", 77).ok()
        val again = GameStartPlan.of(svc.observe(room.id).first()!!, plan.gameId, 77)
        assertThat(again.seats).isEqualTo(plan.seats)
        assertThat(plan.seats.map { it.playerId.value }).containsExactlyInAnyOrder("host", "u1", "u2")
        assertThat(plan.rated).isTrue()
        assertThat(svc.start(room.id, "host", 77).err()).isEqualTo(RoomError.ROOM_NOT_WAITING)
    }

    @Test
    fun `시작 계획 - 엔진에서 게임이 만들어지고 방장만 Local 좌석이며 봇은 에이전트를 갖는다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 4, classic).ok()
        svc.join(room.id, user(1)).ok()
        svc.setReady(room.id, "u1", true).ok()
        svc.addBot(room.id, "host", BotLevel.EASY, BotStyle.AGGRESSIVE).ok()
        svc.setReady(room.id, "u1", true).ok()
        val plan = svc.start(room.id, "host", 5).ok()
        assertThat(plan.rated).isFalse()
        val state = GameEngines.create().newGame(plan.setup())
        assertThat(state.gameId).isEqualTo(plan.gameId)
        val seats = plan.hostSeats()
        assertThat(seats.filterIsInstance<HostSeat.Local>().single().id.value).isEqualTo("host")
        assertThat(seats.filterIsInstance<HostSeat.Remote>().single().uid).isEqualTo("u1")
        assertThat(seats.filterIsInstance<HostSeat.Bot>().single().displayName).isEqualTo("Bot 1")
        assertThat(seats.map { it.id }).isEqualTo(plan.seats.map { it.playerId })
    }

    @Test
    fun `게임이 끝나면 같은 방에서 다시 시작할 수 있다`() = runBlocking {
        val svc = service()
        val room = svc.createRoom(host, "방", Visibility.PUBLIC, 3, classic).ok()
        svc.addBot(room.id, "host").ok()
        val first = svc.start(room.id, "host", 1).ok()
        svc.endGame(room.id, "host").ok()
        val second = svc.start(room.id, "host", 2).ok()
        assertThat(second.gameId).isNotEqualTo(first.gameId)
        assertThat(second).isInstanceOf(GameStartPlan::class)
    }
}
