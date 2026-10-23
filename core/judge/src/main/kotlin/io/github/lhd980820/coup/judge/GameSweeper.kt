package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.account.RatingDecision
import io.github.lhd980820.coup.account.RatingLedger
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.ServerValues
import io.github.lhd980820.coup.remote.UserDocuments
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public sealed interface GameSweep {
    /** 아직 진행 중이다(최근에 기록이 있다). */
    public data object Active : GameSweep

    /** 진행 중인 판이 아니다(없거나, 끝났거나, 이미 정리됐다). */
    public data object NotPlaying : GameSweep

    /**
     * 버려진 판으로 끝냈다. 레이팅 판이면 방장만 [hostRank](0부터) 자리로 반영했고([applied]), [note]는 그 근거
     * (기록을 다시 돌리지 못했으면 그 이유 — 그때도 방장은 꼴찌로 처리한다).
     */
    public data class Abandoned(val rated: Boolean, val hostRank: Int?, val applied: Boolean, val note: String) : GameSweep

    /** 저장소 오류로 이번에는 살피지 못했다(다음 정리 때 다시 본다). */
    public data class Failed(val error: String) : GameSweep
}

/**
 * 버려진 판 정리(ADR 0016). 방장 권위 판은 방장이 사라지면 멈추고, 지는 방장은 일부러 판을 버려 패배를 피할 수 있었다.
 * 모든 판에 사람·봇 결정마다 시간 제한(최대 30초)이 있으므로, 살아 있는 방장이라면 기록이 몇 분씩 끊기지 않는다.
 * 그래서 마지막 진행(게임 생성 또는 마지막 기록의 **서버 시각**)이 [abandonAfter]보다 오래된 판을 버려진 판으로 끝낸다.
 *
 * 레이팅 판이면 **방장만** 패배로 반영한다: 아직 살아 있었으면 꼴찌, 이미 탈락했으면 그 탈락 순위(기록을 다시 돌려 확인).
 * 다른 참가자의 레이팅은 그대로다 — 그들은 판을 버리지 않았다. 지는 판을 버리면 지는 것과 같거나 더 나쁘므로 버릴 이유가 없어진다.
 * [store]는 관리자 권한이어야 한다. 같은 판을 두 번 정리해도 안전하다(트랜잭션 안에서 상태를 다시 확인한다).
 */
public class GameSweeper(
    private val store: DocumentStore,
    private val engine: GameEngine = GameEngines.create(),
    private val verifier: GameVerifier = GameVerifier(engine),
    private val clock: Clock = Clock.System,
    private val abandonAfter: Duration = DEFAULT_ABANDON_AFTER,
) {
    /** 진행 중인 모든 판을 살펴 버려진 판을 끝낸다. 한 판의 저장소 오류는 그 판만 건너뛴다. */
    public suspend fun sweepGames(): Map<String, GameSweep> =
        store.observeWhere(FirestoreSchema.GAMES, FirestoreSchema.Game.STATUS, FirestoreSchema.GameStatus.PLAYING).first()
            .associate { doc ->
                doc.id to try {
                    sweepGame(doc.id)
                } catch (e: TransportException) {
                    GameSweep.Failed(e.message ?: e.toString())
                }
            }

    public suspend fun sweepGame(gameId: String): GameSweep {
        val game = store.get(FirestoreSchema.game(gameId)) ?: return GameSweep.NotPlaying
        if (game.str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING) return GameSweep.NotPlaying
        val createdAt = game.long(FirestoreSchema.Game.CREATED_AT) ?: 0L
        val lastLogAt = store.observeOrdered(FirestoreSchema.log(gameId), FirestoreSchema.Log.VERSION, descending = true, limit = 1).first()
            .firstOrNull()?.data?.long(FirestoreSchema.Log.AT) ?: 0L
        if (clock.nowMillis() - maxOf(createdAt, lastLogAt) < abandonAfter.inWholeMilliseconds) return GameSweep.Active

        val host = game.str(FirestoreSchema.Game.HOST_UID)
        val seats = game.strings(FirestoreSchema.Game.SEATS)
        if (game.bool(FirestoreSchema.Game.RATED) != true || host == null || seats == null || host !in seats) {
            return if (close(gameId, FirestoreSchema.Verdict.UNRATED, "abandoned (not rated)")) {
                GameSweep.Abandoned(rated = false, hostRank = null, applied = false, note = "not rated")
            } else {
                GameSweep.NotPlaying
            }
        }
        val (rank, note) = hostRank(gameId, game, host, seats.size)
        return forfeit(gameId, host, rank, seats.size, note)
    }

    /** 방장의 자리: 기록을 다시 돌려 이미 탈락했으면 그 순위, 아니면(살아 있거나 기록이 맞지 않으면) 꼴찌. */
    private suspend fun hostRank(gameId: String, game: kotlinx.serialization.json.JsonObject, host: String, playerCount: Int): Pair<Int, String> {
        val last = playerCount - 1
        val record = when (val loaded = GameRecords.load(store, gameId, game, ranking = emptyList())) {
            is Loaded.Broken -> return last to "host abandoned; record unusable (${loaded.reason}: ${loaded.detail})"
            is Loaded.Ok -> loaded.record
        }
        return when (val replay = verifier.replay(record)) {
            is Replay.Failed -> last to "host abandoned; record rejected (${replay.verdict.reason}: ${replay.verdict.detail})"
            is Replay.Ok -> {
                // 순위 = 우승자 + 탈락 순서를 거꾸로: 탈락 순서의 i번째는 (n - 1 - i)위(0부터)
                val eliminated = replay.state.eliminationOrder.indexOf(PlayerId(host))
                if (eliminated >= 0) (playerCount - 1 - eliminated) to "host abandoned after being eliminated" else last to "host abandoned while still playing"
            }
        }
    }

    private suspend fun forfeit(gameId: String, host: String, rank: Int, playerCount: Int, note: String): GameSweep = store.transaction { tx ->
        val game = tx.get(FirestoreSchema.game(gameId)) ?: return@transaction GameSweep.NotPlaying
        if (game.str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING || game.str(FirestoreSchema.Game.VERDICT) != null) {
            return@transaction GameSweep.NotPlaying
        }
        val profile = tx.get(FirestoreSchema.user(host))?.let { UserDocuments.profile(host, it) }
        val alreadyApplied = tx.get(FirestoreSchema.appliedResult(host, gameId)) != null
        val pendingGame = tx.get(FirestoreSchema.pendingRating(host))?.str(FirestoreSchema.Pending.GAME_ID)
        val now = clock.nowMillis()
        var applied = false
        if (profile != null) {
            val decision = RatingLedger.forfeit(profile, gameId, rank, playerCount, finishedAt = now, alreadyApplied = alreadyApplied, now = now)
            if (decision is RatingDecision.Apply) {
                UserDocuments.ratingWrites(host, decision).forEach(tx::write)
                applied = true
            }
        }
        if (pendingGame == gameId) tx.write(WriteOp.Delete(FirestoreSchema.pendingRating(host)))
        tx.write(closeOp(gameId, FirestoreSchema.Verdict.ABANDONED, "$note; host rank ${rank + 1} of $playerCount"))
        GameSweep.Abandoned(rated = true, hostRank = rank, applied = applied, note = note)
    }

    /** 레이팅 없이 버려진 판으로 표시만 한다. 이미 정리됐으면 false. */
    private suspend fun close(gameId: String, verdict: String, reason: String): Boolean = store.transaction { tx ->
        val game = tx.get(FirestoreSchema.game(gameId)) ?: return@transaction false
        if (game.str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING || game.str(FirestoreSchema.Game.VERDICT) != null) return@transaction false
        tx.write(closeOp(gameId, verdict, reason))
        true
    }

    private fun closeOp(gameId: String, verdict: String, reason: String): WriteOp = WriteOp.Merge(
        FirestoreSchema.game(gameId),
        buildJsonObject {
            put(FirestoreSchema.Game.STATUS, FirestoreSchema.GameStatus.ABANDONED)
            put(FirestoreSchema.Game.VERDICT, verdict)
            put(FirestoreSchema.Game.VERDICT_REASON, reason.take(MAX_REASON))
            put(FirestoreSchema.Game.JUDGED_AT, ServerValues.TIMESTAMP)
        },
    )

    public companion object {
        /** 시간 제한(최대 30초)의 스무 배 — 잠깐 끊겼다 다시 붙는 방장(ADR 0012)은 버려진 판이 되지 않는다. */
        public val DEFAULT_ABANDON_AFTER: Duration = 10.minutes
        private const val MAX_REASON = 500
    }
}
