package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.RatingPolicy
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.view.Viewer
import io.github.lhd980820.coup.remote.GameSeeds
import io.github.lhd980820.coup.runtime.CommandSource
import io.github.lhd980820.coup.runtime.SeatKind
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 기록 한 건(서버가 읽은 그대로). [at]은 그 기록을 쓴 배치의 서버 시각(epoch millis). */
public data class LogRecord(val version: Long, val command: Command, val source: CommandSource, val ref: String?, val at: Long)

/** 게스트가 직접 쓴 명령 문서. 보안 규칙이 [senderUid] = 쓴 사람임을 보장한다. */
public data class GuestCommand(val id: String, val senderUid: String, val command: Command)

/** 한 판을 판정하는 데 필요한 모든 것. 서버가 관리자 권한으로 모은다. */
public data class GameRecord(
    val gameId: String,
    val hostUid: String,
    /** 게임 문서의 사람 좌석(규칙이 "방에 스스로 들어온 사람 전원"임을 보장). */
    val humanSeats: List<String>,
    val rated: Boolean,
    val ruleSetConfig: RuleSetConfig,
    /** 게임 문서를 만든 서버 시각. 첫 결정의 시간 초과 기준. */
    val createdAt: Long,
    /** 실행 설정(방장 시드). 게임 문서와 같은 배치에서만 만들 수 있고 고칠 수 없다. 실제 시드는 [createdAt]과 섞는다(ADR 0016). */
    val setup: GameSetup?,
    val log: List<LogRecord>,
    val guestCommands: Map<String, GuestCommand>,
    /** 방장이 기록한 결과의 순위(승자부터). */
    val resultRanking: List<String>,
)

public enum class RejectReason {
    NO_SETUP,
    SETUP_MISMATCH,
    LOG_GAP,
    ILLEGAL_COMMAND,
    FORGED_GUEST_COMMAND,
    UNKNOWN_ACTOR,
    BAD_TIMEOUT,
    EARLY_TIMEOUT,
    NOT_FINISHED,
    RANKING_MISMATCH,
}

public sealed interface Verdict {
    /** 검증됨. [ranking]과 [ratingDeltas]는 서버가 다시 돌려 얻은 값이다(방장이 적은 값이 아니다). */
    public data class Verified(val ranking: List<String>, val ratingDeltas: Map<String, Int>) : Verdict
    public data class Rejected(val reason: RejectReason, val detail: String) : Verdict
}

/**
 * 끝난 게임을 시드부터 기록대로 다시 돌려 판정한다(ADR 0015). 순수 함수 — 입력이 같으면 판정이 같다.
 *
 * 확인하는 것:
 * 1. 실행 설정이 있고, 좌석·룰셋이 게임 문서와 같다(rated면 봇·하우스룰 없음).
 * 2. 기록이 1부터 빈틈없이 이어지고, 모든 명령이 그 시점에 합법이다(엔진이 받아들이고 버전이 맞는다).
 * 3. 게스트의 수는 그 게스트가 실제로 쓴 명령 문서를 가리킨다(같은 문서를 두 번 쓰지 않는다).
 * 4. 시간 초과는 엔진의 기본 수 그대로이고, 사람 게스트의 경우 결정이 시작된 뒤 정해진 시간이 지난 다음이다(서버 시각 기준).
 * 5. 게임이 끝났고 순위가 방장이 적은 결과와 같다.
 *
 * 확인하지 못하는 것(남는 위험): 방장이 남의 패를 보는 것, 게스트의 명령을 일부러 처리하지 않는 것(게스트에게는 시간 초과와 같은 효과),
 * 판을 여러 번 새로 시작해 좋은 패를 고르는 것.
 */
public class GameVerifier(
    private val engine: GameEngine,
    private val timeoutPolicy: TimeoutPolicy = TimeoutPolicy.standard(),
    private val slackMillis: Long = DEFAULT_SLACK_MILLIS,
    private val ratingPolicy: RatingPolicy = TableRatingPolicy,
) {
    public fun verify(record: GameRecord): Verdict {
        val setup = record.setup ?: return reject(RejectReason.NO_SETUP, "no setup document")
        checkSetup(record, setup)?.let { return it }

        var state = try {
            // 방장 시드에 게임 문서의 서버 생성 시각을 섞은 시드로 둔다(ADR 0016) — 섞지 않고 둔 판은 기록이 맞지 않는다
            engine.newGame(GameSeeds.effective(setup, record.createdAt))
        } catch (e: IllegalArgumentException) {
            return reject(RejectReason.SETUP_MISMATCH, "setup does not build a game: ${e.message}")
        }
        val humans = record.humanSeats.toSet()
        val guests = humans - record.hostUid
        val usedRefs = mutableSetOf<String>()
        val decisions = DecisionClock(engine)
        decisions.update(state, record.createdAt)

        record.log.sortedBy { it.version }.forEachIndexed { i, entry ->
            if (entry.version != i + 1L) return reject(RejectReason.LOG_GAP, "expected v${i + 1} but found v${entry.version}")
            val actor = entry.command.actor
            if (actor !in setup.seats) return reject(RejectReason.UNKNOWN_ACTOR, "v${entry.version}: ${actor.value} is not a seat")
            when (entry.source) {
                CommandSource.SEAT -> if (actor.value in guests) {
                    val ref = entry.ref ?: return reject(RejectReason.FORGED_GUEST_COMMAND, "v${entry.version}: a guest move without its command document")
                    val sent = record.guestCommands[ref] ?: return reject(RejectReason.FORGED_GUEST_COMMAND, "v${entry.version}: command $ref does not exist")
                    if (sent.senderUid != actor.value) return reject(RejectReason.FORGED_GUEST_COMMAND, "v${entry.version}: command $ref was sent by ${sent.senderUid}")
                    if (!sameMove(sent.command, entry.command)) return reject(RejectReason.FORGED_GUEST_COMMAND, "v${entry.version}: the logged move differs from command $ref")
                    if (!usedRefs.add(ref)) return reject(RejectReason.FORGED_GUEST_COMMAND, "v${entry.version}: command $ref used twice")
                }
                CommandSource.TIMEOUT -> {
                    if (actor !in engine.pendingDeciders(state)) return reject(RejectReason.BAD_TIMEOUT, "v${entry.version}: ${actor.value} had no decision")
                    if (entry.command != engine.timeoutCommand(state, actor)) return reject(RejectReason.BAD_TIMEOUT, "v${entry.version}: not the engine's default move")
                    if (actor.value in guests) {
                        val request = engine.legalOptions(state, actor) ?: return reject(RejectReason.BAD_TIMEOUT, "v${entry.version}: no decision")
                        val limit = timeoutPolicy.durationFor(request, SeatKind.REMOTE_HUMAN)
                            ?: return reject(RejectReason.BAD_TIMEOUT, "v${entry.version}: this decision has no time limit")
                        val waited = entry.at - decisions.startedAt(actor)
                        if (waited < limit.inWholeMilliseconds - slackMillis) {
                            return reject(RejectReason.EARLY_TIMEOUT, "v${entry.version}: ${actor.value} timed out after ${waited}ms (limit $limit)")
                        }
                    }
                }
            }
            when (val applied = engine.apply(state, entry.command)) {
                is ApplyResult.Rejected -> return reject(RejectReason.ILLEGAL_COMMAND, "v${entry.version}: ${applied.reason}")
                is ApplyResult.Accepted -> {
                    if (applied.state.version != entry.version) {
                        return reject(RejectReason.ILLEGAL_COMMAND, "v${entry.version}: replay reached v${applied.state.version}")
                    }
                    state = applied.state
                }
            }
            decisions.update(state, entry.at)
        }

        if (!state.isOver) return reject(RejectReason.NOT_FINISHED, "the replayed game is not over (v${state.version})")
        val ranking = checkNotNull(engine.view(state, Viewer.Spectator).result).ranking.map { it.value }
        if (ranking != record.resultRanking) return reject(RejectReason.RANKING_MISMATCH, "replayed $ranking but the result says ${record.resultRanking}")
        val deltas = if (record.rated) ratingPolicy.ratingDeltas(ranking.map(::PlayerId)).mapKeys { it.key.value } else emptyMap()
        return Verdict.Verified(ranking, deltas)
    }

    private fun checkSetup(record: GameRecord, setup: GameSetup): Verdict.Rejected? {
        if (setup.gameId != record.gameId) return reject(RejectReason.SETUP_MISMATCH, "setup of another game")
        if (setup.ruleSetConfig != record.ruleSetConfig) return reject(RejectReason.SETUP_MISMATCH, "rules differ from the game document")
        val order = setup.seats.map { it.value }
        if (order.toSet().size != order.size) return reject(RejectReason.SETUP_MISMATCH, "duplicate seats")
        if (!order.containsAll(record.humanSeats)) return reject(RejectReason.SETUP_MISMATCH, "a human seat is missing from the game")
        if (record.hostUid !in record.humanSeats) return reject(RejectReason.SETUP_MISMATCH, "the host is not seated")
        if (record.rated) {
            if (order.toSet() != record.humanSeats.toSet()) return reject(RejectReason.SETUP_MISMATCH, "a rated game has non-human seats")
            if (setup.ruleSetConfig.houseRules.isNotEmpty() || setup.ruleSetConfig.paramOverrides.isNotEmpty()) {
                return reject(RejectReason.SETUP_MISMATCH, "a rated game uses changed rules")
            }
        }
        return null
    }

    /** 같은 수인가 — 버전 조건은 빼고 비교한다(권한자는 결정이 그대로면 버전 조건 없이 다시 적용한다). */
    private fun sameMove(a: Command, b: Command): Boolean = withoutVersion(a) == withoutVersion(b)

    private fun withoutVersion(c: Command): JsonObject =
        JsonObject(EngineJson.json.encodeToJsonElement(Command.serializer(), c).jsonObject - "expectedVersion")

    private fun reject(reason: RejectReason, detail: String) = Verdict.Rejected(reason, detail)

    public companion object {
        /** 방장 기기에서 상태가 바뀐 때와 그 게시가 서버에 닿은 때의 차이를 너그럽게 봐 준다. */
        public const val DEFAULT_SLACK_MILLIS: Long = 3_000
    }
}

/**
 * 각 플레이어의 지금 결정이 언제(서버 시각) 시작됐는가. 권한자([io.github.lhd980820.coup.runtime.GameAuthority])와 같은 기준:
 * 같은 턴의 같은 결정 요청이 이어지는 동안은 같은 결정이다(다른 사람이 통과해도 내 마감은 그대로).
 */
private class DecisionClock(private val engine: GameEngine) {
    private val started = mutableMapOf<PlayerId, Pair<Any, Long>>()

    fun update(state: GameState, at: Long) {
        val deciders = engine.pendingDeciders(state)
        started.keys.retainAll(deciders)
        for (p in deciders) {
            val request = engine.legalOptions(state, p) ?: continue
            val signature = state.turn.number to request
            if (started[p]?.first != signature) started[p] = signature to at
        }
    }

    fun startedAt(player: PlayerId): Long = started[player]?.second ?: Long.MAX_VALUE
}
