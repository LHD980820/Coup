package io.github.lhd980820.coup.runtime.transport

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.runtime.session.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 같은 JVM 안에서 호스트와 게스트를 잇는 전송. 멀티플레이 흐름 전체를 네트워크 없이 테스트하기 위한 것이다(설계 §6.4).
 * 실제 전송처럼 **모든 메시지를 JSON으로 직렬화했다가 되돌려** 전달한다 — 객체를 공유하지 않고, 직렬화할 수 없는 정보가
 * 섞여 있으면 여기서 드러난다. 게스트는 자기 좌석의 뷰만 구독할 수 있다(보안 규칙 흉내).
 * 한 인스턴스는 한 게임용이다.
 */
public class InMemoryTransport {
    private val commands = Channel<IncomingCommand>(Channel.UNLIMITED)
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<AckResult>>()
    private val viewFlows = ConcurrentHashMap<PlayerId, MutableSharedFlow<ViewEnvelope>>()
    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val nextCommand = AtomicLong()

    /** 가장 최근에 받은 권한자 백업(호스트 재시작 테스트용). */
    @Volatile
    public var lastBackup: String? = null
        private set

    /** 호스트가 [GameTransport.Host.finish]로 알린 결과. */
    @Volatile
    public var result: GameResultRecord? = null
        private set

    /** 호스트가 사라진 것처럼 게스트의 연결 상태를 [ConnectionState.HOST_LOST]로 만든다. */
    public fun dropHost() {
        connection.value = ConnectionState.HOST_LOST
    }

    private fun flowOf(player: PlayerId) = viewFlows.getOrPut(player) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T =
        EngineJson.json.decodeFromString(serializer, EngineJson.json.encodeToString(serializer, value))

    public val host: GameTransport.Host = object : GameTransport.Host {
        override suspend fun publish(gameId: String, publication: Publication) {
            lastBackup = publication.authorityBackup
            connection.value = ConnectionState.CONNECTED
            publication.views.forEach { (player, envelope) ->
                flowOf(player).emit(roundTrip(ViewEnvelope.serializer(), envelope))
            }
        }

        override fun incomingCommands(gameId: String): Flow<IncomingCommand> = commands.receiveAsFlow()

        override suspend fun acknowledge(gameId: String, commandId: String, ack: AckResult) {
            pendingAcks.remove(commandId)?.complete(roundTrip(AckResult.serializer(), ack))
        }

        override suspend fun finish(gameId: String, result: GameResultRecord) {
            this@InMemoryTransport.result = result
            connection.value = ConnectionState.CLOSED
        }
    }

    /** [uid]로 인증된 게스트의 통로. */
    public fun guest(uid: String): GameTransport.Guest = object : GameTransport.Guest {
        override fun observeMyView(gameId: String, me: PlayerId): Flow<ViewEnvelope> {
            require(me.value == uid) { "guest $uid cannot observe the view of ${me.value}" }
            return flowOf(me)
        }

        override fun connection(gameId: String): Flow<ConnectionState> = connection

        override suspend fun sendCommand(gameId: String, command: Command): AckResult {
            val id = "c${nextCommand.incrementAndGet()}"
            val ack = CompletableDeferred<AckResult>()
            pendingAcks[id] = ack
            commands.send(IncomingCommand(id, uid, roundTrip(Command.serializer(), command)))
            return ack.await()
        }
    }
}
