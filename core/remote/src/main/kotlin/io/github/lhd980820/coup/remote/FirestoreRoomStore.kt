package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.lobby.JoinCodes
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.RoomStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * [RoomStore]의 Firestore 구현(ADR 0008). 한 인스턴스는 로그인한 한 사용자([uid])로서 동작한다 — 보안 규칙이 그 사용자 기준으로
 * "자기 자리만 추가·수정(게스트)", "사람을 넣을 수는 없고 뺄 수만(방장)"을 검사한다.
 * [update]는 Firestore 트랜잭션이라 마지막 자리 동시 입장 같은 경쟁에서도 규칙([io.github.lhd980820.coup.lobby.RoomRules])이 지켜진다.
 *
 * 규칙에 막힌 쓰기는 [io.github.lhd980820.coup.runtime.transport.TransportException]으로 올라온다(정상 앱에서는 일어나지 않는다).
 */
public class FirestoreRoomStore(private val store: DocumentStore, private val uid: String) : RoomStore {

    override suspend fun create(room: Room): RoomResult<Room> {
        require(room.hostUid == uid) { "rooms are created by their host" }
        return store.transaction { tx ->
            if (tx.get(FirestoreSchema.room(room.id)) != null) return@transaction RoomResult.Err(RoomError.NOT_ALLOWED)
            val code = room.joinCode?.let(JoinCodes::normalize)
            if (code != null && tx.get(FirestoreSchema.joinCode(code)) != null) return@transaction RoomResult.Err(RoomError.JOIN_CODE_TAKEN)
            val doc = RoomDocuments.encodeNew(room.copy(joinCode = code))
            tx.write(WriteOp.Set(FirestoreSchema.room(room.id), doc))
            if (code != null) {
                tx.write(WriteOp.Set(FirestoreSchema.joinCode(code), buildJsonObject { put(FirestoreSchema.JoinCodeDoc.ROOM_ID, room.id) }))
            }
            RoomResult.Ok(checkNotNull(RoomDocuments.decode(room.id, doc)))
        }
    }

    override suspend fun get(id: String): Room? = store.get(FirestoreSchema.room(id))?.let { RoomDocuments.decode(id, it) }

    override suspend fun update(id: String, transform: (Room) -> RoomResult<Room>): RoomResult<Room> = store.transaction { tx ->
        val doc = tx.get(FirestoreSchema.room(id)) ?: return@transaction RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        val current = RoomDocuments.decode(id, doc) ?: return@transaction RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        val next = when (val r = transform(current)) {
            is RoomResult.Err -> return@transaction r
            is RoomResult.Ok -> r.value
        }
        if (next == current) return@transaction RoomResult.Ok(current)
        val written = RoomDocuments.encodeUpdate(doc, current, next, actorUid = uid)
        tx.write(WriteOp.Set(FirestoreSchema.room(id), written))
        // 방이 닫히면 참가 코드를 풀어 다른 방이 쓸 수 있게 한다
        val code = current.joinCode
        if (code != null && current.status != RoomStatus.CLOSED && next.status == RoomStatus.CLOSED) {
            tx.write(WriteOp.Delete(FirestoreSchema.joinCode(code)))
        }
        RoomResult.Ok(checkNotNull(RoomDocuments.decode(id, written)))
    }

    override suspend fun listOpen(): List<Room> =
        store.observeWhere(FirestoreSchema.ROOMS, FirestoreSchema.Room.VISIBILITY, "PUBLIC").first()
            .mapNotNull { RoomDocuments.decode(it.id, it.data) }
            .filter { it.status == RoomStatus.WAITING }
            .sortedWith(compareByDescending<Room> { it.createdAt }.thenBy { it.id })

    override suspend fun findByJoinCode(code: String): Room? {
        val doc = store.get(FirestoreSchema.joinCode(JoinCodes.normalize(code))) ?: return null
        val roomId = (doc[FirestoreSchema.JoinCodeDoc.ROOM_ID] as? JsonPrimitive)?.contentOrNull ?: return null
        return get(roomId)?.takeIf { it.status != RoomStatus.CLOSED }
    }

    override fun observe(id: String): Flow<Room?> = store.observe(FirestoreSchema.room(id)).map { it?.let { d -> RoomDocuments.decode(id, d) } }

    override suspend fun touch(id: String, byUid: String): RoomResult<Room> = store.transaction { tx ->
        val doc = tx.get(FirestoreSchema.room(id)) ?: return@transaction RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        val current = RoomDocuments.decode(id, doc) ?: return@transaction RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        if (current.status == RoomStatus.CLOSED) return@transaction RoomResult.Err(RoomError.NOT_ALLOWED)
        if (current.hostUid != byUid || byUid != uid) return@transaction RoomResult.Err(RoomError.NOT_HOST)
        // 내용은 그대로 — version과 서버 시각만 바뀐다
        val written = RoomDocuments.encodeUpdate(doc, current, current, actorUid = uid)
        tx.write(WriteOp.Set(FirestoreSchema.room(id), written))
        RoomResult.Ok(checkNotNull(RoomDocuments.decode(id, written)))
    }
}
