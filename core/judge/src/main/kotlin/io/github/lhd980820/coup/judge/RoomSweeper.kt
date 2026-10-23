package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.lobby.RoomLiveness
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.ServerValues
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.Clock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public sealed interface RoomSweep {
    /** 그대로 둔다(최근에 고쳤거나, 판이 아직 진행 중이다 — 진행 중인 판은 [GameSweeper]가 맡는다). */
    public data object Active : RoomSweep

    /** 이미 닫혔거나 없다. */
    public data object NotOpen : RoomSweep

    public data class Closed(val reason: String) : RoomSweep
}

/**
 * 버려진 방 정리(ADR 0016). 방장이 사라진 대기실은 목록에 남아 사람들을 기다리게 했다.
 * - 대기 중인 방: [staleAfterMillis] 넘게 아무도 고치지 않았으면(방장은 몇 분마다 알린다) 닫는다.
 * - 게임 중인 방: 판이 끝났거나 버려졌고, 그 뒤(방의 마지막 쓰기·판정 시각 중 늦은 쪽) [staleAfterMillis]가 지났으면 닫는다.
 *   판이 진행 중이면 두지 않는다(게임 중에는 방을 쓰지 않으므로 방의 시각만으로는 알 수 없다).
 * 닫으면 비공개 방 참가 코드도 푼다. 방 안의 기기는 "방이 닫혔다"를 보고 로비로 돌아간다. [store]는 관리자 권한이어야 한다.
 */
public class RoomSweeper(
    private val store: DocumentStore,
    private val clock: Clock = Clock.System,
    private val staleAfterMillis: Long = RoomLiveness.STALE_AFTER_MILLIS,
) {
    public suspend fun sweepRooms(): Map<String, RoomSweep> {
        val open = listOf(RoomStatus.WAITING, RoomStatus.PLAYING).flatMap { status ->
            store.observeWhere(FirestoreSchema.ROOMS, FirestoreSchema.Room.STATUS, status.name).first().map { it.id }
        }
        return open.associateWith { sweepRoom(it) }
    }

    public suspend fun sweepRoom(roomId: String): RoomSweep = store.transaction { tx ->
        val room = tx.get(FirestoreSchema.room(roomId)) ?: return@transaction RoomSweep.NotOpen
        val status = room.str(FirestoreSchema.Room.STATUS)
        if (status != RoomStatus.WAITING.name && status != RoomStatus.PLAYING.name) return@transaction RoomSweep.NotOpen
        val now = clock.nowMillis()
        val touched = room.long(FirestoreSchema.Room.UPDATED_AT) ?: room.long(FirestoreSchema.Room.CREATED_AT) ?: 0L

        val reason = if (status == RoomStatus.WAITING.name) {
            if (now - touched <= staleAfterMillis) return@transaction RoomSweep.Active
            "waiting room left alone"
        } else {
            val gameId = room.str(FirestoreSchema.Room.GAME_ID)
            val game = gameId?.let { tx.get(FirestoreSchema.game(it)) }
            val gameStatus = game?.str(FirestoreSchema.Game.STATUS)
            if (gameStatus == FirestoreSchema.GameStatus.PLAYING) return@transaction RoomSweep.Active
            val ended = maxOf(touched, game?.long(FirestoreSchema.Game.JUDGED_AT) ?: 0L)
            if (now - ended <= staleAfterMillis) return@transaction RoomSweep.Active
            if (game == null) "the game never started" else "nobody came back after the game ($gameStatus)"
        }
        close(tx, roomId, room)
        RoomSweep.Closed(reason)
    }

    private fun close(tx: io.github.lhd980820.coup.remote.DocumentTransaction, roomId: String, room: JsonObject) {
        val version = room.long(FirestoreSchema.Room.VERSION) ?: 0L
        tx.write(
            WriteOp.Merge(
                FirestoreSchema.room(roomId),
                JsonObject(
                    mapOf(
                        FirestoreSchema.Room.STATUS to JsonPrimitive(RoomStatus.CLOSED.name),
                        FirestoreSchema.Room.VERSION to JsonPrimitive(version + 1),
                        FirestoreSchema.Room.UPDATED_AT to ServerValues.TIMESTAMP,
                    ),
                ),
            ),
        )
        room.str(FirestoreSchema.Room.JOIN_CODE)?.let { tx.write(WriteOp.Delete(FirestoreSchema.joinCode(it))) }
    }
}
