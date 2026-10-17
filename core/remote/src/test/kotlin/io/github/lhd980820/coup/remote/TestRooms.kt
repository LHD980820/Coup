package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomSeat
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.SeatKind
import io.github.lhd980820.coup.lobby.Visibility

/**
 * 방 규칙과 무관한 테스트용: [gameId]를 시작한 방을 관리자 권한으로 만들고 방 ID를 돌려준다.
 * 좌석 = [seats](사람), [rated]가 아니면 하우스룰을 넣어 비레이팅 구성으로 만든다. (게임 문서 생성 규칙이 방을 요구한다 — ADR 0008)
 */
suspend fun FakeFirestore.seedStartedRoom(gameId: String, hostUid: String, seats: List<String>, rated: Boolean): String {
    val roomId = "room-$gameId"
    val config = BuiltinRules.classicConfig().let { if (rated) it else it.copy(houseRules = setOf("last_stand")) }
    val room = Room(
        id = roomId,
        title = "t",
        hostUid = hostUid,
        visibility = Visibility.PUBLIC,
        joinCode = null,
        maxPlayers = 6,
        seats = seats.map { RoomSeat(it, it, SeatKind.HUMAN, ready = true) },
        status = RoomStatus.PLAYING,
        ruleSetConfig = config,
        minAppVersion = 1,
        createdAt = 0,
        gameId = gameId,
    )
    admin().write(listOf(WriteOp.Set(FirestoreSchema.room(roomId), RoomDocuments.encodeNew(room))))
    return roomId
}
