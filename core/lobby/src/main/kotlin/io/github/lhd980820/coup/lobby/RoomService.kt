package io.github.lhd980820.coup.lobby

import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.runtime.Clock
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/**
 * 로비 유스케이스. 규칙은 [RoomRules](순수 함수), 동시성은 [RoomStore.update](원자적 갱신)이 맡고
 * 이 클래스는 둘을 이어 붙이고 ID/참가 코드/시드를 만든다.
 */
public class RoomService(
    private val store: RoomStore,
    private val registry: RuleSetRegistry,
    private val client: ClientInfo,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {
    public suspend fun createRoom(
        host: LobbyUser,
        title: String,
        visibility: Visibility,
        maxPlayers: Int,
        ruleSetConfig: RuleSetConfig,
    ): RoomResult<Room> {
        repeat(CODE_ATTEMPTS) {
            val code = if (visibility == Visibility.PRIVATE) JoinCodes.generate(random) else null
            val room = when (
                val made = RoomRules.create(newId(), title, host, visibility, code, maxPlayers, ruleSetConfig, client, registry, clock.nowMillis())
            ) {
                is RoomResult.Err -> return made
                is RoomResult.Ok -> made.value
            }
            when (val saved = store.create(room)) {
                is RoomResult.Ok -> return saved
                is RoomResult.Err -> if (saved.error != RoomError.JOIN_CODE_TAKEN) return saved
            }
        }
        return RoomResult.Err(RoomError.JOIN_CODE_TAKEN)
    }

    public suspend fun join(roomId: String, user: LobbyUser, code: String? = null): RoomResult<Room> =
        store.update(roomId) { RoomRules.join(it, user, client, code) }

    /** 참가 코드로 입장(비공개 방). */
    public suspend fun joinByCode(user: LobbyUser, code: String): RoomResult<Room> {
        if (!JoinCodes.isWellFormed(code)) return RoomResult.Err(RoomError.WRONG_CODE)
        val room = store.findByJoinCode(code) ?: return RoomResult.Err(RoomError.ROOM_NOT_FOUND)
        return join(room.id, user, code)
    }

    public suspend fun leave(roomId: String, uid: String): RoomResult<Room> = store.update(roomId) { RoomRules.leave(it, uid) }

    public suspend fun kick(roomId: String, byUid: String, targetUid: String): RoomResult<Room> =
        store.update(roomId) { RoomRules.kick(it, byUid, targetUid) }

    public suspend fun setReady(roomId: String, uid: String, ready: Boolean): RoomResult<Room> =
        store.update(roomId) { RoomRules.setReady(it, uid, ready) }

    public suspend fun addBot(roomId: String, byUid: String, level: BotLevel = BotLevel.NORMAL, style: BotStyle = BotStyle.BALANCED): RoomResult<Room> =
        store.update(roomId) { room ->
            val n = generateSequence(1) { it + 1 }.first { i -> room.seats.none { s -> s.id == "bot-$i" } }
            RoomRules.addBot(room, byUid, "bot-$n", "Bot $n", level, style)
        }

    public suspend fun removeBot(roomId: String, byUid: String, botId: String): RoomResult<Room> =
        store.update(roomId) { RoomRules.removeBot(it, byUid, botId) }

    public suspend fun updateRules(roomId: String, byUid: String, config: RuleSetConfig): RoomResult<Room> =
        store.update(roomId) { RoomRules.updateRules(it, byUid, config, registry) }

    public suspend fun setMaxPlayers(roomId: String, byUid: String, maxPlayers: Int): RoomResult<Room> =
        store.update(roomId) { RoomRules.setMaxPlayers(it, byUid, maxPlayers, registry) }

    /** 게임을 시작하고, 방장 기기가 세션을 열 때 쓸 [GameStartPlan]을 돌려준다. */
    public suspend fun start(roomId: String, byUid: String, seed: Long = random.nextLong()): RoomResult<GameStartPlan> {
        val gameId = "g-" + newId()
        return when (val started = store.update(roomId) { RoomRules.start(it, byUid, gameId, registry) }) {
            is RoomResult.Err -> started
            is RoomResult.Ok -> RoomResult.Ok(GameStartPlan.of(started.value, gameId, seed))
        }
    }

    public suspend fun endGame(roomId: String, byUid: String): RoomResult<Room> = store.update(roomId) { RoomRules.endGame(it, byUid) }

    public suspend fun startBlockers(roomId: String): List<RoomError> =
        store.get(roomId)?.let { RoomRules.startBlockers(it, registry) } ?: listOf(RoomError.ROOM_NOT_FOUND)

    public suspend fun listOpen(): List<RoomSummary> = store.listOpen().map { r ->
        RoomSummary(
            id = r.id,
            title = r.title,
            hostName = r.seats.firstOrNull { it.id == r.hostUid }?.displayName.orEmpty(),
            playerCount = r.seats.size,
            maxPlayers = r.maxPlayers,
            hasBots = r.botSeats.isNotEmpty(),
            hasCustomRules = r.ruleSetConfig.houseRules.isNotEmpty() || r.ruleSetConfig.paramOverrides.isNotEmpty(),
            isRated = r.isRated,
            createdAt = r.createdAt,
        )
    }

    public fun observe(roomId: String): Flow<Room?> = store.observe(roomId)

    private fun newId(): String = buildString { repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

    private companion object {
        const val CODE_ATTEMPTS = 5
        const val ID_LENGTH = 12
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
