package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public sealed interface Purge {
    /** 아직 지울 때가 아니다(진행 중, 판정 전, 보존 기간 안, 열린 방). */
    public data class Kept(val reason: String) : Purge

    /** 없다(이미 지웠다). */
    public data object Gone : Purge

    /** 지웠다. [deletes]는 보낸 삭제 수(없던 문서 포함). */
    public data class Purged(val deletes: Int) : Purge

    /** 저장소 오류로 이번에는 끝내지 못했다. 게임·방 문서는 마지막에 지우므로 남아 있고, 다음 실행이 이어서 지운다. */
    public data class Failed(val error: String) : Purge
}

/**
 * 끝난 판·닫힌 방의 기록 정리(ADR 0021). 판마다 좌석별 화면·백업·기록·명령 문서가 남아 계속 쌓였다.
 *
 * - **판**: 판정이 난 뒤(`judgedAt`, 서버 시각) [keepJudged]가 지난 판의 `games/{id}`와 그 아래 문서 전부, `results/{id}`를 지운다.
 *   위조로 거부된 판(`REJECTED`)은 조사할 수 있게 [keepRejected] 동안 남긴다. **판정이 없는 판(판정 함수가 아직 기록을 읽어야 한다)과
 *   진행 중인 판은 절대 지우지 않는다.** 전적(`users/{uid}/appliedResults`)과 프로필은 그 문서만으로 보이므로 남는다.
 * - **방**: 닫힌 뒤 [keepClosedRooms]가 지난 방 문서를 지우고, 참가 코드가 아직 그 방을 가리키면 함께 지운다.
 *
 * 하위 문서부터 지우고 게임 문서는 맨 마지막에(판정과 시각을 다시 확인하는 트랜잭션 안에서) 지운다 —
 * 중간에 실패해도 게임 문서가 남아 다음 실행이 다시 찾는다. 한 번에 [maxGamesPerRun]판까지만 지워 실행 시간을 묶는다(밀린 것은 다음 실행).
 * [store]는 관리자 권한이어야 한다(규칙은 클라이언트의 삭제를 막는다).
 */
public class RecordPurger(
    private val store: DocumentStore,
    private val clock: Clock = Clock.System,
    private val keepJudged: Duration = DEFAULT_KEEP_JUDGED,
    private val keepRejected: Duration = DEFAULT_KEEP_REJECTED,
    private val keepClosedRooms: Duration = DEFAULT_KEEP_CLOSED_ROOMS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val maxGamesPerRun: Int = DEFAULT_MAX_GAMES_PER_RUN,
) {
    init {
        require(batchSize in 1..MAX_BATCH) { "a Firestore batch holds at most $MAX_BATCH writes" }
        require(maxGamesPerRun >= 1)
    }

    /** 끝난(또는 버려진) 판을 살펴 보존 기간이 지난 것을 지운다. 한 판의 오류는 그 판만 건너뛴다. */
    public suspend fun purgeGames(): Map<String, Purge> {
        val ended = listOf(FirestoreSchema.GameStatus.FINISHED, FirestoreSchema.GameStatus.ABANDONED).flatMap { status ->
            store.observeWhere(FirestoreSchema.GAMES, FirestoreSchema.Game.STATUS, status).first().map { it.id }
        }
        val outcomes = linkedMapOf<String, Purge>()
        var purged = 0
        for (id in ended) {
            if (purged >= maxGamesPerRun) break
            val outcome = try {
                purgeGame(id)
            } catch (e: TransportException) {
                Purge.Failed(e.message ?: e.toString())
            }
            if (outcome is Purge.Purged) purged++
            outcomes[id] = outcome
        }
        return outcomes
    }

    public suspend fun purgeGame(gameId: String): Purge {
        val game = store.get(FirestoreSchema.game(gameId)) ?: return Purge.Gone
        whyKeep(game)?.let { return Purge.Kept(it) }
        val seats = game.strings(FirestoreSchema.Game.SEATS).orEmpty()
        var deletes = 0

        // 1) 이름을 아는 문서: 좌석별·관전 화면, 방장 백업과 실행 설정
        val known = (seats + FirestoreSchema.SPECTATOR_VIEW).map { FirestoreSchema.view(gameId, it) } +
            FirestoreSchema.authority(gameId) + FirestoreSchema.setup(gameId)
        deletes += deleteAll(known)

        // 2) 기록: 버전 순으로 한 묶음씩(모든 기록 문서에 버전이 있다 — 규칙)
        while (true) {
            val page = store.observeOrdered(FirestoreSchema.log(gameId), FirestoreSchema.Log.VERSION, descending = false, limit = batchSize).first()
            if (page.isEmpty()) break
            deletes += deleteAll(page.map { "${FirestoreSchema.log(gameId)}/${it.id}" })
        }

        // 3) 명령: 보낸 사람(좌석)별로(모든 명령 문서에 보낸 사람이 있고, 좌석만 보낼 수 있다 — 규칙)
        seats.forEach { seat ->
            val ids = store.observeWhere(FirestoreSchema.commands(gameId), FirestoreSchema.Command.SENDER_UID, seat).first().map { it.id }
            deletes += deleteAll(ids.map { FirestoreSchema.command(gameId, it) })
        }

        // 4) 마지막에 결과와 게임 문서: 그사이 바뀌지 않았는지 다시 확인한다
        val finished = store.transaction { tx ->
            val current = tx.get(FirestoreSchema.game(gameId)) ?: return@transaction false
            if (whyKeep(current) != null) return@transaction false
            tx.write(WriteOp.Delete(FirestoreSchema.result(gameId)))
            tx.write(WriteOp.Delete(FirestoreSchema.game(gameId)))
            true
        }
        return if (finished) Purge.Purged(deletes + 2) else Purge.Kept("changed while purging")
    }

    /** 이 판을 아직 남겨야 하는 이유. 지워도 되면 null. */
    private fun whyKeep(game: kotlinx.serialization.json.JsonObject): String? {
        val status = game.str(FirestoreSchema.Game.STATUS)
        if (status != FirestoreSchema.GameStatus.FINISHED && status != FirestoreSchema.GameStatus.ABANDONED) return "not ended ($status)"
        val verdict = game.str(FirestoreSchema.Game.VERDICT) ?: return "not judged yet"
        val judgedAt = game.long(FirestoreSchema.Game.JUDGED_AT) ?: return "no judgment time"
        val keep = if (verdict == FirestoreSchema.Verdict.REJECTED) keepRejected else keepJudged
        return if (clock.nowMillis() - judgedAt < keep.inWholeMilliseconds) "within the $keep retention ($verdict)" else null
    }

    /** 닫힌 지 오래된 방을 지운다. */
    public suspend fun purgeRooms(): Map<String, Purge> =
        store.observeWhere(FirestoreSchema.ROOMS, FirestoreSchema.Room.STATUS, RoomStatus.CLOSED.name).first().associate { doc ->
            doc.id to try {
                purgeRoom(doc.id)
            } catch (e: TransportException) {
                Purge.Failed(e.message ?: e.toString())
            }
        }

    public suspend fun purgeRoom(roomId: String): Purge = store.transaction { tx ->
        val room = tx.get(FirestoreSchema.room(roomId)) ?: return@transaction Purge.Gone
        if (room.str(FirestoreSchema.Room.STATUS) != RoomStatus.CLOSED.name) return@transaction Purge.Kept("open")
        val closedAt = room.long(FirestoreSchema.Room.UPDATED_AT) ?: room.long(FirestoreSchema.Room.CREATED_AT) ?: 0L
        if (clock.nowMillis() - closedAt < keepClosedRooms.inWholeMilliseconds) return@transaction Purge.Kept("closed recently")
        var deletes = 1
        // 닫을 때 참가 코드를 풀지만, 혹시 남았고 아직 이 방을 가리키면 함께 지운다(다른 방이 같은 코드를 새로 받았으면 둔다)
        room.str(FirestoreSchema.Room.JOIN_CODE)?.let { code ->
            val target = tx.get(FirestoreSchema.joinCode(code))?.str(FirestoreSchema.JoinCodeDoc.ROOM_ID)
            if (target == roomId) {
                tx.write(WriteOp.Delete(FirestoreSchema.joinCode(code)))
                deletes++
            }
        }
        tx.write(WriteOp.Delete(FirestoreSchema.room(roomId)))
        Purge.Purged(deletes)
    }

    /** [paths]를 [batchSize]개씩 나눠 지운다(Firestore 묶음 쓰기 한도 500). */
    private suspend fun deleteAll(paths: List<String>): Int {
        paths.chunked(batchSize).forEach { chunk -> store.write(chunk.map { WriteOp.Delete(it) }) }
        return paths.size
    }

    public companion object {
        /** 판정 뒤 남기는 기간. 결과 화면·재접속·방 정리(15분)는 그 안에 모두 끝난다. 이의 제기나 버그 조사를 위한 여유다. */
        public val DEFAULT_KEEP_JUDGED: Duration = 7.days

        /** 위조로 거부된 판은 조사할 수 있게 더 오래 남긴다. */
        public val DEFAULT_KEEP_REJECTED: Duration = 30.days
        public val DEFAULT_KEEP_CLOSED_ROOMS: Duration = 7.days
        public const val DEFAULT_BATCH_SIZE: Int = 400
        public const val DEFAULT_MAX_GAMES_PER_RUN: Int = 300
        private const val MAX_BATCH = 500
    }
}
