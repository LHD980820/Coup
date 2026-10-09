package io.github.lhd980820.coup.lobby

import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import kotlinx.serialization.Serializable

@Serializable
public enum class Visibility {
    /** 방 목록에 나온다. */
    PUBLIC,

    /** 목록에 나오지 않고 참가 코드로만 들어온다. */
    PRIVATE,
}

@Serializable
public enum class RoomStatus {
    WAITING,
    PLAYING,

    /** 방장이 나가서 닫혔다. 구독 중인 화면은 이것을 보고 방을 나간다. */
    CLOSED,
}

@Serializable
public enum class SeatKind { HUMAN, BOT }

/** 봇 난이도(= [io.github.lhd980820.coup.ai.AiDifficulty]). 저장 형식을 AI 모듈과 분리하려고 따로 둔다. */
@Serializable
public enum class BotLevel { EASY, NORMAL }

/** 봇 성격 프리셋(= [io.github.lhd980820.coup.ai.Personality] 프리셋). */
@Serializable
public enum class BotStyle { BALANCED, CAUTIOUS, AGGRESSIVE }

/** 방의 좌석 하나. 사람은 [id]가 사용자 uid, 봇은 방 안에서 유일한 봇 ID다. */
@Serializable
public data class RoomSeat(
    public val id: String,
    public val displayName: String,
    public val kind: SeatKind,
    /** 사람 게스트의 준비 여부. 방장과 봇은 항상 true. */
    public val ready: Boolean,
    public val botLevel: BotLevel? = null,
    public val botStyle: BotStyle? = null,
)

/**
 * 방(설계 §8.2 `rooms/{roomId}`). 좌석 순서는 입장 순서이며 게임 시작 때 섞인다.
 * [version]은 저장소가 변경마다 올린다(동시성 제어용).
 */
@Serializable
public data class Room(
    public val id: String,
    public val title: String,
    public val hostUid: String,
    public val visibility: Visibility,
    public val joinCode: String?,
    public val maxPlayers: Int,
    public val seats: List<RoomSeat>,
    public val status: RoomStatus,
    public val ruleSetConfig: RuleSetConfig,
    /** 이 방에 들어오려면 필요한 최소 앱 버전(방을 만든 클라이언트의 버전). */
    public val minAppVersion: Int,
    public val createdAt: Long,
    public val gameId: String? = null,
    /** 방장이 내보낸 사용자. 같은 방에 다시 들어올 수 없다. */
    public val bannedUids: Set<String> = emptySet(),
    public val version: Long = 0,
) {
    public val humanSeats: List<RoomSeat> get() = seats.filter { it.kind == SeatKind.HUMAN }
    public val botSeats: List<RoomSeat> get() = seats.filter { it.kind == SeatKind.BOT }
    public val isFull: Boolean get() = seats.size >= maxPlayers

    /** 게임 결과가 레이팅에 반영되는 구성인가(D5): 봇·하우스룰·파라미터 변경이 모두 없을 때. */
    public val isRated: Boolean
        get() = botSeats.isEmpty() && ruleSetConfig.houseRules.isEmpty() && ruleSetConfig.paramOverrides.isEmpty()
}

/** 방 목록 한 줄. */
public data class RoomSummary(
    val id: String,
    val title: String,
    val hostName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val hasBots: Boolean,
    val hasCustomRules: Boolean,
    val isRated: Boolean,
    val createdAt: Long,
)

/** 로비에서 행동하는 사용자. */
public data class LobbyUser(val uid: String, val displayName: String)

public enum class RoomError {
    ROOM_NOT_FOUND,
    ROOM_NOT_WAITING,
    ROOM_FULL,
    WRONG_CODE,
    KICKED,
    APP_UPDATE_REQUIRED,
    NOT_IN_ROOM,
    NOT_HOST,
    NOT_ALLOWED,
    INVALID_TITLE,
    INVALID_PLAYER_COUNT,
    INVALID_RULES,
    RULES_CONFLICT,
    NOT_ENOUGH_PLAYERS,
    NOT_ALL_READY,
    TARGET_NOT_FOUND,
    JOIN_CODE_REQUIRED,
    JOIN_CODE_TAKEN,
}

/** 규칙 위반은 예외가 아니라 값으로 돌려준다(엔진과 같은 방식). */
public sealed interface RoomResult<out T> {
    public data class Ok<T>(val value: T) : RoomResult<T>
    public data class Err(val error: RoomError) : RoomResult<Nothing>
}

public inline fun <T, R> RoomResult<T>.map(transform: (T) -> R): RoomResult<R> = when (this) {
    is RoomResult.Ok -> RoomResult.Ok(transform(value))
    is RoomResult.Err -> this
}
