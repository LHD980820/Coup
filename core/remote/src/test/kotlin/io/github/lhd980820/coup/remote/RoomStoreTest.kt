package io.github.lhd980820.coup.remote

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.GameStartPlan
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomRules
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.Visibility
import io.github.lhd980820.coup.runtime.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.random.Random
import io.github.lhd980820.coup.remote.FirestoreSchema.Room as R

class RoomStoreTest {
    private val fs = FakeFirestore()
    private val registry = BuiltinRules.registry()
    private val classic = BuiltinRules.classicConfig()
    private var seed = 0

    private fun store(uid: String) = FirestoreRoomStore(fs.storeFor(uid), uid)
    private fun service(uid: String) = RoomService(store(uid), registry, ClientInfo(10, registry), Clock { 1_000 }, Random(seed++))
    private fun user(uid: String) = LobbyUser(uid, "이름-$uid")

    private fun <T> RoomResult<T>.ok(): T = (this as RoomResult.Ok).value
    private fun RoomResult<*>.err(): RoomError = (this as RoomResult.Err).error

    private suspend fun room(max: Int = 4, visibility: Visibility = Visibility.PUBLIC): Room =
        service("host").createRoom(user("host"), "방", visibility, max, classic).ok()

    private suspend fun joinedAndReady(roomId: String, vararg uids: String) = uids.forEach {
        service(it).join(roomId, user(it)).ok()
        service(it).setReady(roomId, it, true).ok()
    }

    private fun doc(roomId: String): JsonObject = fs.docs.getValue("rooms/$roomId")

    /** 저장된 방 문서를 고쳐서(version +1) [uid]로 직접 쓴다 — 조작된 클라이언트 흉내. */
    private suspend fun forge(uid: String, roomId: String, change: MutableMap<String, JsonElement>.() -> Unit) {
        val d = doc(roomId).toMutableMap()
        d[R.VERSION] = JsonPrimitive((d[R.VERSION] as JsonPrimitive).content.toLong() + 1)
        d.change()
        fs.storeFor(uid).write(listOf(WriteOp.Set("rooms/$roomId", JsonObject(d))))
    }

    private fun MutableMap<String, JsonElement>.members(change: MutableMap<String, JsonElement>.() -> Unit) {
        this[R.MEMBERS] = JsonObject((this[R.MEMBERS] as JsonObject).toMutableMap().apply(change))
    }

    private fun member(name: String, ready: Long, seq: Long) = buildJsonObject {
        put(FirestoreSchema.Member.NAME, name)
        put(FirestoreSchema.Member.READY_VERSION, ready)
        put(FirestoreSchema.Member.SEQ, seq)
    }

    private fun MutableMap<String, JsonElement>.appendOrder(uid: String) {
        this[R.MEMBER_ORDER] = JsonArray((this[R.MEMBER_ORDER] as JsonArray) + JsonPrimitive(uid))
    }

    private fun assertDenied(block: suspend () -> Unit) = assertFailure { runBlocking { block() } }.isInstanceOf(PermissionDenied::class)

    // ---- 정상 흐름 ----------------------------------------------------------------------------

    @Test
    fun `방 만들기 - 입장 - 준비 - 시작 - 게임 생성 - 종료 - 다시 준비`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b", "c")
        val seen = service("b").observe(r.id).first()!!
        assertThat(seen.seats.map { it.id }).containsExactly("host", "b", "c")
        assertThat(seen.seats.all { it.ready }).isTrue()

        val plan = service("host").start(r.id, "host", seed = 3).ok()
        assertThat(plan.rated).isTrue()
        FirestoreGameTransport(fs.storeFor("host")).createGame(plan)
        assertThat(fs.docs.containsKey("games/${plan.gameId}")).isTrue()

        val back = service("host").endGame(r.id, "host").ok()
        assertThat(back.status).isEqualTo(RoomStatus.WAITING)
        assertThat(back.seats.filter { it.id != "host" }.none { it.ready }).isTrue()
        assertThat(service("host").start(r.id, "host").err()).isEqualTo(RoomError.NOT_ALL_READY)
    }

    @Test
    fun `규칙이나 봇이 바뀌면 게스트의 준비가 풀린다(configVersion)`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        service("host").updateRules(r.id, "host", classic.copy(houseRules = setOf("last_stand"))).ok()
        assertThat(store("b").get(r.id)!!.seats.first { it.id == "b" }.ready).isFalse()
        service("b").setReady(r.id, "b", true).ok()
        service("host").addBot(r.id, "host", BotLevel.EASY).ok()
        assertThat(store("b").get(r.id)!!.seats.first { it.id == "b" }.ready).isFalse()
        // 최대 인원 변경은 게임 내용이 아니므로 유지
        service("b").setReady(r.id, "b", true).ok()
        service("host").setMaxPlayers(r.id, "host", 5).ok()
        assertThat(store("b").get(r.id)!!.seats.first { it.id == "b" }.ready).isTrue()
    }

    @Test
    fun `비공개 방 - 코드로 입장, 목록에 없음, 닫히면 코드가 풀린다`(): Unit = runBlocking {
        val r = room(visibility = Visibility.PRIVATE)
        val code = r.joinCode!!
        assertThat(service("b").listOpen()).isEqualTo(emptyList())
        assertThat(service("b").joinByCode(user("b"), code.lowercase()).ok().seats.size).isEqualTo(2)
        // 같은 코드로 다른 방은 못 만든다
        val dup = RoomRules.create("other", "다른 방", user("x"), Visibility.PRIVATE, code, 4, classic, ClientInfo(10, registry), registry, 0).ok()
        assertThat(store("x").create(dup).err()).isEqualTo(RoomError.JOIN_CODE_TAKEN)
        service("host").leave(r.id, "host").ok()
        assertThat(fs.docs.containsKey("joinCodes/$code")).isFalse()
        assertThat(store("x").create(dup)).isInstanceOf(RoomResult.Ok::class)
        assertThat(store("c").findByJoinCode(code)!!.id).isEqualTo("other")
    }

    @Test
    fun `공개 목록은 대기 중인 공개 방만`(): Unit = runBlocking {
        val a = room()
        val playing = service("p").createRoom(user("p"), "게임 중", Visibility.PUBLIC, 2, classic).ok()
        service("p").addBot(playing.id, "p").ok()
        service("p").start(playing.id, "p").ok()
        room(visibility = Visibility.PRIVATE)
        assertThat(service("z").listOpen().map { it.id }).containsExactly(a.id)
        assertDenied { fs.storeFor("z").observeWhere(FirestoreSchema.ROOMS, R.VISIBILITY, "PRIVATE").first() }
    }

    @Test
    fun `마지막 자리 경쟁 - 트랜잭션이 다시 읽어 한 명만 들어간다`(): Unit = runBlocking {
        val r = room(max = 2)
        // b의 트랜잭션이 읽은 뒤 커밋하기 직전에 c가 먼저 들어온다
        fs.beforeNextCommit = { service("c").join(r.id, user("c")).ok() }
        assertThat(service("b").join(r.id, user("b")).err()).isEqualTo(RoomError.ROOM_FULL)
        assertThat(store("host").get(r.id)!!.seats.map { it.id }).containsExactly("host", "c")
    }

    // ---- 정산 게이트 --------------------------------------------------------------------------

    private suspend fun markPending(uid: String) {
        fs.admin().write(listOf(WriteOp.Set("pendingRatings/$uid", buildJsonObject { put("gameId", "old") })))
    }

    @Test
    fun `정산하지 않은 사람은 방을 만들거나 들어갈 수 없다`(): Unit = runBlocking {
        val r = room()
        markPending("b")
        assertThat(service("b").join(r.id, user("b")).err()).isEqualTo(RoomError.UNSETTLED_GAME)
        assertThat(service("b").createRoom(user("b"), "방", Visibility.PUBLIC, 4, classic).err()).isEqualTo(RoomError.UNSETTLED_GAME)
        // 클라이언트 검사를 건너뛰어도 규칙이 막는다
        assertDenied { forge("b", r.id) { members { put("b", member("b", -1, 9)) }; appendOrder("b") } }
    }

    @Test
    fun `방에 들어온 뒤 다른 게임이 끝나 정산 대기가 생기면 게임을 만들 수 없다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        val plan = service("host").start(r.id, "host").ok()
        markPending("b")
        assertDenied { FirestoreGameTransport(fs.storeFor("host")).createGame(plan) }
    }

    // ---- 규칙: 게스트 -------------------------------------------------------------------------

    @Test
    fun `규칙 - 게스트는 자기 자리만 바꾼다`(): Unit = runBlocking {
        val r = room(max = 3)
        joinedAndReady(r.id, "b")
        val cv = (doc(r.id)[R.CONFIG_VERSION] as JsonPrimitive).content.toLong()
        // 남을 끼워 넣기
        assertDenied { forge("b", r.id) { members { put("victim", member("v", -1, 9)) }; appendOrder("victim") } }
        // 룰셋·제목 바꾸기
        assertDenied { forge("b", r.id) { put(R.RULE_SET, RoomDocuments.encodeRuleSet(classic.copy(houseRules = setOf("last_stand")))) } }
        assertDenied { forge("b", r.id) { put(R.TITLE, JsonPrimitive("내 방")) } }
        // 다음 설정에 미리 준비해 두기
        assertDenied { forge("b", r.id) { members { put("b", member("이름-b", cv + 1, (get("b") as JsonObject)[FirestoreSchema.Member.SEQ]!!.let { (it as JsonPrimitive).content.toLong() })) } } }
        // 방장의 준비 해제
        assertDenied { forge("b", r.id) { members { put("host", member("이름-host", -1, 1)) } } }
        // 시작하기
        assertDenied { forge("b", r.id) { put(R.STATUS, JsonPrimitive("PLAYING")); put(R.GAME_ID, JsonPrimitive("g")) } }
        // 남을 내보내기
        service("c").join(r.id, user("c")).ok()
        assertDenied { forge("b", r.id) { members { remove("c") }; put(R.MEMBER_ORDER, JsonArray(listOf(JsonPrimitive("host"), JsonPrimitive("b")))) } }
    }

    @Test
    fun `규칙 - 강퇴된 사람, 가득 찬 방, 게임 중 퇴장`(): Unit = runBlocking {
        val r = room(max = 2)
        service("b").join(r.id, user("b")).ok()
        service("host").kick(r.id, "host", "b").ok()
        assertThat(service("b").join(r.id, user("b")).err()).isEqualTo(RoomError.KICKED)
        assertDenied { forge("b", r.id) { members { put("b", member("b", -1, 9)) }; appendOrder("b") } }
        service("c").join(r.id, user("c")).ok()
        assertDenied { forge("d", r.id) { members { put("d", member("d", -1, 9)) }; appendOrder("d") } }
        service("c").setReady(r.id, "c", true).ok()
        service("host").start(r.id, "host").ok()
        assertDenied { forge("c", r.id) { members { remove("c") }; put(R.MEMBER_ORDER, JsonArray(listOf(JsonPrimitive("host")))) } }
    }

    // ---- 규칙: 방장 ---------------------------------------------------------------------------

    @Test
    fun `규칙 - 방장은 사람을 넣을 수 없고, 바꾼 설정으로 준비를 속일 수 없다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        // 동의 없이 남을 좌석에 넣기
        assertDenied { forge("host", r.id) { members { put("victim", member("v", 0, 9)) }; appendOrder("victim") } }
        // 룰셋을 바꾸면서 configVersion을 그대로 두기(= 게스트 준비 유지)
        assertDenied { forge("host", r.id) { put(R.RULE_SET, RoomDocuments.encodeRuleSet(classic.copy(houseRules = setOf("last_stand")))) } }
        // 게스트 항목 고치기(준비시키기)
        service("b").setReady(r.id, "b", false).ok()
        assertDenied { forge("host", r.id) { members { put("b", member("이름-b", 0, 2)) } } }
        // 준비 안 된 게스트가 있는데 시작
        assertDenied { forge("host", r.id) { put(R.STATUS, JsonPrimitive("PLAYING")); put(R.GAME_ID, JsonPrimitive("g")) } }
        // 바꿀 수 없는 필드
        assertDenied { forge("host", r.id) { put(R.HOST_UID, JsonPrimitive("b")) } }
        assertDenied { forge("host", r.id) { put(R.VISIBILITY, JsonPrimitive("PRIVATE")) } }
        // 남의 방
        assertDenied { forge("z", r.id) { put(R.TITLE, JsonPrimitive("x")) } }
    }

    @Test
    fun `규칙 - 닫힌 방은 바꿀 수 없다`(): Unit = runBlocking {
        val r = room()
        service("host").leave(r.id, "host").ok()
        assertDenied { forge("host", r.id) { put(R.STATUS, JsonPrimitive("WAITING")) } }
        assertThat(service("b").join(r.id, user("b")).err()).isEqualTo(RoomError.ROOM_NOT_WAITING)
    }

    // ---- 규칙: 게임 생성은 방에 묶인다 --------------------------------------------------------

    @Test
    fun `규칙 - 게임 좌석은 방 사람 전원이고 rated는 방 구성과 같다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        val host = FirestoreGameTransport(fs.storeFor("host"))
        // 시작하지 않은 방
        assertDenied { host.createGame("g0", r.id, "host", listOf("host", "b"), classic, rated = true) }
        val plan = service("host").start(r.id, "host").ok()
        // 동의하지 않은 사람을 좌석에
        assertDenied { host.createGame(plan.gameId, r.id, "host", listOf("host", "b", "victim"), classic, rated = true) }
        // 방 사람을 빼고
        assertDenied { host.createGame(plan.gameId, r.id, "host", listOf("host"), classic, rated = true) }
        // 다른 gameId
        assertDenied { host.createGame("other", r.id, "host", listOf("host", "b"), classic, rated = true) }
        // 방장이 아닌 사람
        assertDenied { FirestoreGameTransport(fs.storeFor("b")).createGame(plan.gameId, r.id, "b", listOf("host", "b"), classic, rated = true) }
        // rated 거짓말
        assertDenied { host.createGame(plan.copy(rated = false)) }
        host.createGame(plan)
    }

    @Test
    fun `규칙 - 봇이 있는 방의 게임은 비레이팅이고 레이팅 결과를 쓸 수 없다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        service("host").addBot(r.id, "host").ok()
        service("b").setReady(r.id, "b", true).ok()
        val plan: GameStartPlan = service("host").start(r.id, "host").ok()
        assertThat(plan.rated).isFalse()
        val host = FirestoreGameTransport(fs.storeFor("host"))
        assertDenied { host.createGame(plan.copy(rated = true)) }
        host.createGame(plan)
        // 비레이팅 게임에 레이팅 결과
        assertDenied {
            fs.storeFor("host").write(
                listOf(
                    WriteOp.Set(
                        "results/${plan.gameId}",
                        buildJsonObject {
                            put(FirestoreSchema.Result.RANKING, JsonArray(listOf(JsonPrimitive("host"), JsonPrimitive("b"))))
                            put(FirestoreSchema.Result.PLAYER_COUNT, 2)
                            put(FirestoreSchema.Result.RATED, true)
                            put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { put("host", 30); put("b", -20) })
                        },
                    ),
                ),
            )
        }
    }

    @Test
    fun `규칙 - 방 생성은 방장 혼자 앉은 새 방, 비공개 코드는 같은 배치로`(): Unit = runBlocking {
        val base = RoomRules.create("r9", "방", user("a"), Visibility.PUBLIC, null, 4, classic, ClientInfo(10, registry), registry, 0).ok()
        val withGuest = base.copy(seats = base.seats + base.seats.first().copy(id = "victim"))
        assertDenied { fs.storeFor("a").write(listOf(WriteOp.Set("rooms/r9", RoomDocuments.encodeNew(withGuest)))) }
        assertDenied { fs.storeFor("z").write(listOf(WriteOp.Set("rooms/r9", RoomDocuments.encodeNew(base)))) }
        val priv = base.copy(visibility = Visibility.PRIVATE, joinCode = "ABC234")
        assertDenied { fs.storeFor("a").write(listOf(WriteOp.Set("rooms/r9", RoomDocuments.encodeNew(priv)))) }
        // 남의 방 코드를 가로채기
        val other = room(visibility = Visibility.PRIVATE)
        fs.admin().write(listOf(WriteOp.Delete("joinCodes/${other.joinCode}")))
        assertDenied { fs.storeFor("a").write(listOf(WriteOp.Set("joinCodes/${other.joinCode}", buildJsonObject { put("roomId", other.id) }))) }
        assertThat(store("a").create(base)).isInstanceOf(RoomResult.Ok::class)
        assertThat(doc("r9")[R.GAME_ID]).isEqualTo(JsonNull)
    }

    @Test
    fun `규칙 - 게스트 항목의 모양`(): Unit = runBlocking {
        val r = room()
        service("b").join(r.id, user("b")).ok()
        fun order(vararg uids: String) = JsonArray(uids.map(::JsonPrimitive))
        // 이름이 비었거나 너무 김
        assertDenied { forge("c", r.id) { members { put("c", member("", -1, 9)) }; appendOrder("c") } }
        assertDenied { forge("c", r.id) { members { put("c", member("가".repeat(21), -1, 9)) }; appendOrder("c") } }
        // 준비된 채로 입장
        assertDenied { forge("c", r.id) { members { put("c", member("c", 0, 9)) }; appendOrder("c") } }
        // 맨 앞자리로 끼어들기
        assertDenied { forge("c", r.id) { members { put("c", member("c", -1, 9)) }; put(R.MEMBER_ORDER, order("c", "host", "b")) } }
        // 입장하면서 다른 필드도
        assertDenied { forge("c", r.id) { members { put("c", member("c", -1, 9)) }; appendOrder("c"); put("admin", JsonPrimitive(true)) } }
        // 준비하면서 순서 바꾸기
        assertDenied { forge("b", r.id) { members { put("b", member("이름-b", 0, 2)) }; put(R.MEMBER_ORDER, order("b", "host")) } }
        // version을 올리지 않음
        assertDenied {
            val d = doc(r.id).toMutableMap()
            d[R.MEMBERS] = JsonObject((d[R.MEMBERS] as JsonObject) + ("b" to member("이름-b", 0, 2)))
            fs.storeFor("b").write(listOf(WriteOp.Set("rooms/${r.id}", JsonObject(d))))
        }
        // 정상 준비는 통과
        service("b").setReady(r.id, "b", true).ok()
    }

    @Test
    fun `규칙 - 방장 수정의 경계`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        // 모르는 필드
        assertDenied { forge("host", r.id) { put("admin", JsonPrimitive(true)) } }
        // 자기 자신을 명단에서 빼기
        assertDenied { forge("host", r.id) { members { remove("host") }; put(R.MEMBER_ORDER, JsonArray(listOf(JsonPrimitive("b")))) } }
        // 강퇴하면서 명단 순서에는 남겨 두기
        assertDenied { forge("host", r.id) { members { remove("b") } } }
        // configVersion 건너뛰기
        assertDenied { forge("host", r.id) { put(R.CONFIG_VERSION, JsonPrimitive(5)) } }
        // gameId 없이 시작
        assertDenied { forge("host", r.id) { put(R.STATUS, JsonPrimitive("PLAYING")) } }
        // 시작 전에 gameId만 바꾸기
        assertDenied { forge("host", r.id) { put(R.GAME_ID, JsonPrimitive("g")) } }
    }

    @Test
    fun `규칙 - 방장은 configVersion을 되돌려 옛 준비를 되살릴 수 없다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b") // b는 configVersion 0에 준비
        service("host").updateRules(r.id, "host", classic.copy(houseRules = setOf("last_stand"))).ok() // -> 1, b 준비 풀림
        assertDenied { forge("host", r.id) { put(R.CONFIG_VERSION, JsonPrimitive(0)) } }
        assertThat(store("b").get(r.id)!!.seats.first { it.id == "b" }.ready).isFalse()
    }

    @Test
    fun `규칙 - 게임 중에는 상태만 바뀐다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        service("host").start(r.id, "host").ok()
        assertDenied { forge("host", r.id) { put(R.TITLE, JsonPrimitive("바꿈")) } }
        assertDenied { forge("host", r.id) { put(R.RULE_SET, RoomDocuments.encodeRuleSet(classic.copy(houseRules = setOf("last_stand")))); put(R.CONFIG_VERSION, JsonPrimitive(1)) } }
        assertDenied { forge("host", r.id) { put(R.GAME_ID, JsonPrimitive("another")) } }
        assertDenied { forge("host", r.id) { put(R.STATUS, JsonPrimitive("WAITING")) } } // gameId를 비우지 않고 종료
        service("host").endGame(r.id, "host").ok()
    }

    @Test
    fun `규칙 - 새 방의 모양`(): Unit = runBlocking {
        val base = RoomRules.create("r8", "방", user("a"), Visibility.PUBLIC, null, 4, classic, ClientInfo(10, registry), registry, 0).ok()
        fun bad(change: MutableMap<String, JsonElement>.() -> Unit, as_: String = "a") = assertDenied {
            val d = RoomDocuments.encodeNew(base).toMutableMap().apply(change)
            fs.storeFor(as_).write(listOf(WriteOp.Set("rooms/r8", JsonObject(d))))
        }
        bad({ put(R.HOST_UID, JsonPrimitive("z")) })
        bad({ put(R.BOTS, JsonArray(listOf(buildJsonObject { put("id", "bot-1") }))) })
        bad({ put(R.STATUS, JsonPrimitive("PLAYING")) })
        bad({ put(R.GAME_ID, JsonPrimitive("g")) })
        bad({ put(R.BANNED, JsonArray(listOf(JsonPrimitive("z")))) })
        bad({ put(R.VERSION, JsonPrimitive(7)) })
        bad({ put(R.MAX_PLAYERS, JsonPrimitive(9)) })
        bad({ put(R.JOIN_CODE, JsonPrimitive("ABC234")) }) // 공개 방에 코드
        markPending("a")
        bad({ })
    }

    @Test
    fun `규칙 - 참가 코드를 가로채거나 묶어 둘 수 없다`(): Unit = runBlocking {
        fun claim(code: String, roomId: String) = WriteOp.Set("joinCodes/$code", buildJsonObject { put("roomId", roomId) })
        // 공개 방을 만들면서 아무 코드나 선점
        val pub = RoomRules.create("r7", "방", user("a"), Visibility.PUBLIC, null, 4, classic, ClientInfo(10, registry), registry, 0).ok()
        assertDenied { fs.storeFor("a").write(listOf(WriteOp.Set("rooms/r7", RoomDocuments.encodeNew(pub)), claim("ZZZ999", "r7"))) }
        // 닫힌 방의 코드를 다시 묶어 두기
        val priv = room(visibility = Visibility.PRIVATE)
        service("host").leave(priv.id, "host").ok()
        assertDenied { fs.storeFor("host").write(listOf(claim(priv.joinCode!!, priv.id))) }
        // 열린 방의 코드를 지우기 / 남의 코드를 지우기
        val open = room(visibility = Visibility.PRIVATE)
        assertDenied { fs.storeFor("host").write(listOf(WriteOp.Delete("joinCodes/${open.joinCode}"))) }
        assertDenied { fs.storeFor("z").write(listOf(WriteOp.Delete("joinCodes/${open.joinCode}"))) }
    }

    @Test
    fun `규칙 - 게임 문서의 방장은 방의 방장이어야 한다`(): Unit = runBlocking {
        val r = room()
        joinedAndReady(r.id, "b")
        val plan = service("host").start(r.id, "host").ok()
        // 방장이 게임 권위를 b에게 넘기는 문서를 쓰기(게임 수정 권한이 hostUid를 따른다)
        assertDenied { FirestoreGameTransport(fs.storeFor("host")).createGame(plan.gameId, r.id, "b", listOf("host", "b"), classic, rated = true) }
    }

    @Test
    fun `방 구독은 변경을 흘려보낸다`(): Unit = runBlocking {
        val r = room()
        assertThat(store("b").observe(r.id).first()).isNotNull()
        assertThat(store("b").observe("none").first()).isNull()
        service("b").join(r.id, user("b")).ok()
        assertThat(store("host").observe(r.id).first()!!.seats.map { it.id }).containsExactlyInAnyOrder("host", "b")
    }
}
