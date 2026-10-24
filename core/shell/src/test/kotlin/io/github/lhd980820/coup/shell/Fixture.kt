package io.github.lhd980820.coup.shell

import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.DocumentTransaction
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.TransportException
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.lobby.FirestoreGameLauncher
import io.github.lhd980820.coup.shell.lobby.GameLauncher
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import io.github.lhd980820.coup.judge.launchJudge
import kotlin.random.Random

/** 규칙을 강제하는 메모리 속 Firestore 위에 사용자별 코어 객체를 만든다(실제 앱과 같은 조립). */
class Fixture(private val scope: CoroutineScope) {
    /** 테스트 본문과 별개로 취소할 수 있는 범위(세션·구독이 끝없이 도는 코루틴을 담는다). */
    val fs = FakeFirestore()
    val registry = BuiltinRules.registry()
    val classic = BuiltinRules.classicConfig()
    private var seed = 0

    fun store(uid: String): DocumentStore = fs.storeFor(uid, "$uid@test.co")
    fun user(uid: String) = LobbyUser(uid, "이름-$uid")

    fun roomService(uid: String, store: DocumentStore = store(uid)) =
        RoomService(FirestoreRoomStore(store, uid), registry, ClientInfo(10, registry), Clock.System, Random(seed++))

    fun users(uid: String, store: DocumentStore = store(uid)) = FirestoreUserRepository(store, uid, "$uid@test.co")

    fun account(uid: String, store: DocumentStore = store(uid)) = AccountSession(users(uid, store), "$uid@test.co", "player", scope)

    /** 서버 판정을 띄운다: 결과가 생기면 검증하고 레이팅을 반영한다(실제로는 Cloud Function, ADR 0015). */
    fun startJudge(judge: io.github.lhd980820.coup.judge.ServerJudge = io.github.lhd980820.coup.judge.ServerJudge(fs.admin())): kotlinx.coroutines.Job =
        scope.launchJudge(fs, judge)

    /** 프로필까지 만든 계정(레이팅 반영에 필요). */
    suspend fun signedUp(uid: String): AccountSession = account(uid).also { it.start() }

    fun launcher(uid: String, store: DocumentStore = store(uid), scope: CoroutineScope = this.scope): GameLauncher =
        FirestoreGameLauncher(store, uid, GameEngines.create(), scope, joinPollMillis = 20, joinGiveUpMillis = 5_000)

    /** 기기 하나의 수명(프로세스). 취소하면 그 기기의 세션·구독이 모두 멈춘다(앱 종료 흉내). */
    fun device(): CoroutineScope = CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))

    fun waitingRoom(roomId: String, uid: String, launcher: GameLauncher = launcher(uid), store: DocumentStore = store(uid), keepAliveMillis: Long = 60_000) =
        WaitingRoomModel(roomId, user(uid), roomService(uid, store), registry, launcher, keepAliveMillis)
}

/** 조건이 될 때까지 기다린다(시간 초과면 실패). */
suspend fun <T> Flow<T>.await(timeoutMillis: Long = 5_000, predicate: (T) -> Boolean): T = withTimeout(timeoutMillis) { first(predicate) }

/** [DocumentStore]를 감싸서 [fail]이 true인 동안 모든 호출을 실패시킨다(네트워크 오류 흉내). */
class FlakyStore(private val inner: DocumentStore) : DocumentStore by inner {
    @Volatile
    var fail = false

    private fun check() {
        if (fail) throw TransportException("offline")
    }

    override suspend fun write(ops: List<WriteOp>) {
        check()
        inner.write(ops)
    }

    override suspend fun get(path: String) = run { check(); inner.get(path) }

    override suspend fun <T> transaction(block: suspend (DocumentTransaction) -> T): T {
        check()
        return inner.transaction(block)
    }

    override fun observeWhere(collectionPath: String, field: String, value: String) = inner.observeWhere(collectionPath, field, value)
}

/**
 * 세션·구독 코루틴을 담을 범위를 만들어 주고, 본문이 끝나면 모두 취소한다.
 * 그 범위에서 처리되지 않은 예외가 나면(예: 방장의 명령 수신 코루틴이 조용히 죽는 경우) 테스트를 실패시킨다.
 */
fun fixture(timeoutMillis: Long = 30_000, body: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
    val uncaught = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
    try {
        withTimeout(timeoutMillis) {
            coroutineScope {
                body(Fixture(scope))
                coroutineContext.cancelChildren() // 본문이 띄운 구독·화면 코루틴은 끝없이 돈다
            }
        }
    } finally {
        scope.cancel()
    }
    uncaught.firstOrNull()?.let { throw AssertionError("범위 안에서 처리되지 않은 예외: $it", it) }
}

/** 구독이 [breakNext]일 때 한 번 오류로 끊기게 한다(네트워크 오류 흉내). 읽기·쓰기는 그대로. */
class FlakyObserveStore(private val inner: DocumentStore) : DocumentStore by inner {
    @Volatile
    var breakNext = false

    private fun <T> guard(flow: kotlinx.coroutines.flow.Flow<T>): kotlinx.coroutines.flow.Flow<T> = flow.map {
        if (breakNext) {
            breakNext = false
            throw TransportException("listener failed")
        }
        it
    }

    override fun observe(path: String) = guard(inner.observe(path))
    override fun observeWhere(collectionPath: String, field: String, value: String) = guard(inner.observeWhere(collectionPath, field, value))
    override fun observeOrdered(collectionPath: String, orderBy: String, descending: Boolean, limit: Int) =
        guard(inner.observeOrdered(collectionPath, orderBy, descending, limit))
}
