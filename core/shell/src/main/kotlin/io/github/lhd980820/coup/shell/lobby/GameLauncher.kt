package io.github.lhd980820.coup.shell.lobby

import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.lobby.GameStartPlan
import io.github.lhd980820.coup.lobby.Room
import io.github.lhd980820.coup.lobby.SeatKind
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.GameSession
import io.github.lhd980820.coup.runtime.session.HostGameSession
import io.github.lhd980820.coup.runtime.session.RemoteGameSession
import io.github.lhd980820.coup.runtime.session.SeatInfo
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.github.lhd980820.coup.runtime.SeatKind as RuntimeSeatKind

/** 방에서 게임으로 넘어가는 다리: 방장은 게임을 열고, 게스트는 방장이 연 게임에 붙는다. 실패는 [TransportException]. */
public interface GameLauncher {
    /** 방장: 게임 문서를 만들고 권위 세션을 연다. */
    public suspend fun host(plan: GameStartPlan): GameSession

    /** 게스트: 방장이 게임을 열 때까지 기다렸다가 내 좌석 뷰를 구독하는 세션을 연다. 진행 중인 게임에 다시 들어올 때도 같다. */
    public suspend fun join(room: Room, me: PlayerId): GameSession

    /**
     * 방장: 진행 중인 게임을 다시 연다(앱 재시작·프로세스 종료 뒤). 마지막으로 게시한 상태(권한자 백업)에서 이어 간다.
     * 게임이 이미 끝났으면(결과를 기록했으면) null — 이어 갈 것이 없다.
     */
    public suspend fun resumeHost(room: Room): GameSession?
}

/**
 * [GameLauncher]의 Firestore 구현. [store]는 **이 기기 사용자**의 인증 컨텍스트다. 세션의 코루틴은 [scope]에서 돈다
 * (세션을 닫거나 [scope]를 취소하면 끝난다).
 *
 * 방이 PLAYING이 되는 것과 게임 문서가 생기는 것 사이에는 틈이 있다(규칙이 "방이 이 게임으로 시작됐다"를 요구해서 방이 먼저다).
 * 게스트는 그 틈 동안 게임 문서를 읽을 수 없으므로(거부) 문서가 보일 때까지 다시 시도한다 — 구독을 먼저 열면 거부 오류가 세션을 죽인다.
 */
public class FirestoreGameLauncher(
    private val store: DocumentStore,
    private val uid: String,
    private val engine: GameEngine,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val timeoutPolicy: TimeoutPolicy = TimeoutPolicy.standard(),
    private val joinPollMillis: Long = 500,
    private val joinGiveUpMillis: Long = 30_000,
    hostLostAfter: Duration = 30.seconds,
    connectionTick: Duration = 5.seconds,
    private val ackTimeout: Duration = 10.seconds,
) : GameLauncher {
    private val transport = FirestoreGameTransport(store, clock, hostLostAfter = hostLostAfter, connectionTick = connectionTick)

    override suspend fun host(plan: GameStartPlan): GameSession {
        // 시드는 서버가 매긴 게임 생성 시각과 섞인다 — 방장이 미리 고를 수 없다(ADR 0016)
        val setup = transport.createGame(plan)
        return HostGameSession(engine, engine.newGame(setup), plan.hostSeats(), transport.host, timeoutPolicy, clock, scope)
    }

    override suspend fun join(room: Room, me: PlayerId): GameSession {
        val gameId = room.gameId ?: throw TransportException("the room has no game yet")
        waitForGame(gameId)
        val info = room.seats.associate {
            PlayerId(it.id) to SeatInfo(it.displayName, if (it.kind == SeatKind.BOT) RuntimeSeatKind.AI else RuntimeSeatKind.REMOTE_HUMAN)
        }
        return RemoteGameSession(gameId, me, transport.guest(uid), info, scope, ackTimeout = ackTimeout)
    }

    override suspend fun resumeHost(room: Room): GameSession? {
        val gameId = room.gameId ?: throw TransportException("the room has no game")
        val game = store.get(FirestoreSchema.game(gameId)) ?: throw TransportException("the game does not exist")
        // 끝났거나 서버가 버려진 판으로 끝낸 게임은 이어 가지 않는다(ADR 0016)
        if (game.string(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING) return null
        val backup = store.get(FirestoreSchema.authority(gameId))?.string(FirestoreSchema.Authority.BACKUP_JSON)
            ?: throw TransportException("no authority backup for $gameId")
        val state = try {
            EngineJson.decodeState(backup)
        } catch (e: SerializationException) {
            throw TransportException("undecodable authority backup", e)
        } catch (e: IllegalArgumentException) {
            throw TransportException("undecodable authority backup", e)
        }
        if (state.gameId != gameId) throw TransportException("the backup belongs to another game")
        val plan = try {
            GameStartPlan.resume(room, gameId, state.seats)
        } catch (e: IllegalArgumentException) {
            throw TransportException("the room no longer matches the game", e)
        }
        // 백업은 게스트에게 게시한 마지막 상태와 같다(같은 배치로 쓴다). 그 뒤 방장 기기에서만 일어난 일은 아무도 보지 못했으므로 잃어도 된다.
        return HostGameSession(engine, state, plan.hostSeats(), transport.host, timeoutPolicy, clock, scope)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private suspend fun waitForGame(gameId: String) {
        val deadline = clock.nowMillis() + joinGiveUpMillis
        while (true) {
            val exists = try {
                store.get(FirestoreSchema.game(gameId)) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                false // 게임 문서가 생기기 전의 읽기는 규칙에 거부된다
            }
            if (exists) return
            if (clock.nowMillis() >= deadline) throw TransportException("the host did not open the game")
            delay(joinPollMillis)
        }
    }
}
