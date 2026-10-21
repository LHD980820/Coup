package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.account.GameResult
import io.github.lhd980820.coup.account.RatingDecision
import io.github.lhd980820.coup.account.RatingLedger
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.ServerValues
import io.github.lhd980820.coup.remote.UserDocuments
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.CommandSource
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public sealed interface JudgeOutcome {
    /** 검증되어 레이팅을 반영했다. [applied]는 실제로 반영한 사람(이미 반영됐거나 프로필이 없으면 빠진다). */
    public data class Verified(val verdict: Verdict.Verified, val applied: List<String>) : JudgeOutcome
    public data class Rejected(val verdict: Verdict.Rejected) : JudgeOutcome

    /** 레이팅 대상이 아닌 판(봇·하우스룰). 판정만 남긴다. */
    public data object Unrated : JudgeOutcome
    public data object AlreadyJudged : JudgeOutcome

    /** 아직 판정할 수 없다(결과·게임 문서가 없다). 결과가 생기면 다시 불린다. */
    public data class NotReady(val why: String) : JudgeOutcome
}

/**
 * 서버 판정(ADR 0015). 결과 문서(`results/{gameId}`)가 생기면 불린다. [store]는 **관리자 권한**(보안 규칙을 거치지 않는다)이어야 한다.
 * 판을 모아 [GameVerifier]로 검증하고, 통과하면 참가자 전원의 레이팅을 **한 트랜잭션**으로 반영한다(같은 판은 한 번만 — 판정 표시로 막는다).
 * 변동량은 방장이 적은 값이 아니라 서버가 다시 돌려 얻은 순위로 계산한다.
 */
public class ServerJudge(
    private val store: DocumentStore,
    private val engine: GameEngine = GameEngines.create(),
    private val verifier: GameVerifier = GameVerifier(engine),
    private val clock: Clock = Clock.System,
) {
    public suspend fun judge(gameId: String): JudgeOutcome {
        val result = store.get(FirestoreSchema.result(gameId)) ?: return JudgeOutcome.NotReady("no result")
        val game = store.get(FirestoreSchema.game(gameId)) ?: return JudgeOutcome.NotReady("no game")
        if (game.str(FirestoreSchema.Game.VERDICT) != null) return JudgeOutcome.AlreadyJudged
        val rated = game.bool(FirestoreSchema.Game.RATED) == true && result.bool(FirestoreSchema.Result.RATED) == true
        if (!rated) return mark(gameId, VERDICT_UNRATED, null).let { if (it) JudgeOutcome.Unrated else JudgeOutcome.AlreadyJudged }

        val verdict = when (val record = load(gameId, game, result)) {
            is Loaded.Ok -> verifier.verify(record.record)
            is Loaded.Broken -> Verdict.Rejected(record.reason, record.detail)
        }
        return when (verdict) {
            is Verdict.Rejected -> if (mark(gameId, VERDICT_REJECTED, "${verdict.reason}: ${verdict.detail}")) JudgeOutcome.Rejected(verdict) else JudgeOutcome.AlreadyJudged
            is Verdict.Verified -> settle(gameId, verdict, finishedAt = result.long(FirestoreSchema.Result.FINISHED_AT) ?: 0)
        }
    }

    private sealed interface Loaded {
        data class Ok(val record: GameRecord) : Loaded
        data class Broken(val reason: RejectReason, val detail: String) : Loaded
    }

    private suspend fun load(gameId: String, game: JsonObject, result: JsonObject): Loaded {
        fun broken(detail: String) = Loaded.Broken(RejectReason.SETUP_MISMATCH, detail)
        val host = game.str(FirestoreSchema.Game.HOST_UID) ?: return broken("game without a host")
        val seats = game.strings(FirestoreSchema.Game.SEATS) ?: return broken("game without seats")
        val config = game.str(FirestoreSchema.Game.RULE_SET_CONFIG_JSON)?.let { decode(it, RuleSetConfig.serializer()) } ?: return broken("game without rules")
        val createdAt = game.long(FirestoreSchema.Game.CREATED_AT) ?: return broken("game without a start time")
        val ranking = result.strings(FirestoreSchema.Result.RANKING) ?: return Loaded.Broken(RejectReason.RANKING_MISMATCH, "result without a ranking")

        val setup = store.get(FirestoreSchema.setup(gameId))?.let { doc ->
            val order = doc.strings(FirestoreSchema.Setup.ORDER) ?: return broken("setup without seats")
            val setupConfig = doc.str(FirestoreSchema.Setup.RULE_SET_CONFIG_JSON)?.let { decode(it, RuleSetConfig.serializer()) } ?: return broken("setup without rules")
            val seed = doc.long(FirestoreSchema.Setup.SEED) ?: return broken("setup without a seed")
            GameSetup(gameId, order.map(::PlayerId), setupConfig, seed, doc.str(FirestoreSchema.Setup.FIRST_PLAYER)?.let(::PlayerId))
        }

        val log = store.observeOrdered(FirestoreSchema.log(gameId), FirestoreSchema.Log.VERSION, descending = false, limit = MAX_LOG).first().map { doc ->
            val d = doc.data
            LogRecord(
                version = d.long(FirestoreSchema.Log.VERSION) ?: return Loaded.Broken(RejectReason.LOG_GAP, "log ${doc.id} without a version"),
                command = d.str(FirestoreSchema.Log.COMMAND_JSON)?.let { decode(it, Command.serializer()) }
                    ?: return Loaded.Broken(RejectReason.ILLEGAL_COMMAND, "log ${doc.id}: undecodable command"),
                source = d.str(FirestoreSchema.Log.SOURCE)?.let { s -> CommandSource.entries.firstOrNull { it.name == s } }
                    ?: return Loaded.Broken(RejectReason.ILLEGAL_COMMAND, "log ${doc.id}: unknown source"),
                ref = d.str(FirestoreSchema.Log.REF),
                at = d.long(FirestoreSchema.Log.AT) ?: return Loaded.Broken(RejectReason.LOG_GAP, "log ${doc.id} without a time"),
            )
        }
        val guestCommands = log.mapNotNull { it.ref }.distinct().mapNotNull { ref ->
            val doc = store.get(FirestoreSchema.command(gameId, ref)) ?: return@mapNotNull null
            val sender = doc.str(FirestoreSchema.Command.SENDER_UID) ?: return@mapNotNull null
            val command = doc.str(FirestoreSchema.Command.COMMAND_JSON)?.let { decode(it, Command.serializer()) } ?: return@mapNotNull null
            ref to GuestCommand(ref, sender, command)
        }.toMap()

        return Loaded.Ok(
            GameRecord(
                gameId = gameId,
                hostUid = host,
                humanSeats = seats,
                rated = true,
                ruleSetConfig = config,
                createdAt = createdAt,
                setup = setup,
                log = log,
                guestCommands = guestCommands,
                resultRanking = ranking,
            ),
        )
    }

    /** 검증된 판의 레이팅을 반영한다: 판정 표시 확인 -> 참가자 프로필·반영 표시 읽기 -> 모두 쓰기(한 트랜잭션). */
    private suspend fun settle(gameId: String, verdict: Verdict.Verified, finishedAt: Long): JudgeOutcome = store.transaction { tx ->
        val game = tx.get(FirestoreSchema.game(gameId)) ?: return@transaction JudgeOutcome.NotReady("no game")
        if (game.str(FirestoreSchema.Game.VERDICT) != null) return@transaction JudgeOutcome.AlreadyJudged
        val now = clock.nowMillis()
        val outcome = GameResult(gameId, verdict.ranking, verdict.ranking.size, rated = true, ratingDeltas = verdict.ratingDeltas, finishedAt = finishedAt)
        val reads = verdict.ranking.map { uid ->
            Triple(
                uid,
                tx.get(FirestoreSchema.user(uid))?.let { UserDocuments.profile(uid, it) },
                tx.get(FirestoreSchema.appliedResult(uid, gameId)) != null,
            ) to tx.get(FirestoreSchema.pendingRating(uid))?.str(FirestoreSchema.Pending.GAME_ID)
        }
        val applied = mutableListOf<String>()
        for ((read, pendingGame) in reads) {
            val (uid, profile, alreadyApplied) = read
            if (profile != null) {
                val decision = RatingLedger.decide(profile, outcome, alreadyApplied, now)
                if (decision is RatingDecision.Apply) {
                    UserDocuments.ratingWrites(uid, decision).forEach(tx::write)
                    applied += uid
                }
            }
            // 옛 방식(클라이언트 정산)의 대기 표시가 이 판을 가리키면 함께 지운다
            if (pendingGame == gameId) tx.write(WriteOp.Delete(FirestoreSchema.pendingRating(uid)))
        }
        tx.write(verdictOp(gameId, VERDICT_VERIFIED, null))
        JudgeOutcome.Verified(verdict, applied)
    }

    /** 판정만 남긴다(반영 없음). 이미 판정됐으면 false. */
    private suspend fun mark(gameId: String, verdict: String, reason: String?): Boolean = store.transaction { tx ->
        val game = tx.get(FirestoreSchema.game(gameId)) ?: return@transaction false
        if (game.str(FirestoreSchema.Game.VERDICT) != null) return@transaction false
        tx.write(verdictOp(gameId, verdict, reason))
        true
    }

    private fun verdictOp(gameId: String, verdict: String, reason: String?): WriteOp = WriteOp.Merge(
        FirestoreSchema.game(gameId),
        buildJsonObject {
            put(FirestoreSchema.Game.VERDICT, verdict)
            reason?.let { put(FirestoreSchema.Game.VERDICT_REASON, it.take(MAX_REASON)) }
            put(FirestoreSchema.Game.JUDGED_AT, ServerValues.TIMESTAMP)
        },
    )

    private fun <T> decode(text: String, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        EngineJson.json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    public companion object {
        public const val VERDICT_VERIFIED: String = "VERIFIED"
        public const val VERDICT_REJECTED: String = "REJECTED"
        public const val VERDICT_UNRATED: String = "UNRATED"
        private const val MAX_LOG = 100_000
        private const val MAX_REASON = 500
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
