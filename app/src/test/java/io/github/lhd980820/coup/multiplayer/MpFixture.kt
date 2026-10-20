package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.game.GameViewModel
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.LobbyUser
import io.github.lhd980820.coup.lobby.RoomResult
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.AuthGateway
import io.github.lhd980820.coup.shell.auth.AuthResult
import io.github.lhd980820.coup.shell.auth.AuthUser
import io.github.lhd980820.coup.shell.lobby.FirestoreGameLauncher
import io.github.lhd980820.coup.shell.lobby.WaitingRoomModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 이메일 -> 비밀번호를 메모리에 두는 가짜 인증. uid는 이메일에서 정해진다. */
internal class FakeAuth(private val accounts: MutableMap<String, String> = mutableMapOf()) : AuthGateway {
    private val _user = MutableStateFlow<AuthUser?>(null)
    override val user: StateFlow<AuthUser?> = _user

    fun addAccount(email: String, password: String) {
        accounts[email] = password
    }

    override suspend fun signIn(email: String, password: String): AuthResult =
        if (accounts[email] == password && password.isNotEmpty()) {
            _user.value = AuthUser(uidOf(email), email)
            AuthResult.Ok
        } else {
            AuthResult.Failed(AuthError.WRONG_CREDENTIALS)
        }

    override suspend fun register(email: String, password: String): AuthResult =
        if (email in accounts) {
            AuthResult.Failed(AuthError.EMAIL_IN_USE)
        } else {
            accounts[email] = password
            _user.value = AuthUser(uidOf(email), email)
            AuthResult.Ok
        }

    override suspend fun sendPasswordReset(email: String): AuthResult = AuthResult.Ok

    /** 받은 구글 토큰들(검증용). 토큰 "google:<이메일>"은 그 이메일의 구글 계정으로 로그인한다. */
    val googleTokens = mutableListOf<String>()

    override suspend fun signInWithGoogle(idToken: String): AuthResult {
        googleTokens += idToken
        val email = idToken.removePrefix("google:").takeIf { idToken.startsWith("google:") } ?: return AuthResult.Failed(AuthError.UNKNOWN)
        _user.value = AuthUser(uidOf(email), email)
        return AuthResult.Ok
    }

    override suspend fun signOut() {
        _user.value = null
    }

    companion object {
        fun uidOf(email: String) = email.substringBefore('@')
    }
}

/** 규칙을 강제하는 메모리 속 Firestore 위에서 앱의 서버 쪽 조립을 흉내 낸다. */
internal class FakeBackend(val fs: FakeFirestore, override val auth: FakeAuth, private val hostLostAfter: kotlin.time.Duration = kotlin.time.Duration.parse("2s")) : Backend {
    private val registry = BuiltinRules.registry()

    /** 로그인한 사용자의 저장소를 감싼다(느린 서버·네트워크 오류 흉내). */
    var wrapStore: (DocumentStore) -> DocumentStore = { it }

    override fun open(user: AuthUser, scope: CoroutineScope): SignedIn {
        val store = wrapStore(fs.storeFor(user.uid, user.email))
        val users = FirestoreUserRepository(store, user.uid, user.email)
        return SignedIn(
            user = user,
            account = AccountSession(users, user.email, "player", scope),
            users = users,
            rooms = RoomService(FirestoreRoomStore(store, user.uid), registry, ClientInfo(10, registry), Clock.System),
            registry = registry,
            launcher = FirestoreGameLauncher(
                store, user.uid, GameEngines.create(), scope, joinPollMillis = 20,
                hostLostAfter = hostLostAfter, connectionTick = kotlin.time.Duration.parse("200ms"),
            ),
        )
    }
}

/**
 * 화면 테스트의 한 세트: 가짜 서버, 화면 모델, 그리고 "다른 기기의 사용자"를 코어 서비스로 조작하는 도구.
 * 게임·세션 코루틴은 메인 루퍼와 무관한 범위에서 돈다(Robolectric의 일시정지 루퍼에 막히지 않게).
 */
internal class MpFixture(
    val fs: FakeFirestore = FakeFirestore(),
    val auth: FakeAuth = FakeAuth(),
    val activeRooms: ActiveRoomStore = InMemoryActiveRoomStore(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val backend = FakeBackend(fs, auth)
    val game = GameViewModel(scope = scope)
    val shell = ShellViewModel(backend, game, scope, activeRooms)

    /** 앱 재시작: 서버와 로그인 상태, 기기에 저장한 방은 그대로이고 화면 모델과 세션은 새로 만든다. 옛 것은 [close]로 끝낸다. */
    fun restart(): MpFixture = MpFixture(fs, auth, activeRooms)
    private val registry = BuiltinRules.registry()

    fun close() = scope.cancel()

    // ---- 다른 기기의 사용자(코어 서비스로 조작) ----------------------------------------------------

    /** 다른 기기. [device]를 취소하면 그 기기의 앱이 꺼진 것과 같다. */
    inner class Remote(val email: String, val nickname: String, val device: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        val uid = FakeAuth.uidOf(email)
        private val store = fs.storeFor(uid, email)
        val lobbyUser = LobbyUser(uid, nickname)
        val rooms = RoomService(FirestoreRoomStore(store, uid), registry, ClientInfo(10, registry), Clock.System)
        val account = AccountSession(FirestoreUserRepository(store, uid, email), email, "player", scope)
        val launcher = FirestoreGameLauncher(store, uid, GameEngines.create(), device, joinPollMillis = 20)

        suspend fun signUp() = account.start()

        fun waitingRoom(roomId: String) = WaitingRoomModel(roomId, lobbyUser, rooms, registry, launcher)

        suspend fun createRoom(title: String = "원격 방", max: Int = 4) =
            (rooms.createRoom(lobbyUser, title, io.github.lhd980820.coup.lobby.Visibility.PUBLIC, max, BuiltinRules.classicConfig()) as RoomResult.Ok).value
    }

    fun remote(email: String, nickname: String, device: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) = Remote(email, nickname, device)
}

/** 읽기를 [gate]가 열릴 때까지 붙잡고, [fail]이면 실패시킨다. */
internal class SlowStore(private val inner: DocumentStore) : DocumentStore by inner {
    @Volatile
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    @Volatile
    var fail = false

    override suspend fun get(path: String): kotlinx.serialization.json.JsonObject? {
        gate?.await()
        if (fail) throw io.github.lhd980820.coup.runtime.transport.TransportException("offline")
        return inner.get(path)
    }
}
