package io.github.lhd980820.coup.lobby

import io.github.lhd980820.coup.engine.rules.RuleSet
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry

/** 이 클라이언트가 어떤 방에 들어올 수 있는지 판단하는 정보. */
public class ClientInfo(public val appVersion: Int, private val registry: RuleSetRegistry) {
    /** 이 앱이 [config]의 룰셋을 만들 수 있는가(모르는 기반/하우스룰/버전이면 false -> 업데이트 필요). */
    public fun supports(config: RuleSetConfig): Boolean = runCatching { registry.build(config) }.isSuccess
}

/**
 * 방의 모든 규칙. 전부 `(방, 행위자, ...) -> 새 방 또는 오류`인 **순수 함수**라서 동시성은 저장소의 원자적 읽기-수정-쓰기가 맡고,
 * 여기서는 상태 전이의 옳고 그름만 판단한다.
 *
 * 핵심 원칙: **게임에 영향을 주는 변경(룰셋, 봇 추가/제거)이 생기면 게스트의 준비 상태를 초기화한다.**
 * 그러지 않으면 모두 준비한 뒤 방장이 규칙을 바꿔 게스트가 동의하지 않은 게임을 시작할 수 있다.
 */
public object RoomRules {
    public const val MAX_TITLE_LENGTH: Int = 30

    // ---- 생성 ---------------------------------------------------------------------------------

    public fun create(
        id: String,
        title: String,
        host: LobbyUser,
        visibility: Visibility,
        joinCode: String?,
        maxPlayers: Int,
        ruleSetConfig: RuleSetConfig,
        client: ClientInfo,
        registry: RuleSetRegistry,
        now: Long,
    ): RoomResult<Room> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > MAX_TITLE_LENGTH) return err(RoomError.INVALID_TITLE)
        val rules = buildRules(registry, ruleSetConfig) ?: return err(RoomError.INVALID_RULES)
        if (maxPlayers !in rules.params.minPlayers..rules.params.maxPlayers) return err(RoomError.INVALID_PLAYER_COUNT)
        if (visibility == Visibility.PRIVATE && joinCode.isNullOrBlank()) return err(RoomError.JOIN_CODE_REQUIRED)
        return ok(
            Room(
                id = id,
                title = cleanTitle,
                hostUid = host.uid,
                visibility = visibility,
                joinCode = if (visibility == Visibility.PRIVATE) joinCode else null,
                maxPlayers = maxPlayers,
                seats = listOf(RoomSeat(host.uid, host.displayName, SeatKind.HUMAN, ready = true)),
                status = RoomStatus.WAITING,
                ruleSetConfig = ruleSetConfig,
                minAppVersion = client.appVersion,
                createdAt = now,
            ),
        )
    }

    // ---- 입장/퇴장 ----------------------------------------------------------------------------

    /** 이미 앉아 있는 사용자의 재입장은 오류 없이 방을 그대로 돌려준다(재접속). */
    public fun join(room: Room, user: LobbyUser, client: ClientInfo, code: String?): RoomResult<Room> {
        if (room.status != RoomStatus.WAITING) return if (room.seats.any { it.id == user.uid }) ok(room) else err(RoomError.ROOM_NOT_WAITING)
        if (room.seats.any { it.id == user.uid }) return ok(room)
        if (user.uid in room.bannedUids) return err(RoomError.KICKED)
        if (client.appVersion < room.minAppVersion || !client.supports(room.ruleSetConfig)) return err(RoomError.APP_UPDATE_REQUIRED)
        if (room.visibility == Visibility.PRIVATE && !codeMatches(room.joinCode, code)) return err(RoomError.WRONG_CODE)
        if (room.isFull) return err(RoomError.ROOM_FULL)
        return ok(room.copy(seats = room.seats + RoomSeat(user.uid, user.displayName, SeatKind.HUMAN, ready = false)))
    }

    /** 방장이 나가면 방이 닫힌다(옛 앱의 "방 폭파"와 같다). 게스트가 나가면 자리만 비운다. */
    public fun leave(room: Room, uid: String): RoomResult<Room> {
        if (room.seats.none { it.id == uid && it.kind == SeatKind.HUMAN }) return err(RoomError.NOT_IN_ROOM)
        if (uid == room.hostUid) return ok(room.copy(status = RoomStatus.CLOSED))
        if (room.status != RoomStatus.WAITING) return err(RoomError.ROOM_NOT_WAITING)
        return ok(room.copy(seats = room.seats.filter { it.id != uid }))
    }

    public fun kick(room: Room, byUid: String, targetUid: String): RoomResult<Room> {
        hostGuard(room, byUid)?.let { return it }
        if (targetUid == room.hostUid) return err(RoomError.NOT_ALLOWED)
        if (room.seats.none { it.id == targetUid && it.kind == SeatKind.HUMAN }) return err(RoomError.TARGET_NOT_FOUND)
        return ok(room.copy(seats = room.seats.filter { it.id != targetUid }, bannedUids = room.bannedUids + targetUid))
    }

    // ---- 준비 ---------------------------------------------------------------------------------

    public fun setReady(room: Room, uid: String, ready: Boolean): RoomResult<Room> {
        if (room.status != RoomStatus.WAITING) return err(RoomError.ROOM_NOT_WAITING)
        val seat = room.seats.firstOrNull { it.id == uid && it.kind == SeatKind.HUMAN } ?: return err(RoomError.NOT_IN_ROOM)
        if (uid == room.hostUid) return err(RoomError.NOT_ALLOWED) // 방장은 항상 준비 상태
        return ok(room.copy(seats = room.seats.map { if (it.id == seat.id) it.copy(ready = ready) else it }))
    }

    // ---- 봇 -----------------------------------------------------------------------------------

    /** [botId]는 방 안에서 유일해야 한다(서비스가 만든다). 게스트의 동의가 필요한 변경이라 준비를 초기화한다. */
    public fun addBot(room: Room, byUid: String, botId: String, displayName: String, level: BotLevel, style: BotStyle): RoomResult<Room> {
        hostGuard(room, byUid)?.let { return it }
        if (room.isFull) return err(RoomError.ROOM_FULL)
        if (room.seats.any { it.id == botId }) return err(RoomError.NOT_ALLOWED)
        val bot = RoomSeat(botId, displayName, SeatKind.BOT, ready = true, botLevel = level, botStyle = style)
        return ok(resetGuestReadiness(room.copy(seats = room.seats + bot)))
    }

    public fun removeBot(room: Room, byUid: String, botId: String): RoomResult<Room> {
        hostGuard(room, byUid)?.let { return it }
        if (room.seats.none { it.id == botId && it.kind == SeatKind.BOT }) return err(RoomError.TARGET_NOT_FOUND)
        return ok(resetGuestReadiness(room.copy(seats = room.seats.filter { it.id != botId })))
    }

    // ---- 설정 변경 ----------------------------------------------------------------------------

    /**
     * 룰셋을 바꾼다. 새 룰셋이 만들어져야 하고, 지금 앉은 인원이 새 인원 범위를 넘으면 [RoomError.RULES_CONFLICT].
     * 최대 인원은 새 범위 안으로 맞춘다. 같은 설정이면 아무것도 바꾸지 않는다(준비도 유지).
     */
    public fun updateRules(room: Room, byUid: String, config: RuleSetConfig, registry: RuleSetRegistry): RoomResult<Room> {
        hostGuard(room, byUid)?.let { return it }
        if (config == room.ruleSetConfig) return ok(room)
        val rules = buildRules(registry, config) ?: return err(RoomError.INVALID_RULES)
        if (room.seats.size > rules.params.maxPlayers) return err(RoomError.RULES_CONFLICT)
        val max = room.maxPlayers.coerceIn(maxOf(rules.params.minPlayers, room.seats.size), rules.params.maxPlayers)
        return ok(resetGuestReadiness(room.copy(ruleSetConfig = config, maxPlayers = max)))
    }

    /** 최대 인원을 바꾼다. 앉은 인원보다 작거나 룰셋의 범위 밖이면 거절. 게임 내용이 아니므로 준비는 유지한다. */
    public fun setMaxPlayers(room: Room, byUid: String, maxPlayers: Int, registry: RuleSetRegistry): RoomResult<Room> {
        hostGuard(room, byUid)?.let { return it }
        val rules = buildRules(registry, room.ruleSetConfig) ?: return err(RoomError.INVALID_RULES)
        if (maxPlayers !in rules.params.minPlayers..rules.params.maxPlayers || maxPlayers < room.seats.size) {
            return err(RoomError.INVALID_PLAYER_COUNT)
        }
        return ok(room.copy(maxPlayers = maxPlayers))
    }

    // ---- 시작/종료 ----------------------------------------------------------------------------

    /** 지금 시작할 수 없는 이유들(빈 목록이면 시작 가능). 방장 화면의 시작 버튼 상태와 안내에 쓴다. */
    public fun startBlockers(room: Room, registry: RuleSetRegistry): List<RoomError> = buildList {
        if (room.status != RoomStatus.WAITING) add(RoomError.ROOM_NOT_WAITING)
        val rules = buildRules(registry, room.ruleSetConfig)
        if (rules == null) {
            add(RoomError.INVALID_RULES)
        } else if (room.seats.size < rules.params.minPlayers) {
            add(RoomError.NOT_ENOUGH_PLAYERS)
        }
        if (room.humanSeats.any { !it.ready }) add(RoomError.NOT_ALL_READY)
    }

    /** 게임을 시작한다: 상태를 PLAYING으로, [gameId]를 기록한다. */
    public fun start(room: Room, byUid: String, gameId: String, registry: RuleSetRegistry): RoomResult<Room> {
        if (byUid != room.hostUid) return err(RoomError.NOT_HOST)
        startBlockers(room, registry).firstOrNull()?.let { return err(it) }
        return ok(room.copy(status = RoomStatus.PLAYING, gameId = gameId))
    }

    /** 게임이 끝나 대기실로 돌아간다(다시 하기). 게스트는 다시 준비해야 한다. */
    public fun endGame(room: Room, byUid: String): RoomResult<Room> {
        if (byUid != room.hostUid) return err(RoomError.NOT_HOST)
        if (room.status != RoomStatus.PLAYING) return err(RoomError.NOT_ALLOWED)
        return ok(resetGuestReadiness(room.copy(status = RoomStatus.WAITING, gameId = null)))
    }

    // ---- 도우미 -------------------------------------------------------------------------------

    private fun hostGuard(room: Room, byUid: String): RoomResult.Err? = when {
        byUid != room.hostUid -> RoomResult.Err(RoomError.NOT_HOST)
        room.status != RoomStatus.WAITING -> RoomResult.Err(RoomError.ROOM_NOT_WAITING)
        else -> null
    }

    private fun resetGuestReadiness(room: Room): Room =
        room.copy(seats = room.seats.map { if (it.kind == SeatKind.HUMAN && it.id != room.hostUid) it.copy(ready = false) else it })

    private fun codeMatches(expected: String?, given: String?): Boolean =
        expected != null && given != null && JoinCodes.normalize(given) == JoinCodes.normalize(expected)

    private fun buildRules(registry: RuleSetRegistry, config: RuleSetConfig): RuleSet? = runCatching { registry.build(config) }.getOrNull()

    private fun ok(room: Room): RoomResult<Room> = RoomResult.Ok(room)
    private fun err(error: RoomError): RoomResult.Err = RoomResult.Err(error)
}

/** 참가 코드: 헷갈리는 글자(0/O, 1/I/L)를 뺀 6자리. 입력은 대소문자·공백을 무시한다. */
public object JoinCodes {
    public const val LENGTH: Int = 6
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    public fun generate(random: kotlin.random.Random): String = buildString {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    public fun normalize(input: String): String = input.trim().uppercase()

    public fun isWellFormed(input: String): Boolean = normalize(input).let { it.length == LENGTH && it.all { c -> c in ALPHABET } }
}
