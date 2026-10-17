package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.BotStyle
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.RoomSeat
import io.github.lhd980820.coup.lobby.RoomStatus
import io.github.lhd980820.coup.lobby.SeatKind
import io.github.lhd980820.coup.lobby.Visibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import io.github.lhd980820.coup.remote.FirestoreSchema.Room as F

/**
 * [Room] <-> `rooms/{id}` 문서. 갱신은 **이전 문서에서 바뀐 필드만** 바꿔서 만든다 — 규칙이 "이 행위자가 바꿀 수 있는 필드만 바뀌었는가"를
 * 필드 단위로 검사하므로, 바뀌지 않은 필드는 저장된 그대로여야 한다.
 */
internal object RoomDocuments {
    private const val NOT_READY = -1L

    fun decode(id: String, doc: JsonObject): Room? {
        val configVersion = doc.long(F.CONFIG_VERSION) ?: return null
        val hostUid = doc.str(F.HOST_UID) ?: return null
        val members = (doc[F.MEMBERS] as? JsonObject) ?: return null
        val humans = members.map { (uid, e) ->
            val entry = e as? JsonObject ?: return null
            val seq = entry.long(FirestoreSchema.Member.SEQ) ?: return null
            val ready = uid == hostUid || entry.long(FirestoreSchema.Member.READY_VERSION) == configVersion
            seq to RoomSeat(uid, entry.str(FirestoreSchema.Member.NAME) ?: return null, SeatKind.HUMAN, ready)
        }
        val bots = ((doc[F.BOTS] as? JsonArray) ?: return null).map { e ->
            val b = e as? JsonObject ?: return null
            (b.long(FirestoreSchema.Bot.SEQ) ?: return null) to RoomSeat(
                id = b.str(FirestoreSchema.Bot.ID) ?: return null,
                displayName = b.str(FirestoreSchema.Bot.NAME) ?: return null,
                kind = SeatKind.BOT,
                ready = true,
                botLevel = runCatching { BotLevel.valueOf(b.str(FirestoreSchema.Bot.LEVEL)!!) }.getOrNull() ?: return null,
                botStyle = runCatching { BotStyle.valueOf(b.str(FirestoreSchema.Bot.STYLE)!!) }.getOrNull() ?: return null,
            )
        }
        return Room(
            id = id,
            title = doc.str(F.TITLE) ?: return null,
            hostUid = hostUid,
            visibility = runCatching { Visibility.valueOf(doc.str(F.VISIBILITY)!!) }.getOrNull() ?: return null,
            joinCode = doc.str(F.JOIN_CODE),
            maxPlayers = doc.long(F.MAX_PLAYERS)?.toInt() ?: return null,
            seats = (humans + bots).sortedWith(compareBy({ it.first }, { it.second.id })).map { it.second },
            status = runCatching { RoomStatus.valueOf(doc.str(F.STATUS)!!) }.getOrNull() ?: return null,
            ruleSetConfig = decodeRuleSet(doc[F.RULE_SET] as? JsonObject ?: return null) ?: return null,
            minAppVersion = doc.long(F.MIN_APP_VERSION)?.toInt() ?: return null,
            createdAt = doc.long(F.CREATED_AT) ?: return null,
            gameId = doc.str(F.GAME_ID),
            bannedUids = (doc[F.BANNED] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),
            version = doc.long(F.VERSION) ?: return null,
        )
    }

    /** 새 방(version 1, configVersion 0). */
    fun encodeNew(room: Room): JsonObject {
        val empty = buildJsonObject {
            put(F.MEMBERS, JsonObject(emptyMap()))
            put(F.MEMBER_ORDER, JsonArray(emptyList()))
            put(F.BOTS, JsonArray(emptyList()))
            put(F.CONFIG_VERSION, 0L)
            put(F.VERSION, 0L)
        }
        return encodeUpdate(empty, null, room, actorUid = room.hostUid)
    }

    /**
     * [before] 문서(그때의 방 [beforeRoom])에서 [after]로 바뀐 만큼만 고친 새 문서. version은 1 오른다.
     * 룰셋·봇이 바뀌거나 게임이 끝나 대기실로 돌아오면 configVersion을 올려 게스트의 준비를 푼다.
     * 사람 좌석 항목은 [actorUid] 본인 것만 새로 쓰고(입장·준비), 나머지는 저장된 그대로 두거나 지운다(퇴장·강퇴).
     */
    fun encodeUpdate(before: JsonObject, beforeRoom: Room?, after: Room, actorUid: String): JsonObject {
        val out = before.toMutableMap()
        fun set(key: String, value: JsonElement) {
            if (before[key] != value) out[key] = value
        }
        val oldConfig = before.long(F.CONFIG_VERSION) ?: 0L
        val oldVersion = before.long(F.VERSION) ?: 0L
        val bump = beforeRoom != null && (
            beforeRoom.ruleSetConfig != after.ruleSetConfig ||
                beforeRoom.botSeats != after.botSeats ||
                (beforeRoom.status == RoomStatus.PLAYING && after.status == RoomStatus.WAITING)
            )
        val configVersion = if (bump) oldConfig + 1 else oldConfig

        set(F.TITLE, JsonPrimitive(after.title))
        set(F.HOST_UID, JsonPrimitive(after.hostUid))
        set(F.VISIBILITY, JsonPrimitive(after.visibility.name))
        if (after.joinCode != null) set(F.JOIN_CODE, JsonPrimitive(after.joinCode))
        set(F.MAX_PLAYERS, JsonPrimitive(after.maxPlayers))
        set(F.STATUS, JsonPrimitive(after.status.name))
        set(F.RULE_SET, encodeRuleSet(after.ruleSetConfig))
        set(F.MIN_APP_VERSION, JsonPrimitive(after.minAppVersion))
        set(F.CREATED_AT, JsonPrimitive(after.createdAt))
        set(F.GAME_ID, after.gameId?.let(::JsonPrimitive) ?: JsonNull)
        set(F.BANNED, buildJsonArray { after.bannedUids.sorted().forEach { add(JsonPrimitive(it)) } })
        set(F.CONFIG_VERSION, JsonPrimitive(configVersion))

        // 사람 좌석
        val oldMembers = (before[F.MEMBERS] as? JsonObject) ?: JsonObject(emptyMap())
        val nextSeq = oldVersion + 1
        val members = after.humanSeats.associate { seat ->
            val existing = oldMembers[seat.id] as? JsonObject
            val entry = when {
                // 방장 항목은 쓰지 않는다(방장은 늘 준비 상태로 본다) — 규칙은 방장이 사람 항목을 고치는 것을 막는다
                existing != null && (seat.id != actorUid || seat.id == after.hostUid) -> existing
                existing != null -> JsonObject(existing + (FirestoreSchema.Member.READY_VERSION to JsonPrimitive(readyVersion(seat, after, configVersion))))
                else -> buildJsonObject {
                    put(FirestoreSchema.Member.NAME, seat.displayName)
                    put(FirestoreSchema.Member.READY_VERSION, readyVersion(seat, after, configVersion))
                    put(FirestoreSchema.Member.SEQ, nextSeq)
                }
            }
            seat.id to entry
        }
        set(F.MEMBERS, JsonObject(members))
        val oldOrder = (before[F.MEMBER_ORDER] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val order = oldOrder.filter { it in members } + after.humanSeats.map { it.id }.filter { it !in oldOrder }
        set(F.MEMBER_ORDER, buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })

        // 봇
        val oldBots = ((before[F.BOTS] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
            .associateBy { it.str(FirestoreSchema.Bot.ID) }
        set(
            F.BOTS,
            buildJsonArray {
                after.botSeats.forEach { bot ->
                    add(
                        buildJsonObject {
                            put(FirestoreSchema.Bot.ID, bot.id)
                            put(FirestoreSchema.Bot.NAME, bot.displayName)
                            put(FirestoreSchema.Bot.LEVEL, (bot.botLevel ?: BotLevel.NORMAL).name)
                            put(FirestoreSchema.Bot.STYLE, (bot.botStyle ?: BotStyle.BALANCED).name)
                            put(FirestoreSchema.Bot.SEQ, oldBots[bot.id]?.long(FirestoreSchema.Bot.SEQ) ?: nextSeq)
                        },
                    )
                }
            },
        )
        out[F.VERSION] = JsonPrimitive(oldVersion + 1)
        return JsonObject(out)
    }

    private fun readyVersion(seat: RoomSeat, room: Room, configVersion: Long): Long =
        if (seat.id == room.hostUid || seat.ready) configVersion else NOT_READY

    fun encodeRuleSet(config: RuleSetConfig): JsonObject = buildJsonObject {
        put(FirestoreSchema.RuleSet.BASE_ID, config.baseId)
        put(FirestoreSchema.RuleSet.BASE_VERSION, config.baseVersion)
        put(FirestoreSchema.RuleSet.HOUSE_RULES, buildJsonArray { config.houseRules.sorted().forEach { add(JsonPrimitive(it)) } })
        put(FirestoreSchema.RuleSet.PARAM_OVERRIDES, buildJsonObject { config.paramOverrides.toSortedMap().forEach { (k, v) -> put(k, v) } })
    }

    private fun decodeRuleSet(o: JsonObject): RuleSetConfig? = RuleSetConfig(
        baseId = o.str(FirestoreSchema.RuleSet.BASE_ID) ?: return null,
        baseVersion = o.long(FirestoreSchema.RuleSet.BASE_VERSION)?.toInt() ?: return null,
        houseRules = (o[FirestoreSchema.RuleSet.HOUSE_RULES] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return null,
        paramOverrides = (o[FirestoreSchema.RuleSet.PARAM_OVERRIDES] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: return null }
            ?: return null,
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
