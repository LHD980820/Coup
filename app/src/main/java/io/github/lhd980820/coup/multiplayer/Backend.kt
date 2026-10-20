package io.github.lhd980820.coup.multiplayer

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.github.lhd980820.coup.data.FirebaseAuthGateway
import io.github.lhd980820.coup.data.FirebaseDocumentStore
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.rules.RuleSetRegistry
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.lobby.ClientInfo
import io.github.lhd980820.coup.lobby.RoomService
import io.github.lhd980820.coup.remote.FirestoreRoomStore
import io.github.lhd980820.coup.remote.FirestoreUserRepository
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.auth.AuthGateway
import io.github.lhd980820.coup.shell.auth.AuthUser
import io.github.lhd980820.coup.shell.lobby.FirestoreGameLauncher
import io.github.lhd980820.coup.shell.lobby.GameLauncher
import kotlinx.coroutines.CoroutineScope

/** 로그인한 사용자 한 명 몫의 서비스 묶음. 로그아웃하면 버려진다. */
internal class SignedIn(
    val user: AuthUser,
    val account: AccountSession,
    val users: FirestoreUserRepository,
    val rooms: RoomService,
    val registry: RuleSetRegistry,
    val launcher: GameLauncher,
)

/**
 * 화면(ViewModel)이 기대는 서버 쪽 조립. 실제 앱은 Firebase([FirebaseBackend]), 화면 테스트는 규칙을 강제하는 메모리 Firestore로 바꿔 끼운다.
 */
internal interface Backend {
    val auth: AuthGateway

    /** [user]로 로그인한 상태의 서비스를 만든다. 세션과 구독 코루틴은 [scope]에서 돈다. */
    fun open(user: AuthUser, scope: CoroutineScope): SignedIn
}

internal class FirebaseBackend(
    private val appVersion: Int,
    firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : Backend {
    override val auth: AuthGateway = FirebaseAuthGateway(firebaseAuth)

    override fun open(user: AuthUser, scope: CoroutineScope): SignedIn {
        val store = FirebaseDocumentStore(db)
        val registry = BuiltinRules.registry()
        val users = FirestoreUserRepository(store, user.uid, user.email)
        return SignedIn(
            user = user,
            account = AccountSession(users, user.email, DEFAULT_NICKNAME, scope),
            users = users,
            rooms = RoomService(FirestoreRoomStore(store, user.uid), registry, ClientInfo(appVersion, registry), Clock.System),
            registry = registry,
            launcher = FirestoreGameLauncher(store, user.uid, GameEngines.create(), scope),
        )
    }

    private companion object {
        const val DEFAULT_NICKNAME = "player"
    }
}
