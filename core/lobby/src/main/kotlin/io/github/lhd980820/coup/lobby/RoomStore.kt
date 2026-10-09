package io.github.lhd980820.coup.lobby

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 방 저장소. 핵심 계약은 [update]: **읽기-수정-쓰기가 원자적**이어야 한다(Firestore 구현은 트랜잭션).
 * 그래야 마지막 자리에 두 명이 동시에 들어와도 정확히 한 명만 성공한다.
 */
public interface RoomStore {
    /** 새 방을 저장한다. ID나 (비공개) 참가 코드가 이미 쓰이고 있으면 오류. */
    public suspend fun create(room: Room): RoomResult<Room>

    public suspend fun get(id: String): Room?

    /** 변환이 [RoomResult.Ok]를 돌려주면 저장하고(version +1) 저장된 방을, 오류면 오류를 돌려준다. */
    public suspend fun update(id: String, transform: (Room) -> RoomResult<Room>): RoomResult<Room>

    /** 공개 + 대기 중인 방만, 최신순. */
    public suspend fun listOpen(): List<Room>

    public suspend fun findByJoinCode(code: String): Room?

    public fun observe(id: String): Flow<Room?>
}

/** 테스트/오프라인용 구현. */
public class InMemoryRoomStore : RoomStore {
    private val mutex = Mutex()
    private val rooms = MutableStateFlow<Map<String, Room>>(emptyMap())

    override suspend fun create(room: Room): RoomResult<Room> = mutex.withLock {
        val all = rooms.value
        if (room.id in all) return RoomResult.Err(RoomError.NOT_ALLOWED)
        val code = room.joinCode
        if (code != null && all.values.any { it.status != RoomStatus.CLOSED && it.joinCode?.let(JoinCodes::normalize) == JoinCodes.normalize(code) }) {
            return RoomResult.Err(RoomError.JOIN_CODE_TAKEN)
        }
        val saved = room.copy(version = 1)
        rooms.value = all + (saved.id to saved)
        RoomResult.Ok(saved)
    }

    override suspend fun get(id: String): Room? = rooms.value[id]

    override suspend fun update(id: String, transform: (Room) -> RoomResult<Room>): RoomResult<Room> = mutex.withLock {
        val current = rooms.value[id] ?: return RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        when (val result = transform(current)) {
            is RoomResult.Err -> result
            is RoomResult.Ok -> {
                val next = result.value
                if (next == current) {
                    RoomResult.Ok(current)
                } else {
                    val saved = next.copy(version = current.version + 1)
                    rooms.value = rooms.value + (id to saved)
                    RoomResult.Ok(saved)
                }
            }
        }
    }

    override suspend fun listOpen(): List<Room> = rooms.value.values
        .filter { it.visibility == Visibility.PUBLIC && it.status == RoomStatus.WAITING }
        .sortedWith(compareByDescending<Room> { it.createdAt }.thenBy { it.id })

    override suspend fun findByJoinCode(code: String): Room? {
        val wanted = JoinCodes.normalize(code)
        return rooms.value.values.firstOrNull { it.status != RoomStatus.CLOSED && it.joinCode?.let(JoinCodes::normalize) == wanted }
    }

    override fun observe(id: String): Flow<Room?> = rooms.map { it[id] }
}
