package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap

class PermissionDenied(message: String) : TransportException(message)

/** 요청한 클라이언트의 인증 정보(= Firestore 규칙의 `request.auth.uid`, `request.auth.token.email`). */
data class Auth(val uid: String, val email: String? = null)

/**
 * 메모리 속 가짜 Firestore. [storeFor]가 돌려주는 [DocumentStore]는 **그 사용자로 로그인한 클라이언트**처럼 동작하며,
 * 모든 읽기·쓰기에 [AccessPolicy]를 적용한다(= 보안 규칙의 실행 가능한 모델).
 * 실제 `firebase/firestore.rules`는 이 정책의 손 번역이다 — 둘이 어긋나지 않게 함께 고칠 것.
 *
 * 배치 쓰기의 규칙 평가는 실제 Firestore와 같다: 각 쓰기를 **배치 전 상태**(`get`)와 **배치 전체가 적용된 뒤 상태**(`getAfter`)로
 * 판단하고, 하나라도 거절되면 아무것도 적용하지 않는다.
 */
class FakeFirestore {
    val docs = ConcurrentHashMap<String, JsonObject>()
    private val changes = MutableStateFlow(0L)
    private val lock = Any()

    /** 테스트가 규칙을 건너뛰고 직접 문서를 넣거나 읽는 통로(관리자 권한). */
    fun admin(): DocumentStore = Store(auth = null)

    fun storeFor(uid: String, email: String? = null): DocumentStore = Store(Auth(uid, email))

    private fun parentOf(path: String) = path.substringBeforeLast('/')

    private inner class Store(val auth: Auth?) : DocumentStore {
        override suspend fun write(ops: List<WriteOp>) {
            synchronized(lock) {
                val before = docs.toMap()
                val after = before.toMutableMap()
                ops.forEach { op ->
                    after[op.path] = when (op) {
                        is WriteOp.Set -> op.data
                        is WriteOp.Merge -> JsonObject((after[op.path] ?: JsonObject(emptyMap())) + op.fields)
                    }
                }
                if (auth != null) {
                    ops.map { it.path }.distinct().forEach { path ->
                        val kind = if (before.containsKey(path)) Access.UPDATE else Access.CREATE
                        AccessPolicy.check(auth, kind, path, after[path], before[path], { before[it] }, { after[it] })
                    }
                }
                docs.clear()
                docs.putAll(after)
                changes.value = changes.value + 1
            }
        }

        override fun observe(path: String): Flow<JsonObject?> = changes.map {
            if (auth != null) AccessPolicy.check(auth, Access.READ, path, null, docs[path], { docs[it] }, { docs[it] })
            docs[path]
        }.distinctUntilChanged()

        override fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>> = changes.map {
            if (auth != null) AccessPolicy.check(auth, Access.QUERY, collectionPath, null, null, { docs[it] }, { docs[it] })
            docs.filterKeys { parentOf(it) == collectionPath }
                .filterValues { (it[field] as? JsonPrimitive)?.contentOrNull == value }
                .map { (path, data) -> StoredDocument(path.substringAfterLast('/'), data) }
                .sortedBy { it.id }
        }.distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }

        override fun observeOrdered(collectionPath: String, orderBy: String, descending: Boolean, limit: Int): Flow<List<StoredDocument>> =
            changes.map {
                if (auth != null) AccessPolicy.check(auth, Access.QUERY, collectionPath, null, null, { docs[it] }, { docs[it] })
                val matching = docs.filterKeys { parentOf(it) == collectionPath }
                    .mapNotNull { (path, data) -> (data[orderBy] as? JsonPrimitive)?.doubleOrNull?.let { Triple(path.substringAfterLast('/'), data, it) } }
                val byValue = compareBy<Triple<String, JsonObject, Double>> { it.third }
                matching.sortedWith((if (descending) byValue.reversed() else byValue).thenBy { it.first })
                    .take(limit)
                    .map { StoredDocument(it.first, it.second) }
            }.distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }
    }
}

enum class Access { READ, QUERY, CREATE, UPDATE }

/**
 * 접근 매트릭스(설계 §8.3, ADR 0005·0007). `get` = 요청 전 상태, `getAfter` = 배치 적용 후 상태.
 * - games/{id}: 읽기 = 좌석 uid, 쓰기 = 방장
 * - games/{id}/views/{viewerId}: 읽기 = viewerId == uid (관전 뷰는 좌석 uid), 쓰기 = 방장
 * - games/{id}/authority/state: 읽기·쓰기 = 방장만
 * - games/{id}/commands/{cid}: 생성 = senderUid == uid 이고 방장이 아닌 좌석, 읽기 = 방장 또는 보낸 사람, 수정 = 방장, 컬렉션 조회 = 방장
 * - results/{id}: 생성 = 방장(rated면 순위 = 사람 좌석 전원), 읽기 = 로그인한 누구나, 수정 불가
 * - users/{uid}: 읽기·조회 = 로그인한 누구나. 생성 = 본인(새 프로필은 시작값, 이관은 옛 문서 값 + 같은 배치의 선점 표시).
 *   수정 = 본인, (a) 닉네임만 또는 (b) 레이팅 반영: 표에서 계산한 변동량 + 표시 문서가 전에는 없고 배치 뒤에는 있음
 * - users/{uid}/appliedResults/{gid}: 읽기·조회 = 본인. 생성 = 본인, 같은 배치에서 사용자 문서가 이 게임을 반영할 때만. 수정 불가
 * - legacyClaims/{email}: 생성 = 토큰 이메일이 같고 uid가 본인. 읽기 = 토큰 이메일이 같을 때. 수정 불가
 * - user/{email}(옛 앱): 읽기 = 토큰 이메일이 같을 때. 쓰기 불가
 */
object AccessPolicy {
    /** 레이팅 표(= TableRatingPolicy). 규칙 파일에도 같은 표가 있다. */
    val RATING_TABLE: Map<Int, List<Int>> = mapOf(
        2 to listOf(30, -20),
        3 to listOf(50, 20, -40),
        4 to listOf(70, 30, -30, -50),
        5 to listOf(100, 60, 30, -40, -70),
        6 to listOf(130, 70, 40, -30, -60, -90),
    )
    const val INITIAL_RATING = 1000
    private const val NICK_MIN = 2
    private const val NICK_MAX = 12
    private val PROFILE_KEYS = setOf("nickname", "rating", "plays", "wins", "createdAt", "migratedFromLegacy")
    private val RATING_KEYS = setOf("rating", "plays", "wins", "lastAppliedResult", "lastAppliedRank")
    private val MARKER_KEYS = setOf("rank", "delta", "playerCount", "finishedAt", "appliedAt")

    fun check(
        auth: Auth,
        kind: Access,
        path: String,
        incoming: JsonObject?,
        existing: JsonObject?,
        get: (String) -> JsonObject?,
        getAfter: (String) -> JsonObject?,
    ) {
        val uid = auth.uid
        val segments = path.split('/')
        fun deny(reason: String): Nothing = throw PermissionDenied("$uid cannot $kind $path: $reason")
        fun game(id: String): JsonObject = get("games/$id") ?: deny("game document missing")
        fun hostOf(id: String) = (game(id)[FirestoreSchema.Game.HOST_UID] as JsonPrimitive).content
        fun seatsOf(id: String) = (game(id)[FirestoreSchema.Game.SEATS] as JsonArray).map { (it as JsonPrimitive).content }

        when {
            // results/{id}
            segments.size == 2 && segments[0] == "results" -> when (kind) {
                Access.READ -> Unit
                Access.CREATE -> {
                    val id = segments[1]
                    if (uid != hostOf(id)) deny("only the host may write results")
                    val data = incoming ?: deny("no data")
                    if (data.bool(FirestoreSchema.Result.RATED) == true) {
                        val ranking = data.strings(FirestoreSchema.Result.RANKING) ?: deny("ranking missing")
                        val seats = seatsOf(id)
                        if (ranking.toSet() != seats.toSet() || ranking.size != seats.size) deny("rated ranking must be exactly the human seats")
                        if (data.int(FirestoreSchema.Result.PLAYER_COUNT) != ranking.size) deny("playerCount mismatch")
                    }
                }
                else -> deny("results are immutable")
            }
            // games/{id}
            segments.size == 2 && segments[0] == "games" -> when (kind) {
                Access.READ -> if (uid !in seatsOf(segments[1])) deny("not a seat")
                Access.CREATE -> {
                    // 게임 문서를 만드는 사람이 곧 방장이어야 한다
                    val host = (incoming?.get(FirestoreSchema.Game.HOST_UID) as? JsonPrimitive)?.content
                    if (host != uid) deny("creator must be the host")
                }
                Access.UPDATE -> if (uid != hostOf(segments[1])) deny("only the host may update the game")
                else -> deny("not allowed")
            }
            // games/{id}/views/{viewer}
            segments.size == 4 && segments[0] == "games" && segments[2] == "views" -> {
                val id = segments[1]
                val viewer = segments[3]
                when (kind) {
                    Access.READ -> {
                        val allowed = if (viewer == FirestoreSchema.SPECTATOR_VIEW) uid in seatsOf(id) else viewer == uid
                        if (!allowed) deny("view belongs to someone else")
                    }
                    Access.CREATE, Access.UPDATE -> if (uid != hostOf(id)) deny("only the host writes views")
                    else -> deny("not allowed")
                }
            }
            // games/{id}/authority/state
            segments.size == 4 && segments[0] == "games" && segments[2] == "authority" ->
                if (uid != hostOf(segments[1])) deny("authority state is host-only")
            // games/{id}/commands (컬렉션 조회)
            segments.size == 3 && segments[0] == "games" && segments[2] == "commands" ->
                if (kind != Access.QUERY || uid != hostOf(segments[1])) deny("only the host may list commands")
            // games/{id}/commands/{cid}
            segments.size == 4 && segments[0] == "games" && segments[2] == "commands" -> {
                val id = segments[1]
                when (kind) {
                    Access.CREATE -> {
                        val sender = (incoming?.get(FirestoreSchema.Command.SENDER_UID) as? JsonPrimitive)?.content
                        if (sender != uid) deny("senderUid must be the caller")
                        if (uid !in seatsOf(id) || uid == hostOf(id)) deny("only guest seats may send commands")
                        val status = (incoming.get(FirestoreSchema.Command.STATUS) as? JsonPrimitive)?.content
                        if (status != FirestoreSchema.CommandStatus.PENDING) deny("new commands must be PENDING")
                    }
                    Access.READ -> {
                        val sender = (existing?.get(FirestoreSchema.Command.SENDER_UID) as? JsonPrimitive)?.content
                        // 아직 없는 문서를 구독하는 것은 보낸 직후 자기 문서일 때만 허용(존재하지 않으면 누구의 것인지 알 수 없다)
                        if (uid != hostOf(id) && existing != null && sender != uid) deny("not your command")
                    }
                    Access.UPDATE -> if (uid != hostOf(id)) deny("only the host may update commands")
                    else -> deny("not allowed")
                }
            }
            // users (컬렉션 조회: 랭킹)
            segments.size == 1 && segments[0] == "users" -> if (kind != Access.QUERY) deny("not allowed")
            // users/{uid}
            segments.size == 2 && segments[0] == "users" -> {
                val owner = segments[1]
                when (kind) {
                    Access.READ -> Unit
                    Access.CREATE -> {
                        if (owner != uid) deny("only the owner creates a profile")
                        val data = incoming ?: deny("no data")
                        if (data.keys != PROFILE_KEYS) deny("unexpected profile fields: ${data.keys}")
                        if (!validNickname(data)) deny("invalid nickname")
                        if (data.int("wins") != 0) deny("wins must start at 0")
                        if (data.bool("migratedFromLegacy") == true) {
                            val email = auth.email ?: deny("no email in token")
                            val legacy = get("user/$email") ?: deny("no legacy document")
                            if (data.int("rating") != legacy.int("rating") || data.int("plays") != (legacy.int("plays") ?: 0)) {
                                deny("migrated values must equal the legacy document")
                            }
                            if (getAfter("legacyClaims/$email")?.str("uid") != uid) deny("legacy claim must be written in the same batch")
                            if (get("legacyClaims/$email") != null) deny("legacy account already claimed")
                        } else {
                            if (data.bool("migratedFromLegacy") != false) deny("migratedFromLegacy must be a boolean")
                            if (data.int("rating") != INITIAL_RATING || data.int("plays") != 0) deny("new profiles start at the initial rating")
                        }
                    }
                    Access.UPDATE -> {
                        if (owner != uid) deny("only the owner updates a profile")
                        val before = existing ?: deny("no profile")
                        val after = incoming ?: deny("no data")
                        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
                        when {
                            changed.isEmpty() -> Unit
                            changed == setOf("nickname") -> if (!validNickname(after)) deny("invalid nickname")
                            RATING_KEYS.containsAll(changed) -> checkRatingUpdate(uid, before, after, get, getAfter, ::deny)
                            else -> deny("these fields cannot be changed: $changed")
                        }
                    }
                    else -> deny("not allowed")
                }
            }
            // users/{uid}/appliedResults (컬렉션 조회: 전적)
            segments.size == 3 && segments[0] == "users" && segments[2] == "appliedResults" ->
                if (kind != Access.QUERY || segments[1] != uid) deny("history is private")
            // users/{uid}/appliedResults/{gameId}
            segments.size == 4 && segments[0] == "users" && segments[2] == "appliedResults" -> {
                val owner = segments[1]
                val gameId = segments[3]
                if (owner != uid) deny("history is private")
                when (kind) {
                    Access.READ -> Unit
                    Access.CREATE -> {
                        val userAfter = getAfter("users/$uid") ?: deny("no profile")
                        if (userAfter.str("lastAppliedResult") != gameId) deny("profile must apply this game in the same batch")
                        if (get("users/$uid")?.str("lastAppliedResult") == gameId) deny("profile already applied this game")
                        val data = incoming ?: deny("no data")
                        if (!MARKER_KEYS.containsAll(data.keys)) deny("unexpected marker fields: ${data.keys}")
                        if (data.int("rank") != userAfter.int("lastAppliedRank")) deny("rank mismatch")
                        val result = get("results/$gameId") ?: deny("no result")
                        if (data.int("delta") != (result[FirestoreSchema.Result.RATING_DELTAS] as? JsonObject)?.int(uid)) deny("delta mismatch")
                    }
                    else -> deny("applied results are immutable")
                }
            }
            // legacyClaims/{email}
            segments.size == 2 && segments[0] == "legacyClaims" -> {
                if (auth.email == null || auth.email != segments[1]) deny("not your email")
                when (kind) {
                    Access.READ -> Unit
                    Access.CREATE -> if (incoming?.str("uid") != uid) deny("claim must name the caller")
                    else -> deny("claims are immutable")
                }
            }
            // user/{email} (옛 앱)
            segments.size == 2 && segments[0] == "user" -> {
                if (kind != Access.READ) deny("legacy documents are read-only")
                if (auth.email == null || auth.email != segments[1]) deny("not your email")
            }
            else -> deny("unknown path")
        }
    }

    /** 레이팅 반영 수정: 변동량은 결과 문서의 순위·인원수로 표에서 다시 계산한다. 표시 문서가 전에는 없고 배치 뒤에는 있어야 한다. */
    private fun checkRatingUpdate(
        uid: String,
        before: JsonObject,
        after: JsonObject,
        get: (String) -> JsonObject?,
        getAfter: (String) -> JsonObject?,
        deny: (String) -> Nothing,
    ) {
        val gameId = after.str("lastAppliedResult") ?: deny("lastAppliedResult missing")
        val rank = after.int("lastAppliedRank") ?: deny("lastAppliedRank missing")
        val result = get("results/$gameId") ?: deny("no such result")
        if (result.bool(FirestoreSchema.Result.RATED) != true) deny("result is not rated")
        val ranking = result.strings(FirestoreSchema.Result.RANKING) ?: deny("ranking missing")
        if (ranking.getOrNull(rank) != uid) deny("rank does not belong to the caller")
        val count = result.int(FirestoreSchema.Result.PLAYER_COUNT) ?: deny("playerCount missing")
        val delta = RATING_TABLE[count]?.getOrNull(rank) ?: deny("unsupported player count")
        if ((result[FirestoreSchema.Result.RATING_DELTAS] as? JsonObject)?.int(uid) != delta) deny("recorded delta disagrees with the table")
        if (after.int("rating") != before.int("rating")!! + delta) deny("rating must change by exactly the delta")
        if (after.int("plays") != before.int("plays")!! + 1) deny("plays must increase by one")
        if (after.int("wins") != (before.int("wins") ?: 0) + if (rank == 0) 1 else 0) deny("wins mismatch")
        if (get("users/$uid/appliedResults/$gameId") != null) deny("result already applied")
        if (getAfter("users/$uid/appliedResults/$gameId") == null) deny("applied marker must be written in the same batch")
    }

    private fun validNickname(data: JsonObject): Boolean {
        val name = data.str("nickname") ?: return false
        val length = name.codePointCount(0, name.length)
        return length in NICK_MIN..NICK_MAX
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.map { e: JsonElement -> (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null }
}
