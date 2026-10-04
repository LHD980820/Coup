package io.github.lhd980820.coup.runtime

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.AiAgent
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.session.SubmitResult
import io.github.lhd980820.coup.runtime.transport.AckResult
import io.github.lhd980820.coup.runtime.transport.GameResultRecord
import io.github.lhd980820.coup.runtime.transport.GameTransport
import io.github.lhd980820.coup.runtime.transport.InMemoryTransport
import io.github.lhd980820.coup.runtime.transport.Publication
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.runtime.transport.ViewEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class MultiplayerTest {
    private val engine = GameEngines.create()
    private fun pid(n: Int) = PlayerId("u$n")
    private fun ids(n: Int) = List(n) { pid(it) }

    /** 호스트로 가는 모든 공개물을 기록하는 감시 전송(보안 검증용). */
    private class Spy(private val inner: GameTransport.Host) : GameTransport.Host by inner {
        val publications = mutableListOf<Publication>()
        override suspend fun publish(gameId: String, publication: Publication) {
            publications += publication
            inner.publish(gameId, publication)
        }
    }

    private class Table(
        val transport: InMemoryTransport,
        val spy: Spy,
        val host: HostGameSession,
        val guests: Map<PlayerId, RemoteGameSession>,
    ) {
        fun all(): List<GameSession> = listOf<GameSession>(host) + guests.values
    }

    private fun TestScope.table(
        humans: Int,
        bots: Int = 0,
        seed: Long = 5,
        config: io.github.lhd980820.coup.engine.rules.RuleSetConfig = BuiltinRules.classicConfig(),
        policy: TimeoutPolicy = TimeoutPolicy.None,
        transport: InMemoryTransport = InMemoryTransport(),
        initial: GameState? = null,
    ): Table {
        val humanIds = ids(humans)
        val botIds = List(bots) { PlayerId("bot$it") }
        val setup = GameSetup("mp", humanIds + botIds, config, seed, firstPlayer = humanIds.first())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val seats: List<HostSeat> = humanIds.mapIndexed { i, id ->
            if (i == 0) HostSeat.Local(id, "방장") else HostSeat.Remote(id, "게스트$i")
        } + botIds.map { HostSeat.Bot(it, "봇", RandomAgent(it.value.hashCode().toLong())) }
        val spy = Spy(transport.host)
        val host = HostGameSession(
            engine, initial ?: engine.newGame(setup), seats, spy, policy, Clock { testScheduler.currentTime },
            backgroundScope, aiDispatcher = dispatcher,
        )
        val info = seats.associate { it.id to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
        val guests = humanIds.drop(1).associateWith { id ->
            RemoteGameSession("mp", id, transport.guest(id.value), info, backgroundScope)
        }
        return Table(transport, spy, host, guests)
    }

    /** 각 세션 뒤에서 무작위 에이전트가 결정을 내리게 한다(사람 대신). */
    private fun TestScope.drive(sessions: List<GameSession>, skip: Set<PlayerId> = emptySet()) {
        sessions.filter { it.me !in skip }.forEach { session ->
            val agent: AiAgent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
            backgroundScope.launch {
                var acted = -1L
                session.snapshot.collect { snap ->
                    val request = snap?.view?.myDecision ?: return@collect
                    if (snap.view.version == acted) return@collect
                    acted = snap.view.version
                    session.submit(agent.decide(snap.view, request))
                }
            }
        }
    }


    // ---- 정상 흐름 ---------------------------------------------------------------------------------

    @Test
    fun `호스트 1 + 게스트 3이 끝까지 게임을 마치고 모두 같은 결과를 본다`() = runTest {
        val t = table(humans = 4)
        drive(t.all())
        runUntil { t.guests.values.all { it.snapshot.value?.view?.result != null } && t.host.snapshot.value?.view?.result != null }
        val results = t.all().map { checkNotNull(it.snapshot.value).view.result }
        assertThat(results.toSet()).hasSize(1)
        assertThat(t.transport.result).isNotNull()
        assertThat(t.transport.result!!.ranking).isEqualTo(results.first()!!.ranking)
    }

    @Test
    fun `사람끼리만, 기본 룰이면 레이팅 반영 대상이고 변동은 표와 같다`() = runTest {
        val t = table(humans = 4)
        drive(t.all())
        runUntil { t.transport.result != null }
        val result = t.transport.result!!
        assertThat(result.rated).isTrue()
        assertThat(result.ratingDeltas.values.toList()).isEqualTo(listOf(70, 30, -30, -50))
        assertThat(result.ratingDeltas.keys.toList()).isEqualTo(result.ranking)
    }

    @Test
    fun `봇이나 하우스룰이 있으면 레이팅에 반영하지 않는다`() = runTest {
        val withBot = table(humans = 2, bots = 1)
        drive(withBot.all())
        runUntil { withBot.transport.result != null }
        assertThat(withBot.transport.result!!.rated).isEqualTo(false)
        assertThat(withBot.transport.result!!.ratingDeltas).isEmpty()
    }

    @Test
    fun `하우스룰 게임은 레이팅 미반영`() = runTest {
        val cfg = BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"))
        val t = table(humans = 3, config = cfg)
        drive(t.all())
        runUntil { t.transport.result != null }
        assertThat(t.transport.result!!.rated).isEqualTo(false)
    }

    @Test
    fun `봇이 섞인 게임도 끝까지 진행된다`() = runTest {
        val t = table(humans = 2, bots = 2)
        drive(t.all())
        runUntil { t.guests.values.single().snapshot.value?.view?.result != null }
        assertThat(t.guests.values.single().snapshot.value!!.view.opponents).hasSize(3)
    }

    // ---- 동시성 ------------------------------------------------------------------------------------

    @Test
    fun `사람 응답자 셋이 같은 시점에 허용을 눌러도 모두 반영된다`() = runTest {
        val t = table(humans = 4)
        runCurrent()
        assertThat(t.host.submit(Command.DeclareAction(pid(0), ActionId("tax")))).isEqualTo(SubmitResult.Ok)
        runCurrent()
        val before = t.guests.getValue(pid(1)).snapshot.value!!.view
        val results = mutableListOf<SubmitResult>()
        t.guests.values.forEach { g ->
            launch { results += g.submit(Command.Pass(g.me, expectedVersion = before.version)) }
        }
        runCurrent()
        assertThat(results).isEqualTo(List(3) { SubmitResult.Ok })
        assertThat(checkNotNull(t.host.snapshot.value).view.me!!.coins).isEqualTo(5) // 세금 +3
    }

    @Test
    fun `도전이 동시에 몰리면 하나만 수락되고 나머지는 거절된다`() = runTest {
        val t = table(humans = 4)
        runCurrent()
        t.host.submit(Command.DeclareAction(pid(0), ActionId("tax")))
        runCurrent()
        val v = t.guests.getValue(pid(1)).snapshot.value!!.view.version
        val results = mutableListOf<SubmitResult>()
        t.guests.values.forEach { g -> launch { results += g.submit(Command.Challenge(g.me, expectedVersion = v)) } }
        runCurrent()
        assertThat(results.count { it == SubmitResult.Ok }).isEqualTo(1)
        assertThat(results.filterIsInstance<SubmitResult.Rejected>()).hasSize(2)
    }

    // ---- 보안 --------------------------------------------------------------------------------------

    @Test
    fun `게스트는 다른 사람의 이름으로 명령을 보낼 수 없다`() = runTest {
        val t = table(humans = 3)
        runCurrent()
        val guest = t.guests.getValue(pid(1))
        val rawGuest = t.transport.guest(pid(1).value)
        // 정상 세션 API는 거부한다
        runCatching { guest.submit(Command.DeclareAction(pid(0), ActionId("income"))) }.exceptionOrNull()
            .let { assertThat(it).isNotNull().isInstanceOf(IllegalArgumentException::class) }
        // 전송 계층을 직접 써서 속여도 호스트가 거절한다
        val ack = rawGuest.sendCommand("mp", Command.DeclareAction(pid(0), ActionId("income")))
        assertThat(ack).isEqualTo(AckResult.Rejected(Rejection.NOT_YOUR_DECISION))
        assertThat(t.host.snapshot.value!!.view.version).isEqualTo(0L)
    }

    @Test
    fun `등록되지 않은 발신자와 봇 좌석 사칭은 거절된다`() = runTest {
        val t = table(humans = 2, bots = 1)
        runCurrent()
        val stranger = t.transport.guest("intruder")
        assertThat(stranger.sendCommand("mp", Command.Concede(pid(1)))).isEqualTo(AckResult.Rejected(Rejection.NOT_YOUR_DECISION))
        val g = t.transport.guest(pid(1).value)
        assertThat(g.sendCommand("mp", Command.Concede(PlayerId("bot0")))).isEqualTo(AckResult.Rejected(Rejection.NOT_YOUR_DECISION))
    }

    @Test
    fun `게스트는 다른 좌석의 뷰를 구독할 수 없다`() = runTest {
        val t = table(humans = 3)
        val spying = runCatching { t.transport.guest(pid(1).value).observeMyView("mp", pid(2)) }
        assertThat(spying.exceptionOrNull()).isNotNull().isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `게스트가 받은 모든 갱신에는 알 수 없는 카드 정보가 없다`() = runTest {
        val t = table(humans = 4, bots = 1)
        val received = t.guests.keys.associateWith { mutableListOf<String>() }
        t.guests.forEach { (id, _) ->
            backgroundScope.launch {
                t.transport.guest(id.value).observeMyView("mp", id).collect {
                    received.getValue(id) += EngineJson.json.encodeToString(ViewEnvelope.serializer(), it)
                }
            }
        }
        drive(t.all())
        runUntil { t.transport.result != null }
        runCurrent()

        val backups = t.spy.publications.associate { it.version to it.authorityBackup }
        var checked = 0
        received.forEach { (id, texts) ->
            val known = mutableSetOf<Int>() // 이 게스트가 합법적으로 알게 된 카드 ID
            texts.forEach { text ->
                val envelope = EngineJson.json.parseToJsonElement(text).jsonObject
                val version = envelope.getValue("version").jsonPrimitive.content.toLong()
                val cardIds = cardIdsIn(envelope)
                known += publicCardIds(envelope)
                known += myCardIds(envelope)
                val forbidden = hiddenCardIds(backups.getValue(version), exceptFor = id) - known
                assertThat(cardIds.intersect(forbidden)).isEmpty()
                checked++
            }
        }
        assertThat(checked > 50).isTrue()
    }

    // ---- 연결·복구 ---------------------------------------------------------------------------------

    @Test
    fun `호스트가 사라지면 게스트는 HOST_LOST를 보고, 끝나면 CLOSED`() = runTest {
        val t = table(humans = 2)
        runCurrent()
        assertThat(t.guests.values.single().connection.value).isEqualTo(ConnectionState.CONNECTED)
        t.transport.dropHost()
        runCurrent()
        assertThat(t.guests.values.single().connection.value).isEqualTo(ConnectionState.HOST_LOST)
    }

    @Test
    fun `호스트를 백업에서 복구하면 같은 게임이 이어진다`() = runTest {
        val transport = InMemoryTransport()
        val first = table(humans = 3, transport = transport)
        runCurrent()
        first.host.submit(Command.DeclareAction(pid(0), ActionId("income")))
        runCurrent()
        first.guests.getValue(pid(1)).submit(Command.DeclareAction(pid(1), ActionId("foreign_aid")))
        runCurrent()
        val backup = checkNotNull(transport.lastBackup)
        val versionBefore = first.host.snapshot.value!!.view.version
        first.host.close()

        val restored = engine.let { EngineJson.decodeState(backup) }
        val second = table(humans = 3, transport = transport, initial = restored)
        // 기존 게스트 세션이 새 호스트의 갱신도 이어서 받는다(같은 전송)
        runCurrent()
        assertThat(second.host.snapshot.value!!.view.version).isEqualTo(versionBefore)
        drive(listOf<GameSession>(second.host) + first.guests.values)
        runUntil { transport.result != null }
        assertThat(first.guests.values.all { it.snapshot.value?.view?.result != null }).isTrue()
    }

    // ---- 네트워크 오류 -----------------------------------------------------------------------------

    @Test
    fun `전송 실패와 응답 지연은 NetworkError`() = runTest {
        val failing = object : GameTransport.Guest {
            override fun observeMyView(gameId: String, me: PlayerId): Flow<ViewEnvelope> = kotlinx.coroutines.flow.emptyFlow()
            override fun connection(gameId: String): Flow<ConnectionState> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun sendCommand(gameId: String, command: Command): AckResult = throw TransportException("offline")
        }
        val hanging = object : GameTransport.Guest {
            override fun observeMyView(gameId: String, me: PlayerId): Flow<ViewEnvelope> = kotlinx.coroutines.flow.emptyFlow()
            override fun connection(gameId: String): Flow<ConnectionState> = kotlinx.coroutines.flow.emptyFlow()
            override suspend fun sendCommand(gameId: String, command: Command): AckResult = CompletableDeferred<AckResult>().await()
        }
        val cmd = Command.DeclareAction(pid(1), ActionId("income"))
        val a = RemoteGameSession("mp", pid(1), failing, emptyMap(), backgroundScope)
        val b = RemoteGameSession("mp", pid(1), hanging, emptyMap(), backgroundScope, ackTimeout = 3.seconds)
        assertThat(a.submit(cmd)).isEqualTo(SubmitResult.NetworkError)
        assertThat(b.submit(cmd)).isEqualTo(SubmitResult.NetworkError)
        assertThat(testScheduler.currentTime).isEqualTo(3_000L)
    }

    @Test
    fun `전송이 게시에 실패해도 호스트 게임은 계속 진행되고 연결 상태만 바뀐다`() = runTest {
        var failures = 0
        val flaky = object : GameTransport.Host by InMemoryTransport().host {
            override suspend fun publish(gameId: String, publication: Publication) {
                if (publication.version == 1L) { failures++; throw TransportException("write failed") }
            }
        }
        val setup = GameSetup("mp", ids(2), BuiltinRules.classicConfig(), 1, firstPlayer = pid(0))
        val host = HostGameSession(
            engine, engine.newGame(setup), listOf(HostSeat.Local(pid(0), "a"), HostSeat.Remote(pid(1), "b")),
            flaky, TimeoutPolicy.None, Clock { 0 }, backgroundScope,
        )
        runCurrent()
        host.submit(Command.DeclareAction(pid(0), ActionId("income")))
        runCurrent()
        assertThat(failures).isEqualTo(1)
        assertThat(host.snapshot.value!!.view.version).isEqualTo(1L)
        assertThat(host.connection.value).isEqualTo(ConnectionState.RECONNECTING)
        host.submit(Command.DeclareAction(pid(0), ActionId("income"))) // 아직 상대 차례 → 거절되어도 상관없다
        assertThat(host.snapshot.value).isNotNull()
    }

    @Test
    fun `호스트 구성 검증`() = runTest {
        val setup = GameSetup("mp", ids(2), BuiltinRules.classicConfig(), 1)
        val state = engine.newGame(setup)
        val transport = InMemoryTransport().host
        val noLocal = runCatching {
            HostGameSession(engine, state, listOf(HostSeat.Remote(pid(0), "a"), HostSeat.Remote(pid(1), "b")), transport, TimeoutPolicy.None, Clock { 0 }, backgroundScope)
        }
        assertThat(noLocal.exceptionOrNull()).isNotNull().isInstanceOf(IllegalArgumentException::class)
        val wrongOrder = runCatching {
            HostGameSession(engine, state, listOf(HostSeat.Remote(pid(1), "b"), HostSeat.Local(pid(0), "a")), transport, TimeoutPolicy.None, Clock { 0 }, backgroundScope)
        }
        assertThat(wrongOrder.exceptionOrNull()).isNotNull().isInstanceOf(IllegalArgumentException::class)
        assertThat(state.seats).isEqualTo(ids(2))
    }

    // ---- JSON 도우미 (게임 상태/뷰 JSON에서 카드 ID 수집) --------------------------------------------

    /** `{"id":N,"role":...}` 모양(카드)의 id를 모두 모은다. */
    private fun cardIdsIn(json: kotlinx.serialization.json.JsonElement): Set<Int> = when (json) {
        is JsonObject -> {
            val self = if (json.keys.containsAll(setOf("id", "role")) && json.getValue("id").jsonPrimitive.content.toIntOrNull() != null) {
                setOf(json.getValue("id").jsonPrimitive.int)
            } else emptySet()
            self + json.values.flatMap { cardIdsIn(it) }
        }
        is JsonArray -> json.flatMap { cardIdsIn(it) }.toSet()
        else -> emptySet()
    }

    /** 이번 갱신의 공개 이벤트(공개/상실/증명)로 모두에게 알려진 카드 ID. */
    private fun publicCardIds(envelope: JsonObject): Set<Int> =
        envelope.getValue("events").jsonArray.flatMap { cardIdsIn(it.jsonObject.getValue("event")) }.toSet()
            .let { all ->
                // 비공개 이벤트(교체로 받은 새 카드, 교환 후보)는 타입 이름으로 제외 — 이 이벤트들은 당사자 외에는 마스킹되어 도착한다.
                envelope.getValue("events").jsonArray
                    .filter { it.jsonObject.getValue("event").jsonObject.getValue("type").jsonPrimitive.content in PUBLIC_EVENTS }
                    .flatMap { cardIdsIn(it.jsonObject.getValue("event")) }.toSet()
            }

    /** 내 손패와 내가 교환 중일 때의 후보(내 뷰의 결정). */
    private fun myCardIds(envelope: JsonObject): Set<Int> {
        val view = envelope.getValue("view").jsonObject
        val mine = view["me"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { cardIdsIn(it) }.orEmpty()
        val decision = view["myDecision"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { cardIdsIn(it) }.orEmpty()
        val privateEvents = envelope.getValue("events").jsonArray
            .filter { it.jsonObject.getValue("event").jsonObject.getValue("type").jsonPrimitive.content in PRIVATE_EVENTS }
            .flatMap { cardIdsIn(it) }
        return mine + decision + privateEvents
    }

    /** 호스트 상태 백업에서 [exceptFor]를 뺀 모든 사람의 미공개 카드 + 덱의 카드 ID. */
    private fun hiddenCardIds(backup: String, exceptFor: PlayerId): Set<Int> {
        val state = EngineJson.json.parseToJsonElement(backup).jsonObject
        val fromPlayers = state.getValue("players").jsonObject
            .filterKeys { it != exceptFor.value }
            .values.flatMap { p ->
                p.jsonObject.getValue("influences").jsonArray
                    .filter { !it.jsonObject.getValue("revealed").jsonPrimitive.content.toBoolean() }
                    .flatMap { cardIdsIn(it.jsonObject.getValue("card")) }
            }
        val deck = state.getValue("deck").jsonArray.flatMap { cardIdsIn(it) }
        return (fromPlayers + deck).toSet()
    }

    private companion object {
        val PUBLIC_EVENTS = setOf("card_revealed", "influence_lost", "card_replaced_hidden")
        val PRIVATE_EVENTS = setOf("card_replaced", "exchange_drawn")
    }
}
