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
