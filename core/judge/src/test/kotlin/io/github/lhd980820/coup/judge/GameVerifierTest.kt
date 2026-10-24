package io.github.lhd980820.coup.judge

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.remote.GameSeeds
import io.github.lhd980820.coup.runtime.CommandSource
import org.junit.jupiter.api.Test

/** 검증기 자체(저장소 없이). 서버 판정 경로로는 닿지 않는 경우 — 봇이 있는 비레이팅 판의 설정 검사 — 를 본다. */
class GameVerifierTest {
    private companion object {
        const val CREATED_AT = 1_700_000_000_123L
    }

    private val engine = GameEngines.create()
    private val verifier = GameVerifier(engine)
    private val config = BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"))
    private val setup = GameSetup("g", listOf("host", "bot1", "bot2").map(::PlayerId), config, seed = 3, firstPlayer = PlayerId("host"))

    /** 봇 둘과 방장이 끝까지 둔 판(모두 방장 기기의 좌석이므로 명령 문서가 없다). */
    private val record: GameRecord = run {
        var state = engine.newGame(GameSeeds.effective(setup, CREATED_AT))
        val agents = setup.seats.associateWith { RandomAgent(it.value.hashCode().toLong()) }
        val log = mutableListOf<LogRecord>()
        while (!state.isOver) {
            val actor = engine.pendingDeciders(state).first()
            val view = engine.view(state, Viewer.Player(actor))
            val command = agents.getValue(actor).decide(view, checkNotNull(view.myDecision))
            state = (engine.apply(state, command) as ApplyResult.Accepted).state
            log += LogRecord(state.version, command, CommandSource.SEAT, ref = null, at = 0)
        }
        GameRecord(
            gameId = "g",
            hostUid = "host",
            humanSeats = listOf("host"),
            rated = false,
            ruleSetConfig = config,
            createdAt = CREATED_AT,
            setup = setup,
            log = log,
            guestCommands = emptyMap(),
            resultRanking = checkNotNull(engine.view(state, Viewer.Spectator).result).ranking.map { it.value },
        )
    }

    @Test
    fun `시드 섞기 - 같은 입력이면 같고, 생성 시각이 1ms만 달라도 다르다`() {
        assertThat(GameSeeds.mix(3, CREATED_AT)).isEqualTo(GameSeeds.mix(3, CREATED_AT))
        assertThat(GameSeeds.mix(3, CREATED_AT + 1) == GameSeeds.mix(3, CREATED_AT)).isEqualTo(false)
        assertThat(GameSeeds.mix(4, CREATED_AT) == GameSeeds.mix(3, CREATED_AT)).isEqualTo(false)
        // 생성 시각이 다르게 적힌 기록(= 다른 시드로 둔 판)은 다시 돌리면 어긋난다
        assertThat(verifier.verify(record.copy(createdAt = CREATED_AT + 1))).isInstanceOf(Verdict.Rejected::class)
    }

    private fun rejected(r: GameRecord) = (verifier.verify(r) as Verdict.Rejected).reason

    // ---- 방장이 바뀐 판(ADR 0017) ------------------------------------------------------------------

    private val humans = listOf("h", "b", "c")
    private val classic = BuiltinRules.classicConfig()
    private val humanSetup = GameSetup("m", humans.map(::PlayerId), classic, seed = 8, firstPlayer = PlayerId("h"))

    /** 사람 셋이 끝까지 둔 판. [switchAfter]번째 기록까지는 h가, 그 뒤로는 b가 방장(기록을 쓴 사람). 방장이 아닌 사람의 수는 명령 문서가 있다. */
    private fun migrated(switchAfter: Long): GameRecord {
        var state = engine.newGame(GameSeeds.effective(humanSetup, CREATED_AT))
        val agents = humanSetup.seats.associateWith { RandomAgent(it.value.hashCode().toLong() + 7) }
        val log = mutableListOf<LogRecord>()
        val commands = mutableMapOf<String, GuestCommand>()
        while (!state.isOver) {
            val actor = engine.pendingDeciders(state).first()
            val view = engine.view(state, Viewer.Player(actor))
            val command = agents.getValue(actor).decide(view, checkNotNull(view.myDecision))
            state = (engine.apply(state, command) as ApplyResult.Accepted).state
            val writer = if (state.version <= switchAfter) "h" else "b"
            val ref = if (actor.value != writer) "cmd-${state.version}".also { commands[it] = GuestCommand(it, actor.value, command) } else null
            log += LogRecord(state.version, command, CommandSource.SEAT, ref = ref, at = 0, writer = writer)
        }
        return GameRecord(
            gameId = "m", hostUid = "b", humanSeats = humans, rated = true, ruleSetConfig = classic, createdAt = CREATED_AT,
            setup = humanSetup, log = log, guestCommands = commands,
            resultRanking = checkNotNull(engine.view(state, Viewer.Spectator).result).ranking.map { it.value },
        )
    }

    @Test
    fun `방장이 바뀐 판 - 구간마다 그때의 방장만 명령 문서 없이 두었으면 검증된다`() {
        val r = migrated(switchAfter = 6)
        assertThat(r.log.map { it.writer }.toSet()).isEqualTo(setOf("h", "b"))
        assertThat(verifier.verify(r)).isInstanceOf(Verdict.Verified::class)
    }

    @Test
    fun `방장이 바뀐 판 - 새 방장이 옛 방장의 수를 명령 문서 없이 기록하면 위조`() {
        val r = migrated(switchAfter = 6)
        // 이어받은 뒤 h(이제 게스트)의 수에서 명령 문서 연결을 지운다 = b가 h 대신 둔 것
        val i = r.log.indexOfFirst { it.writer == "b" && it.command.actor.value == "h" }
        check(i >= 0)
        val forged = r.copy(log = r.log.toMutableList().also { it[i] = it[i].copy(ref = null) })
        assertThat(rejected(forged)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
    }

    @Test
    fun `방장이 바뀐 판 - 사람 좌석이 아닌 이름으로 쓴 기록, 이름 없는 옛 기록은 게임 문서의 방장으로 본다`() {
        val r = migrated(switchAfter = 6)
        val stranger = r.copy(log = r.log.mapIndexed { i, e -> if (i == 0) e.copy(writer = "stranger") else e })
        assertThat(rejected(stranger)).isEqualTo(RejectReason.UNKNOWN_ACTOR)
        // 이름이 없으면 게임 문서의 방장(b)이 처음부터 썼다고 본다 — 그러면 앞 구간의 b의 수(명령 문서 있음)는 괜찮지만
        // h의 수에는 명령 문서가 없으므로 위조가 된다
        val unnamed = r.copy(log = r.log.map { it.copy(writer = null) })
        assertThat(rejected(unnamed)).isEqualTo(RejectReason.FORGED_GUEST_COMMAND)
        // 처음부터 끝까지 b가 방장(첫 좌석이 아니다)인 이름 없는 옛 기록은 게임 문서의 방장 b가 쓴 것으로 보고 검증된다
        val allB = migrated(switchAfter = 0)
        assertThat(verifier.verify(allB.copy(log = allB.log.map { it.copy(writer = null) }))).isInstanceOf(Verdict.Verified::class)
    }


    @Test
    fun `봇이 있는 비레이팅 판은 검증되고 변동량이 없다`() {
        val verdict = verifier.verify(record)
        assertThat(verdict).isInstanceOf(Verdict.Verified::class)
        assertThat((verdict as Verdict.Verified).ratingDeltas).isEqualTo(emptyMap())
    }

    @Test
    fun `다른 게임의 실행 설정이면 SETUP_MISMATCH`() {
        assertThat(rejected(record.copy(setup = setup.copy(gameId = "other")))).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `게임 문서와 룰셋이 다르면 SETUP_MISMATCH`() {
        assertThat(rejected(record.copy(ruleSetConfig = BuiltinRules.classicConfig()))).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `게임 문서의 사람이 실행 설정의 좌석에 없으면 SETUP_MISMATCH`() {
        assertThat(rejected(record.copy(humanSeats = listOf("host", "someone")))).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `방장이 좌석에 없거나 좌석이 겹치면 SETUP_MISMATCH`() {
        assertThat(rejected(record.copy(hostUid = "bot1", humanSeats = listOf("host")))).isEqualTo(RejectReason.SETUP_MISMATCH)
        val doubled = setup.copy(seats = setup.seats + PlayerId("host"))
        assertThat(rejected(record.copy(setup = doubled))).isEqualTo(RejectReason.SETUP_MISMATCH)
    }

    @Test
    fun `레이팅 판에 봇 좌석이 있으면 SETUP_MISMATCH`() {
        val classic = BuiltinRules.classicConfig()
        val r = record.copy(rated = true, ruleSetConfig = classic, setup = setup.copy(ruleSetConfig = classic))
        assertThat(rejected(r)).isEqualTo(RejectReason.SETUP_MISMATCH)
    }
}
