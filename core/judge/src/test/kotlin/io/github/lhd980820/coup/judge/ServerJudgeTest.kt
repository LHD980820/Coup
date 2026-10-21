package io.github.lhd980820.coup.judge

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.remote.StoredDocument
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.remote.seedStartedRoom
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.HostSeat
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * 서버 판정(ADR 0015): 규칙을 강제하는 FakeFirestore 위에서 방장 권위 세션 + 원격 게스트로 실제 한 판을 끝내고,
 * 관리자 권한으로 판정한다. 그다음 방장이 할 수 있는(또는 규칙을 피해 했다고 가정한) 조작마다 판정이 거부하는지 본다.
 */
class ServerJudgeTest {
    private companion object {
        const val START_AT = 1_000_000L
    }

    private val engine = GameEngines.create()
    private val gameId = "g-judge"
    private val uids = listOf("host", "b", "c")

    /** 사람 게스트 시간 제한(검증기와 방장이 같은 정책을 쓴다). */
    private val shortLimits = TimeoutPolicy { _, _ -> 10.seconds }

    private class Table(val fs: FakeFirestore, val host: HostGameSession, val guests: List<RemoteGameSession>)

    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private suspend fun TestScope.table(
        players: List<String> = uids,
        rated: Boolean = true,
        policy: TimeoutPolicy = TimeoutPolicy.None,
        seed: Long = 11,
        firstPlayer: String = players.first(),
    ): Table {
        // 서버 시각이 0이 아닌 때 시작한다(게임 시작 시각을 기준으로 재는지 확인할 수 있게)
        advanceTimeBy(START_AT)
        val fs = FakeFirestore().apply { now = { testScheduler.currentTime } }
        players.forEach { FirestoreUserRepository(fs.storeFor(it), it, null).ensureProfile("player-$it") }
        val roomId = fs.seedStartedRoom(gameId, players.first(), players, rated)
        val config = BuiltinRules.classicConfig().let { if (rated) it else it.copy(houseRules = setOf("last_stand")) }
        val setup = GameSetup(gameId, players.map(::PlayerId), config, seed, firstPlayer = PlayerId(firstPlayer))
        val hostTransport = FirestoreGameTransport(fs.storeFor(players.first()), clock())
        hostTransport.createGame(gameId, roomId, players.first(), players, config, rated, setup = setup)
        val seats = players.mapIndexed { i, id -> if (i == 0) HostSeat.Local(PlayerId(id), id) else HostSeat.Remote(PlayerId(id), id) }
        val host = HostGameSession(engine, engine.newGame(setup), seats, hostTransport.host, policy, clock(), backgroundScope)
        val info = seats.associate { it.id to SeatInfo(it.displayName, SeatKind.REMOTE_HUMAN) }
        val guests = players.drop(1).map { id ->
            RemoteGameSession(gameId, PlayerId(id), FirestoreGameTransport(fs.storeFor(id), clock()).guest(id), info, backgroundScope)
        }
        return Table(fs, host, guests)
    }

    private fun TestScope.drive(sessions: List<GameSession>) {
        sessions.forEach { session ->
            val agent = RandomAgent(checkNotNull(session.me).value.hashCode().toLong())
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

    /** 한 판을 끝까지(결과 문서가 생길 때까지) 돌린다. */
    private suspend fun TestScope.finishedGame(
        rated: Boolean = true,
        policy: TimeoutPolicy = TimeoutPolicy.None,
        guestsPlay: Boolean = true,
        players: List<String> = uids,
        firstPlayer: String = players.first(),
    ): Table {
        val t = table(players, rated, policy, firstPlayer = firstPlayer)
        drive(if (guestsPlay) listOf<GameSession>(t.host) + t.guests else listOf(t.host))
        runCurrent()
        val start = testScheduler.currentTime
        while (t.fs.docs[FirestoreSchema.result(gameId)] == null) {
            check(testScheduler.currentTime - start < 6 * 3_600_000L) { "the game did not finish" }
            advanceTimeBy(500)
            runCurrent()
        }
        return t
    }

    private fun TestScope.judge(t: Table, policy: TimeoutPolicy = TimeoutPolicy.None) =
        ServerJudge(t.fs.admin(), engine, GameVerifier(engine, policy), clock())

    private suspend fun log(t: Table): List<StoredDocument> =
        t.fs.admin().observeOrdered(FirestoreSchema.log(gameId), FirestoreSchema.Log.VERSION, descending = false, limit = 10_000).first()

    private fun StoredDocument.str(key: String) = data[key]?.jsonPrimitive?.content
    private fun StoredDocument.command() = EngineJson.json.decodeFromString(Command.serializer(), checkNotNull(str(FirestoreSchema.Log.COMMAND_JSON)))
    private fun StoredDocument.isGuestMove() = str(FirestoreSchema.Log.SOURCE) == "SEAT" && data[FirestoreSchema.Log.REF] != null

    private suspend fun Table.admin(vararg ops: WriteOp) = fs.admin().write(ops.toList())
    private fun Table.merge(path: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = WriteOp.Merge(path, buildJsonObject(block))
    private fun Table.rating(uid: String) = fs.docs.getValue(FirestoreSchema.user(uid))[FirestoreSchema.User.RATING]!!.jsonPrimitive.long.toInt()
    private fun Table.verdict() = fs.docs.getValue(FirestoreSchema.game(gameId))[FirestoreSchema.Game.VERDICT]?.jsonPrimitive?.content

    private suspend fun TestScope.rejectedFor(t: Table, policy: TimeoutPolicy = TimeoutPolicy.None): RejectReason {
        val outcome = judge(t, policy).judge(gameId)
        assertThat(outcome).isInstanceOf(JudgeOutcome.Rejected::class)
        assertThat(t.verdict()).isEqualTo(ServerJudge.VERDICT_REJECTED)
        // 아무 레이팅도 바뀌지 않는다
        uids.filter { t.fs.docs.containsKey(FirestoreSchema.user(it)) }.forEach { assertThat(t.rating(it)).isEqualTo(1000) }
        return (outcome as JudgeOutcome.Rejected).verdict.reason
    }

    // ---- 정상 -----------------------------------------------------------------------------------

    @Test
    fun `정상 판 - 검증되어 서버가 계산한 변동량을 모두에게 한 번만 반영하고 정산 대기를 지운다`() = runTest {
        val t = finishedGame()
        val ranking = t.host.snapshot.value!!.view.result!!.ranking
        val outcome = judge(t).judge(gameId)

        assertThat(outcome).isInstanceOf(JudgeOutcome.Verified::class)
        val verified = outcome as JudgeOutcome.Verified
        assertThat(verified.verdict.ranking).isEqualTo(ranking.map { it.value })
        assertThat(verified.applied).containsExactlyInAnyOrder(*uids.toTypedArray())
        val expected = TableRatingPolicy.ratingDeltas(ranking)
        uids.forEach { uid ->
            assertThat(t.rating(uid)).isEqualTo(1000 + expected.getValue(PlayerId(uid)))
            assertThat(t.fs.docs[FirestoreSchema.pendingRating(uid)]).isNull()
            assertThat(t.fs.docs.containsKey(FirestoreSchema.appliedResult(uid, gameId))).isEqualTo(true)
        }
        assertThat(t.verdict()).isEqualTo(ServerJudge.VERDICT_VERIFIED)

        // 두 번째 판정(트리거 중복 전달)은 아무것도 바꾸지 않는다
        assertThat(judge(t).judge(gameId)).isEqualTo(JudgeOutcome.AlreadyJudged)
        uids.forEach { assertThat(t.rating(it)).isEqualTo(1000 + expected.getValue(PlayerId(it))) }
    }

    @Test
    fun `정상 판 - 게스트가 시간 초과로만 둔 판도 정해진 시간이 지난 뒤라면 검증된다`() = runTest {
        // 셋이면 응답 창에서 두 게스트의 시간 초과가 연달아 기록된다 — 두 번째도 창이 열린 때부터 잰다
        val t = finishedGame(policy = shortLimits, guestsPlay = false)
        assertThat(log(t).count { it.str(FirestoreSchema.Log.SOURCE) == "TIMEOUT" }).isNotEqualTo(0)
        assertThat(judge(t, shortLimits).judge(gameId)).isInstanceOf(JudgeOutcome.Verified::class)
    }

    @Test
    fun `점검(inspect)은 판정만 하고 아무것도 쓰지 않는다`() = runTest {
        val t = finishedGame()
        val before = t.fs.docs.toMap()
        assertThat(judge(t).inspect(gameId)).isNotNull().isInstanceOf(Verdict.Verified::class)
        assertThat(t.fs.docs.toMap()).isEqualTo(before)
        assertThat(judge(t).inspect("nope")).isNull()
        val u = finishedGame(rated = false)
        assertThat(judge(u).inspect(gameId)).isNull()
    }

    @Test
    fun `레이팅 없는 판 - 판정만 남기고 레이팅은 그대로`() = runTest {
        val t = finishedGame(rated = false)
        assertThat(judge(t).judge(gameId)).isEqualTo(JudgeOutcome.Unrated)
        assertThat(t.verdict()).isEqualTo(ServerJudge.VERDICT_UNRATED)
        uids.forEach { assertThat(t.rating(it)).isEqualTo(1000) }
        assertThat(judge(t).judge(gameId)).isEqualTo(JudgeOutcome.AlreadyJudged)
    }

    @Test
    fun `결과가 아직 없으면 판정하지 않는다`() = runTest {
        val t = table()
        runCurrent()
        assertThat(judge(t).judge(gameId)).isInstanceOf(JudgeOutcome.NotReady::class)
        assertThat(t.verdict()).isNull()
    }

    // ---- 조작 ------------------------------------------------------------------------------------

    @Test
    fun `결과의 순위를 바꾸면 RANKING_MISMATCH`() = runTest {
        val t = finishedGame()
        val ranking = (t.fs.docs.getValue(FirestoreSchema.result(gameId))[FirestoreSchema.Result.RANKING] as JsonArray).map { it.jsonPrimitive.content }
        t.admin(t.merge(FirestoreSchema.result(gameId)) { put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.reversed().forEach { add(JsonPrimitive(it)) } }) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.RANKING_MISMATCH)
    }

    @Test
    fun `게스트의 수를 없는 명령 문서에 묶으면 FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        val entry = log(t).first { it.isGuestMove() }
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, entry.id.toLong())) { put(FirestoreSchema.Log.REF, "made-up") })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `게스트의 수에서 명령 문서 연결을 지우면(방장이 대신 둔 것) FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        val entry = log(t).first { it.isGuestMove() }
        t.admin(WriteOp.Set(FirestoreSchema.logEntry(gameId, entry.id.toLong()), kotlinx.serialization.json.JsonObject(entry.data - FirestoreSchema.Log.REF)))
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `게스트의 수를 다른 게스트가 보낸 명령 문서에 묶으면 FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        val moves = log(t).filter { it.isGuestMove() }
        val first = moves.first()
        val other = moves.first { it.command().actor != first.command().actor }
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, first.id.toLong())) { put(FirestoreSchema.Log.REF, other.str(FirestoreSchema.Log.REF)) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `다른 게스트가 남의 이름으로 쓴 명령 문서를 그 사람의 수로 기록하면 FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        val entry = log(t).first { it.isGuestMove() }
        val victim = entry.command().actor.value
        val accomplice = uids.drop(1).first { it != victim }
        // 공범이 쓴 문서(보낸 사람 = 공범)에 피해자의 수와 똑같은 내용을 담는다 — 규칙은 명령 내용까지 보지 않는다
        t.admin(
            WriteOp.Set(
                FirestoreSchema.command(gameId, "planted"),
                buildJsonObject {
                    put(FirestoreSchema.Command.SENDER_UID, accomplice)
                    put(FirestoreSchema.Command.COMMAND_JSON, checkNotNull(entry.str(FirestoreSchema.Log.COMMAND_JSON)))
                    put(FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.APPLIED)
                },
            ),
            t.merge(FirestoreSchema.logEntry(gameId, entry.id.toLong())) { put(FirestoreSchema.Log.REF, "planted") },
        )
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `게스트가 보낸 것과 다른 수를 기록하면 FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        val entry = log(t).first { it.isGuestMove() }
        val ref = checkNotNull(entry.str(FirestoreSchema.Log.REF))
        val concede = EngineJson.json.encodeToString(Command.serializer(), Command.Concede(entry.command().actor))
        t.admin(t.merge(FirestoreSchema.command(gameId, ref)) { put(FirestoreSchema.Command.COMMAND_JSON, concede) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `게스트가 보낸 명령 문서 하나를 두 수에 쓰면 FORGED_GUEST_COMMAND`() = runTest {
        val t = finishedGame()
        // 같은 게스트의 같은 수(통과) 둘 — 두 번째를 첫 번째 명령 문서에 묶는다
        val passes = log(t).filter { it.isGuestMove() && it.command() is Command.Pass }.groupBy { it.command().actor }.values.first { it.size >= 2 }
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, passes[1].id.toLong())) { put(FirestoreSchema.Log.REF, passes[0].str(FirestoreSchema.Log.REF)) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `마지막 기록을 지우면 끝나지 않은 판이라 NOT_FINISHED`() = runTest {
        val t = finishedGame()
        t.admin(WriteOp.Delete(FirestoreSchema.logEntry(gameId, log(t).size.toLong())))
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.NOT_FINISHED)
    }

    @Test
    fun `실행 설정의 좌석에서 사람을 빼면 SETUP_MISMATCH`() = runTest {
        val t = finishedGame()
        t.admin(t.merge(FirestoreSchema.setup(gameId)) { put(FirestoreSchema.Setup.ORDER, buildJsonArray { uids.drop(1).forEach { add(JsonPrimitive(it)) } }) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `레이팅 판인데 게임 문서와 실행 설정 모두 하우스룰이면 SETUP_MISMATCH`() = runTest {
        val t = finishedGame()
        val changed = EngineJson.json.encodeToString(
            io.github.lhd980820.coup.engine.rules.RuleSetConfig.serializer(),
            BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand")),
        )
        t.admin(
            t.merge(FirestoreSchema.setup(gameId)) { put(FirestoreSchema.Setup.RULE_SET_CONFIG_JSON, changed) },
            t.merge(FirestoreSchema.game(gameId)) { put(FirestoreSchema.Game.RULE_SET_CONFIG_JSON, changed) },
        )
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `기록 하나를 지우면 LOG_GAP`() = runTest {
        val t = finishedGame()
        t.admin(WriteOp.Delete(FirestoreSchema.logEntry(gameId, 2)))
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.LOG_GAP)
    }

    @Test
    fun `엔진이 받지 않는 수를 기록하면 ILLEGAL_COMMAND`() = runTest {
        val t = finishedGame()
        val bogus = EngineJson.json.encodeToString(Command.serializer(), Command.DeclareAction(PlayerId("host"), ActionId("no_such_action")))
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, 1)) { put(FirestoreSchema.Log.COMMAND_JSON, bogus) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.ILLEGAL_COMMAND)
    }

    @Test
    fun `좌석에 없는 사람의 수를 기록하면 UNKNOWN_ACTOR`() = runTest {
        val t = finishedGame()
        val bogus = EngineJson.json.encodeToString(Command.serializer(), Command.Concede(PlayerId("stranger")))
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, 1)) { put(FirestoreSchema.Log.COMMAND_JSON, bogus) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.UNKNOWN_ACTOR)
    }

    @Test
    fun `실행 설정이 없으면 NO_SETUP, 시드가 다르면 다시 돌린 판이 어긋나 거부`() = runTest {
        val t = finishedGame()
        t.admin(t.merge(FirestoreSchema.setup(gameId)) { put(FirestoreSchema.Setup.SEED, 12345L) })
        assertThat(rejectedFor(t)).isNotEqualTo(RejectReason.NO_SETUP)

        val u = finishedGame()
        u.admin(WriteOp.Delete(FirestoreSchema.setup(gameId)))
        assertThat(rejectedFor(u)).isEqualTo(RejectReason.NO_SETUP)
    }

    @Test
    fun `게임 문서의 룰셋과 실행 설정의 룰셋이 다르면 SETUP_MISMATCH`() = runTest {
        val t = finishedGame()
        val changed = EngineJson.json.encodeToString(
            io.github.lhd980820.coup.engine.rules.RuleSetConfig.serializer(),
            BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand")),
        )
        t.admin(t.merge(FirestoreSchema.setup(gameId)) { put(FirestoreSchema.Setup.RULE_SET_CONFIG_JSON, changed) })
        assertThat(rejectedFor(t)).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `게스트의 시간 초과를 정해진 시간보다 일찍 처리하면 EARLY_TIMEOUT`() = runTest {
        val t = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2))
        val entry = log(t).first { it.str(FirestoreSchema.Log.SOURCE) == "TIMEOUT" }
        val at = entry.data.getValue(FirestoreSchema.Log.AT).jsonPrimitive.long
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, entry.id.toLong())) { put(FirestoreSchema.Log.AT, at - 8_000) })
        assertThat(rejectedFor(t, shortLimits)).isEqualTo(RejectReason.EARLY_TIMEOUT)
    }

    @Test
    fun `첫 결정이 게스트의 것이면 게임 시작 시각부터 잰다`() = runTest {
        val t = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2), firstPlayer = "b")
        val entry = log(t).first()
        assertThat(entry.str(FirestoreSchema.Log.SOURCE)).isEqualTo("TIMEOUT")
        assertThat(judge(t, shortLimits).judge(gameId)).isInstanceOf(JudgeOutcome.Verified::class)

        val u = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2), firstPlayer = "b")
        val first = log(u).first()
        val at = first.data.getValue(FirestoreSchema.Log.AT).jsonPrimitive.long
        u.admin(u.merge(FirestoreSchema.logEntry(gameId, 1)) { put(FirestoreSchema.Log.AT, at - 8_000) })
        assertThat(rejectedFor(u, shortLimits)).isEqualTo(RejectReason.EARLY_TIMEOUT)
    }

    @Test
    fun `결정이 없는 사람을 시간 초과 처리하면 BAD_TIMEOUT`() = runTest {
        val t = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2))
        // 게스트의 행동 선택 차례(방장은 결정이 없다)에 방장이 시간 초과로 통과했다고 기록
        val entry = log(t).first { it.str(FirestoreSchema.Log.SOURCE) == "TIMEOUT" && it.command() is Command.DeclareAction }
        val pass = EngineJson.json.encodeToString(Command.serializer(), Command.Pass(PlayerId("host")))
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, entry.id.toLong())) { put(FirestoreSchema.Log.COMMAND_JSON, pass) })
        assertThat(rejectedFor(t, shortLimits)).isEqualTo(RejectReason.BAD_TIMEOUT)
    }

    @Test
    fun `시간 초과로 기본 수가 아닌 수를 두면 BAD_TIMEOUT`() = runTest {
        val t = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2))
        val entry = log(t).first { it.str(FirestoreSchema.Log.SOURCE) == "TIMEOUT" }
        val concede = EngineJson.json.encodeToString(Command.serializer(), Command.Concede(entry.command().actor))
        t.admin(t.merge(FirestoreSchema.logEntry(gameId, entry.id.toLong())) { put(FirestoreSchema.Log.COMMAND_JSON, concede) })
        assertThat(rejectedFor(t, shortLimits)).isEqualTo(RejectReason.BAD_TIMEOUT)
    }

    @Test
    fun `시간 제한이 없는 결정을 시간 초과로 처리하면 BAD_TIMEOUT`() = runTest {
        val t = finishedGame(policy = shortLimits, guestsPlay = false, players = uids.take(2))
        // 방장은 10초 제한으로 돌렸지만 서버 정책에는 제한이 없다 -> 시간 초과 자체가 근거 없음
        assertThat(rejectedFor(t, TimeoutPolicy.None)).isEqualTo(RejectReason.BAD_TIMEOUT)
    }
}
