package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.runtime.CommandSource
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal sealed interface Loaded {
    data class Ok(val record: GameRecord) : Loaded
    data class Broken(val reason: RejectReason, val detail: String) : Loaded
}

/** 판정에 필요한 문서를 모은다(관리자 권한). [ranking]은 결과 문서의 순위(버려진 판은 비어 있다). */
internal object GameRecords {
    suspend fun load(store: DocumentStore, gameId: String, game: JsonObject, ranking: List<String>): Loaded {
        fun broken(detail: String) = Loaded.Broken(RejectReason.SETUP_MISMATCH, detail)
        val host = game.str(FirestoreSchema.Game.HOST_UID) ?: return broken("game without a host")
        val seats = game.strings(FirestoreSchema.Game.SEATS) ?: return broken("game without seats")
        val config = game.str(FirestoreSchema.Game.RULE_SET_CONFIG_JSON)?.let { decode(it, RuleSetConfig.serializer()) } ?: return broken("game without rules")
        val createdAt = game.long(FirestoreSchema.Game.CREATED_AT) ?: return broken("game without a start time")

        val setup = store.get(FirestoreSchema.setup(gameId))?.let { doc ->
            val order = doc.strings(FirestoreSchema.Setup.ORDER) ?: return broken("setup without seats")
            val setupConfig = doc.str(FirestoreSchema.Setup.RULE_SET_CONFIG_JSON)?.let { decode(it, RuleSetConfig.serializer()) } ?: return broken("setup without rules")
            val seed = doc.long(FirestoreSchema.Setup.SEED) ?: return broken("setup without a seed")
            GameSetup(gameId, order.map(::PlayerId), setupConfig, seed, doc.str(FirestoreSchema.Setup.FIRST_PLAYER)?.let(::PlayerId))
        }

        val log = store.observeOrdered(FirestoreSchema.log(gameId), FirestoreSchema.Log.VERSION, descending = false, limit = MAX_LOG).first().map { doc ->
            val d = doc.data
            LogRecord(
                version = d.long(FirestoreSchema.Log.VERSION) ?: return Loaded.Broken(RejectReason.LOG_GAP, "log ${doc.id} without a version"),
                command = d.str(FirestoreSchema.Log.COMMAND_JSON)?.let { decode(it, Command.serializer()) }
                    ?: return Loaded.Broken(RejectReason.ILLEGAL_COMMAND, "log ${doc.id}: undecodable command"),
                source = d.str(FirestoreSchema.Log.SOURCE)?.let { s -> CommandSource.entries.firstOrNull { it.name == s } }
                    ?: return Loaded.Broken(RejectReason.ILLEGAL_COMMAND, "log ${doc.id}: unknown source"),
                ref = d.str(FirestoreSchema.Log.REF),
                at = d.long(FirestoreSchema.Log.AT) ?: return Loaded.Broken(RejectReason.LOG_GAP, "log ${doc.id} without a time"),
                writer = d.str(FirestoreSchema.Log.HOST),
            )
        }
        val guestCommands = log.mapNotNull { it.ref }.distinct().mapNotNull { ref ->
            val doc = store.get(FirestoreSchema.command(gameId, ref)) ?: return@mapNotNull null
            val sender = doc.str(FirestoreSchema.Command.SENDER_UID) ?: return@mapNotNull null
            val command = doc.str(FirestoreSchema.Command.COMMAND_JSON)?.let { decode(it, Command.serializer()) } ?: return@mapNotNull null
            ref to GuestCommand(ref, sender, command)
        }.toMap()

        return Loaded.Ok(
            GameRecord(
                gameId = gameId,
                hostUid = host,
                humanSeats = seats,
                rated = true,
                ruleSetConfig = config,
                createdAt = createdAt,
                setup = setup,
                log = log,
                guestCommands = guestCommands,
                resultRanking = ranking,
            ),
        )
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T? = try {
        EngineJson.json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private const val MAX_LOG = 100_000
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonObject.strings(key: String): List<String>? = (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
