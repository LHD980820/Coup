package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.session.ConnectionState
import io.github.lhd980820.coup.runtime.transport.AckResult
import io.github.lhd980820.coup.runtime.transport.GameResultRecord
import io.github.lhd980820.coup.runtime.transport.GameTransport
import io.github.lhd980820.coup.runtime.transport.IncomingCommand
import io.github.lhd980820.coup.runtime.transport.Publication
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.runtime.transport.ViewEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [GameTransport]의 Firestore 구현(설계 §8). [DocumentStore] 위에서 동작하므로 Firebase 없이 테스트된다.
 * 방장 기기는 [host]를, 게스트 기기는 [guest]를 쓴다. 각각 **자기 인증 컨텍스트의 [DocumentStore]** 를 받는다.
 *
 * - 방장은 상태가 바뀔 때마다 좌석별 뷰 + 관전 뷰 + 권한자 백업 + 게임 메타를 **한 번의 배치**로 쓴다.
 * - 게스트는 자기 좌석 뷰 문서 하나만 구독한다. 명령은 `commands/{id}`에 PENDING으로 쓰고 방장이 APPLIED/REJECTED로 바꾸기를 기다린다.
 * - 게스트의 연결 상태는 방장 생존 신호(`hostHeartbeatAt`)로 판단한다.
 */
public class FirestoreGameTransport(
    private val store: DocumentStore,
    private val clock: Clock = Clock.System,
    private val newCommandId: () -> String = { UUID.randomUUID().toString() },
    private val hostLostAfter: Duration = 30.seconds,
    private val connectionTick: Duration = 5.seconds,
) {
    private val json = EngineJson.json

    // ---- 게임 시작 ----------------------------------------------------------------------------

    /**
     * 게임 문서를 만든다(방장이 게임을 시작할 때 한 번). [seats]는 모든 좌석의 uid(방장 포함, 봇 제외)다.
     * 보안 규칙은 이 목록으로 "누가 이 게임에 접근할 수 있는가"를 판단한다.
     */
    public suspend fun createGame(gameId: String, hostUid: String, seats: List<String>, ruleSetConfig: RuleSetConfig, rated: Boolean) {
        require(hostUid in seats) { "the host must be one of the seats" }
        guarded {
            store.write(
                listOf(
                    WriteOp.Set(
                        FirestoreSchema.game(gameId),
                        buildJsonObject {
                            put(FirestoreSchema.Game.HOST_UID, hostUid)
                            put(FirestoreSchema.Game.SEATS, buildJsonArray { seats.forEach { add(JsonPrimitive(it)) } })
                            put(FirestoreSchema.Game.STATUS, FirestoreSchema.GameStatus.PLAYING)
                            put(FirestoreSchema.Game.VERSION, 0L)
                            put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, clock.nowMillis())
                            put(FirestoreSchema.Game.RULE_SET_CONFIG_JSON, json.encodeToString(RuleSetConfig.serializer(), ruleSetConfig))
                            put(FirestoreSchema.Game.RATED, rated)
                        },
                    ),
                ),
            )
        }
    }

    // ---- 방장 ---------------------------------------------------------------------------------

    public val host: GameTransport.Host = object : GameTransport.Host {
        override suspend fun publish(gameId: String, publication: Publication) {
            val ops = buildList {
                publication.views.forEach { (player, envelope) -> add(viewOp(gameId, player.value, envelope)) }
                add(viewOp(gameId, FirestoreSchema.SPECTATOR_VIEW, publication.spectator))
                add(
                    WriteOp.Set(
                        FirestoreSchema.authority(gameId),
                        buildJsonObject {
                            put(FirestoreSchema.Authority.VERSION, publication.version)
                            put(FirestoreSchema.Authority.BACKUP_JSON, publication.authorityBackup)
                        },
                    ),
                )
                add(
                    WriteOp.Merge(
                        FirestoreSchema.game(gameId),
                        buildJsonObject {
                            put(FirestoreSchema.Game.VERSION, publication.version)
                            put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, clock.nowMillis())
                        },
                    ),
                )
            }
            guarded { store.write(ops) }
        }

        override fun incomingCommands(gameId: String): Flow<IncomingCommand> {
            val seen = mutableSetOf<String>()
            return store.observeWhere(FirestoreSchema.commands(gameId), FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.PENDING)
                .transform { docs ->
                    docs.sortedWith(compareBy({ it.data.long(FirestoreSchema.Command.CREATED_AT) ?: 0L }, { it.id }))
                        .filter { seen.add(it.id) }
                        .forEach { doc -> parseIncoming(gameId, doc)?.let { emit(it) } }
                }
        }

        override suspend fun acknowledge(gameId: String, commandId: String, ack: AckResult) {
            val fields = buildJsonObject {
                when (ack) {
                    is AckResult.Accepted -> {
                        put(FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.APPLIED)
                        put(FirestoreSchema.Command.APPLIED_VERSION, ack.version)
                    }
                    is AckResult.Rejected -> {
                        put(FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.REJECTED)
                        put(FirestoreSchema.Command.REJECTION, ack.reason.name)
                    }
                }
            }
            guarded { store.write(listOf(WriteOp.Merge(FirestoreSchema.command(gameId, commandId), fields))) }
        }

        override suspend fun finish(gameId: String, result: GameResultRecord) {
            val doc = buildJsonObject {
                put(FirestoreSchema.Result.RANKING, buildJsonArray { result.ranking.forEach { add(JsonPrimitive(it.value)) } })
                put(FirestoreSchema.Result.PLAYER_COUNT, result.ranking.size)
                put(FirestoreSchema.Result.RULE_SET_CONFIG_JSON, json.encodeToString(RuleSetConfig.serializer(), result.ruleSetConfig))
                put(FirestoreSchema.Result.RATED, result.rated)
                put(
                    FirestoreSchema.Result.RATING_DELTAS,
                    buildJsonObject { result.ratingDeltas.forEach { (player, delta) -> put(player.value, delta) } },
                )
                put(FirestoreSchema.Result.FINISHED_AT, clock.nowMillis())
            }
            guarded {
                store.write(
                    listOf(
                        WriteOp.Set(FirestoreSchema.result(gameId), doc),
                        WriteOp.Merge(
                            FirestoreSchema.game(gameId),
                            buildJsonObject { put(FirestoreSchema.Game.STATUS, FirestoreSchema.GameStatus.FINISHED) },
                        ),
                    ),
                )
            }
        }

        override suspend fun heartbeat(gameId: String) {
            guarded {
                store.write(
                    listOf(
                        WriteOp.Merge(
                            FirestoreSchema.game(gameId),
                            buildJsonObject { put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, clock.nowMillis()) },
                        ),
                    ),
                )
            }
        }
    }

    private fun viewOp(gameId: String, viewerId: String, envelope: ViewEnvelope): WriteOp =
        WriteOp.Set(
            FirestoreSchema.view(gameId, viewerId),
            buildJsonObject {
                put(FirestoreSchema.View.VERSION, envelope.version)
                put(FirestoreSchema.View.ENVELOPE_JSON, json.encodeToString(ViewEnvelope.serializer(), envelope))
            },
        )

    /** 형식이 잘못된 명령은 방장이 REJECTED로 표시하고 건너뛴다(악의적인 게스트가 방장을 멈추게 하지 못하도록). */
    private suspend fun parseIncoming(gameId: String, doc: StoredDocument): IncomingCommand? {
        val sender = doc.data.string(FirestoreSchema.Command.SENDER_UID)
        val raw = doc.data.string(FirestoreSchema.Command.COMMAND_JSON)
        val command = try {
            raw?.let { json.decodeFromString(Command.serializer(), it) }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (sender == null || command == null) {
            host.acknowledge(gameId, doc.id, AckResult.Rejected(Rejection.WRONG_PHASE))
            return null
        }
        return IncomingCommand(doc.id, sender, command)
    }

    // ---- 게스트 -------------------------------------------------------------------------------

    /** [uid]로 인증된 게스트 통로. [store]는 그 사용자의 인증 컨텍스트로 동작해야 한다. */
    public fun guest(uid: String): GameTransport.Guest = object : GameTransport.Guest {
        override fun observeMyView(gameId: String, me: PlayerId): Flow<ViewEnvelope> {
            require(me.value == uid) { "guest $uid cannot observe the view of ${me.value}" }
            return store.observe(FirestoreSchema.view(gameId, me.value))
                .filterNotNull()
                .map { doc ->
                    val text = doc.string(FirestoreSchema.View.ENVELOPE_JSON)
                        ?: throw TransportException("view document without an envelope")
                    try {
                        json.decodeFromString(ViewEnvelope.serializer(), text)
                    } catch (e: SerializationException) {
                        throw TransportException("undecodable view envelope", e)
                    }
                }
        }

        override fun connection(gameId: String): Flow<ConnectionState> {
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(connectionTick)
                }
            }
            return combine(store.observe(FirestoreSchema.game(gameId)), ticker) { game, _ -> connectionOf(game) }.distinctUntilChanged()
        }

        override suspend fun sendCommand(gameId: String, command: Command): AckResult {
            val id = newCommandId()
            val path = FirestoreSchema.command(gameId, id)
            guarded {
                store.write(
                    listOf(
                        WriteOp.Set(
                            path,
                            buildJsonObject {
                                put(FirestoreSchema.Command.SENDER_UID, uid)
                                put(FirestoreSchema.Command.COMMAND_JSON, json.encodeToString(Command.serializer(), command))
                                command.expectedVersion?.let { put(FirestoreSchema.Command.EXPECTED_VERSION, it) }
                                put(FirestoreSchema.Command.CREATED_AT, clock.nowMillis())
                                put(FirestoreSchema.Command.STATUS, FirestoreSchema.CommandStatus.PENDING)
                            },
                        ),
                    ),
                )
            }
            val decided = guarded {
                store.observe(path).filterNotNull().first { it.string(FirestoreSchema.Command.STATUS) != FirestoreSchema.CommandStatus.PENDING }
            }
            return when (decided.string(FirestoreSchema.Command.STATUS)) {
                FirestoreSchema.CommandStatus.APPLIED -> AckResult.Accepted(decided.long(FirestoreSchema.Command.APPLIED_VERSION) ?: 0L)
                else -> AckResult.Rejected(
                    // 모르는 사유(형식 오류 등)는 WRONG_PHASE로 본다
                    Rejection.entries.firstOrNull { it.name == decided.string(FirestoreSchema.Command.REJECTION) } ?: Rejection.WRONG_PHASE,
                )
            }
        }
    }

    private fun connectionOf(game: JsonObject?): ConnectionState {
        if (game == null) return ConnectionState.RECONNECTING
        if (game.string(FirestoreSchema.Game.STATUS) == FirestoreSchema.GameStatus.FINISHED) return ConnectionState.CLOSED
        val heartbeat = game.long(FirestoreSchema.Game.HOST_HEARTBEAT_AT) ?: return ConnectionState.RECONNECTING
        return if (clock.nowMillis() - heartbeat > hostLostAfter.inWholeMilliseconds) ConnectionState.HOST_LOST else ConnectionState.CONNECTED
    }

    /** 저장소 오류는 모두 [TransportException]으로 통일한다(취소는 그대로 전파). */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw TransportException("document store failure: ${e.message}", e)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
