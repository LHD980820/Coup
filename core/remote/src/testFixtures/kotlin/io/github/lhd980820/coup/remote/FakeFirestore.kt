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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
    private val trace: RuleTrace? = RuleTrace.create()

    /** 모델의 판단을 기록하면서 검사한다([RuleTrace]). 거부는 기록한 뒤 그대로 던진다. */
    private fun checked(auth: Auth, kind: Access, path: String, incoming: JsonObject?, existing: JsonObject?, get: (String) -> JsonObject?, getAfter: (String) -> JsonObject?, query: Pair<String, String>? = null) {
        try {
            AccessPolicy.check(auth, kind, path, incoming, existing, get, getAfter, query)
        } catch (e: PermissionDenied) {
            traceRead(auth, kind, path, query, false)
            throw e
        }
        traceRead(auth, kind, path, query, true)
    }

    private fun traceRead(auth: Auth, kind: Access, path: String, query: Pair<String, String>?, allowed: Boolean) {
        if (kind == Access.READ) trace?.read(auth, "get", path, null, allowed)
        if (kind == Access.QUERY) {
            val q = kotlinx.serialization.json.buildJsonObject {
                if (query != null && query.first.startsWith("orderBy:")) {
                    val (dir, limit) = query.second.split(":")
                    put("orderBy", query.first.removePrefix("orderBy:"))
                    put("dir", dir)
                    put("limit", limit.toInt())
                } else if (query != null) {
                    put("where", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(query.first))
                        add(kotlinx.serialization.json.JsonPrimitive(query.second))
                    })
                }
            }
            trace?.read(auth, "query", path, q, allowed)
        }
    }

    /** 서버 시각(`request.time`). [ServerValues.TIMESTAMP] 표식이 이 값으로 바뀐다. 테스트가 바꿔 끼울 수 있다. */
    @Volatile
    var now: () -> Long = { System.currentTimeMillis() }

    /** 테스트 훅: 트랜잭션이 커밋을 확인하기 직전에 한 번 실행된다(경쟁 상황 재현). 실행 뒤 지워진다. */
    @Volatile
    var beforeNextCommit: (suspend () -> Unit)? = null

    /** 테스트가 규칙을 건너뛰고 직접 문서를 넣거나 읽는 통로(관리자 권한). */
    fun admin(): DocumentStore = Store(auth = null)

    fun storeFor(uid: String, email: String? = null): DocumentStore = Store(Auth(uid, email))

    private fun parentOf(path: String) = path.substringBeforeLast('/')

    private inner class Store(val auth: Auth?) : DocumentStore {
        override suspend fun write(ops: List<WriteOp>) {
            synchronized(lock) { commit(ops) }
        }

        override suspend fun <T> transaction(block: suspend (DocumentTransaction) -> T): T {
            repeat(MAX_TRANSACTION_ATTEMPTS) {
                val reads = mutableMapOf<String, JsonObject?>()
                val ops = mutableListOf<WriteOp>()
                val tx = object : DocumentTransaction {
                    override suspend fun get(path: String): JsonObject? {
                        return synchronized(lock) {
                            if (auth != null) checked(auth, Access.READ, path, null, docs[path], { docs[it] }, { docs[it] })
                            docs[path].also { reads[path] = it }
                        }
                    }

                    override fun write(op: WriteOp) {
                        ops += op
                    }
                }
                val value = block(tx)
                beforeNextCommit?.let { hook ->
                    beforeNextCommit = null
                    hook()
                }
                // 낙관적 동시성: 읽은 문서가 그대로일 때만 커밋, 아니면 처음부터 다시
                val committed = synchronized(lock) {
                    if (reads.all { (path, seen) -> docs[path] == seen }) {
                        if (ops.isNotEmpty()) commit(ops)
                        true
                    } else {
                        false
                    }
                }
                if (committed) return value
            }
            throw TransportException("transaction failed after $MAX_TRANSACTION_ATTEMPTS attempts")
        }

        private fun commit(ops: List<WriteOp>) {
            run {
                val before = docs.toMap()
                val after = before.toMutableMap()
                val requestTime = now()
                ops.forEach { op ->
                    when (op) {
                        is WriteOp.Set -> after[op.path] = resolveServerValues(op.data, requestTime)
                        is WriteOp.Merge -> after[op.path] = JsonObject((after[op.path] ?: JsonObject(emptyMap())) + resolveServerValues(op.fields, requestTime))
                        is WriteOp.Delete -> after.remove(op.path)
                    }
                }
                if (auth != null) {
                    try {
                        ops.map { it.path }.distinct().forEach { path ->
                            val kind = when {
                                !after.containsKey(path) -> if (before.containsKey(path)) Access.DELETE else return@forEach
                                before.containsKey(path) -> Access.UPDATE
                                else -> Access.CREATE
                            }
                            AccessPolicy.check(auth, kind, path, after[path], before[path], { before[it] }, { after[it] }, requestTime = requestTime)
                        }
                    } catch (e: PermissionDenied) {
                        trace?.write(auth, ops, allowed = false)
                        throw e
                    }
                }
                trace?.write(auth, ops, allowed = true)
                // 바뀐 문서만 갱신한다. 전부 지웠다가 다시 채우면 다른 스레드의 구독이 그 틈에 문서가 "없다"고 읽는다.
                ops.map { it.path }.distinct().forEach { path ->
                    val doc = after[path]
                    if (doc != null) docs[path] = doc else docs.remove(path)
                }
                changes.value = changes.value + 1
            }
        }

        // 읽기는 쓰기(배치 한 번)와 겹치지 않게 같은 자물쇠 아래에서 한다 — 구독자는 배치의 중간 상태를 볼 수 없다(실제 Firestore와 같다)
        override fun observe(path: String): Flow<JsonObject?> = changes.map {
            synchronized(lock) {
                if (auth != null) checked(auth, Access.READ, path, null, docs[path], { docs[it] }, { docs[it] })
                docs[path]
            }
        }.distinctUntilChanged()

        override fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>> = changes.map {
            synchronized(lock) {
                if (auth != null) checked(auth, Access.QUERY, collectionPath, null, null, { docs[it] }, { docs[it] }, query = field to value)
                docs.filterKeys { parentOf(it) == collectionPath }
                    .filterValues { (it[field] as? JsonPrimitive)?.contentOrNull == value }
                    .map { (path, data) -> StoredDocument(path.substringAfterLast('/'), data) }
                    .sortedBy { it.id }
            }
        }.distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }

        override fun observeOrdered(collectionPath: String, orderBy: String, descending: Boolean, limit: Int): Flow<List<StoredDocument>> =
            changes.map {
                synchronized(lock) {
                    if (auth != null) checked(auth, Access.QUERY, collectionPath, null, null, { docs[it] }, { docs[it] }, query = "orderBy:$orderBy" to "${if (descending) "desc" else "asc"}:$limit")
                    val matching = docs.filterKeys { parentOf(it) == collectionPath }
                        .mapNotNull { (path, data) -> (data[orderBy] as? JsonPrimitive)?.doubleOrNull?.let { Triple(path.substringAfterLast('/'), data, it) } }
                    val byValue = compareBy<Triple<String, JsonObject, Double>> { it.third }
                    matching.sortedWith((if (descending) byValue.reversed() else byValue).thenBy { it.first })
                        .take(limit)
                        .map { StoredDocument(it.first, it.second) }
                }
            }.distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }
    }
}

private const val MAX_TRANSACTION_ATTEMPTS = 5

/** 서버 시각 표식을 [time]으로 바꾼다(최상위 필드만 — 앱은 최상위에만 쓴다). */
private fun resolveServerValues(data: JsonObject, time: Long): JsonObject =
    JsonObject(data.mapValues { (_, v) -> if (ServerValues.isTimestamp(v)) JsonPrimitive(time) else v })

enum class Access { READ, QUERY, CREATE, UPDATE, DELETE }

/**
 * 접근 매트릭스(설계 §8.3, ADR 0005·0007). `get` = 요청 전 상태, `getAfter` = 배치 적용 후 상태.
 * - games/{id}: 읽기 = 좌석 uid, 쓰기 = 방장
 * - games/{id}/views/{viewerId}: 읽기 = viewerId == uid (관전 뷰는 좌석 uid), 쓰기 = 방장
 * - games/{id}/authority/state: 읽기·쓰기 = 방장만
 * - games/{id}/commands/{cid}: 생성 = senderUid == uid 이고 방장이 아닌 좌석, 읽기 = 방장 또는 보낸 사람, 수정 = 방장, 컬렉션 조회 = 방장
 * - results/{id}: 생성 = 방장(rated면 순위 = 사람 좌석 전원, 변동량 = 레이팅 표), 읽기 = 로그인한 누구나, 수정 불가
 * - users/{uid}: 읽기·조회 = 로그인한 누구나. 생성 = 본인(새 프로필은 시작값, 이관은 옛 문서 값 + 같은 배치의 선점 표시).
 *   수정 = 본인, 닉네임만. 레이팅은 서버 판정(관리자 권한, ADR 0015)만 바꾼다.
 * - users/{uid}/appliedResults/{gid}: 읽기·조회 = 본인. 쓰기 = 서버만(ADR 0015)
 * - pendingRatings/{uid}(옛 클라이언트 정산 대기, ADR 0007): 클라이언트는 읽기·쓰기 불가. 서버 판정이 남은 것을 지운다.
 * - legacyClaims/{email}: 생성 = 토큰 이메일이 같고 uid가 본인. 읽기 = 토큰 이메일이 같을 때. 수정 불가
 * - user/{email}(옛 앱): 읽기 = 토큰 이메일이 같을 때. 쓰기 불가
 * - rooms/{id} (ADR 0008): 읽기 = 로그인한 누구나(목록 조회는 공개 방만). 생성 = 방장 본인만 앉은 새 방. 모든 쓰기에 `updatedAt` = 서버 시각(ADR 0016).
 *   게스트 수정 = 자기 항목만(입장·준비·퇴장), 대기 중에만, 입장은 강퇴되지 않았고 자리가 있을 때.
 *   방장 수정 = 사람은 뺄 수만 있다(넣을 수 없다), 룰셋·봇을 바꾸면 configVersion +1, 시작은 모든 게스트가 지금 설정에 준비했을 때.
 * - joinCodes/{code}: 읽기 = 로그인한 누구나. 생성 = 그 방을 같은 배치에서 만드는 방장. 삭제 = 방이 닫힐 때 방장.
 * - games/{id} 생성: 방이 시작 상태이고 gameId가 같고, 좌석 = 방의 사람 전원, rated = 방 구성(봇·하우스룰 없음), 시작 시각 = 서버 시각.
 * - games/{id} 수정: 방장, 진행 중인 판만, 상태·버전·생존 신호만, "끝남"은 결과를 만드는 같은 배치에서만(판정·버려진 판 처리는 서버만).
 * - results/{id} 생성은 진행 중인 판에서만(서버가 버려진 판으로 끝낸 뒤에는 결과를 쓸 수 없다).
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
    private val GAME_HOST_KEYS = setOf(FirestoreSchema.Game.STATUS, FirestoreSchema.Game.VERSION, FirestoreSchema.Game.HOST_HEARTBEAT_AT)

    fun check(
        auth: Auth,
        kind: Access,
        path: String,
        incoming: JsonObject?,
        existing: JsonObject?,
        get: (String) -> JsonObject?,
        getAfter: (String) -> JsonObject?,
        query: Pair<String, String>? = null,
        requestTime: Long? = null,
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
                    if (game(id).str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING) deny("the game is no longer in progress")
                    val data = incoming ?: deny("no data")
                    if (data.bool(FirestoreSchema.Result.RATED) == true) {
                        if (game(id).bool(FirestoreSchema.Game.RATED) != true) deny("an unrated game cannot have a rated result")
                        val ranking = data.strings(FirestoreSchema.Result.RANKING) ?: deny("ranking missing")
                        val seats = seatsOf(id)
                        if (ranking.toSet() != seats.toSet() || ranking.size != seats.size) deny("rated ranking must be exactly the human seats")
                        if (data.int(FirestoreSchema.Result.PLAYER_COUNT) != ranking.size) deny("playerCount mismatch")
                        // 결과 화면이 보여 주는 변동량도 표와 같아야 한다(실제 반영은 서버가 다시 계산한다)
                        val deltas = data[FirestoreSchema.Result.RATING_DELTAS] as? JsonObject ?: deny("ratingDeltas missing")
                        val table = RATING_TABLE[ranking.size] ?: deny("unsupported player count")
                        if (deltas.keys != ranking.toSet() || ranking.withIndex().any { (i, u) -> deltas.int(u) != table[i] }) {
                            deny("ratingDeltas must follow the rating table")
                        }
                    }
                }
                else -> deny("results are immutable")
            }
            // games/{id}
            segments.size == 2 && segments[0] == "games" -> when (kind) {
                Access.READ -> if (uid !in seatsOf(segments[1])) deny("not a seat")
                Access.CREATE -> {
                    val gameId = segments[1]
                    val data = incoming ?: deny("no data")
                    // 게임 문서를 만드는 사람이 곧 방장이어야 한다
                    if (data.str(FirestoreSchema.Game.HOST_UID) != uid) deny("creator must be the host")
                    // 게임은 방에 묶인다: 좌석 = 방에 스스로 들어온 사람 전원(남을 끼워 넣을 수 없다)
                    val roomId = data.str(FirestoreSchema.Game.ROOM_ID) ?: deny("roomId missing")
                    val room = getAfter("rooms/$roomId") ?: deny("no such room")
                    if (room.str(R.HOST_UID) != uid) deny("not the host of the room")
                    if (room.str(R.STATUS) != "PLAYING" || room.str(R.GAME_ID) != gameId) deny("room has not started this game")
                    val seats = data.strings(FirestoreSchema.Game.SEATS) ?: deny("seats missing")
                    val members = room.strings(R.MEMBER_ORDER) ?: deny("memberOrder missing")
                    if (seats.toSet() != members.toSet() || seats.size != members.size) deny("seats must be exactly the room members")
                    val ruleSet = room[R.RULE_SET] as? JsonObject ?: deny("ruleSet missing")
                    val roomRated = (room[R.BOTS] as? JsonArray)?.isEmpty() == true &&
                        (ruleSet[FirestoreSchema.RuleSet.HOUSE_RULES] as? JsonArray)?.isEmpty() == true &&
                        (ruleSet[FirestoreSchema.RuleSet.PARAM_OVERRIDES] as? JsonObject)?.isEmpty() == true
                    if (data.bool(FirestoreSchema.Game.RATED) != roomRated) deny("rated must match the room setup")
                    if (requestTime == null || data.long(FirestoreSchema.Game.CREATED_AT) != requestTime) deny("createdAt must be the server time")
                    if (data.long(FirestoreSchema.Game.HOST_HEARTBEAT_AT) != requestTime) deny("the heartbeat must be the server time")
                }
                Access.UPDATE -> {
                    val before = existing ?: deny("no game")
                    val after = incoming ?: deny("no data")
                    val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
                    if (uid != hostOf(segments[1])) {
                        checkTakeover(uid, before, after, changed, requestTime, ::deny)
                        return
                    }
                    if (FirestoreSchema.Game.HOST_HEARTBEAT_AT in changed && after.long(FirestoreSchema.Game.HOST_HEARTBEAT_AT) != requestTime) {
                        deny("the heartbeat must be the server time")
                    }
                    if (before.str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING) deny("the game is no longer in progress")
                    if (!GAME_HOST_KEYS.containsAll(changed)) deny("the host may only change ${GAME_HOST_KEYS}, not ${changed - GAME_HOST_KEYS}")
                    when (after.str(FirestoreSchema.Game.STATUS)) {
                        FirestoreSchema.GameStatus.PLAYING -> Unit
                        // 끝남은 결과 문서를 만드는 같은 배치에서만(결과 없이 끝난 척해 버려진 판 정리를 피하지 못하게)
                        FirestoreSchema.GameStatus.FINISHED ->
                            if (get("results/${segments[1]}") != null || getAfter("results/${segments[1]}") == null) deny("finish together with the result")
                        else -> deny("bad game status")
                    }
                }
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
            // games/{id}/authority/setup: 실행 설정(시드). 게임 문서와 같은 배치에서 방장이 만들 때만, 이후 고칠 수 없다(ADR 0015)
            segments.size == 4 && segments[0] == "games" && segments[2] == "authority" && segments[3] == "setup" -> {
                val id = segments[1]
                when (kind) {
                    Access.READ -> if (uid != hostOf(id)) deny("setup is host-only")
                    Access.CREATE -> {
                        if (get("games/$id") != null) deny("setup must be created together with the game")
                        val after = getAfter("games/$id") ?: deny("setup must be created together with the game")
                        if (after.str(FirestoreSchema.Game.HOST_UID) != uid) deny("only the host writes the setup")
                    }
                    else -> deny("setup is immutable")
                }
            }
            // games/{id}/authority/state
            segments.size == 4 && segments[0] == "games" && segments[2] == "authority" ->
                if (uid != hostOf(segments[1])) deny("authority state is host-only")
            // games/{id}/log (컬렉션 조회)
            segments.size == 3 && segments[0] == "games" && segments[2] == "log" ->
                if (kind != Access.QUERY || uid != hostOf(segments[1])) deny("only the host may list the log")
            // games/{id}/log/{version}: 명령 기록. 방장이 만들기만, ID = 버전, 시각 = 서버 시각(ADR 0015)
            segments.size == 4 && segments[0] == "games" && segments[2] == "log" -> {
                val id = segments[1]
                when (kind) {
                    Access.READ -> if (uid != hostOf(id)) deny("the log is host-only")
                    Access.CREATE -> {
                        if (uid != hostOf(id)) deny("only the host writes the log")
                        val data = incoming ?: deny("no data")
                        if (data.str(FirestoreSchema.Log.HOST) != uid) deny("a log entry names the host who wrote it")
                        if (data.long(FirestoreSchema.Log.VERSION)?.toString() != segments[3]) deny("log id must be its version")
                        if (requestTime == null || data.long(FirestoreSchema.Log.AT) != requestTime) deny("log time must be the server time")
                    }
                    else -> deny("the log is immutable")
                }
            }
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
                            else -> deny("these fields cannot be changed (ratings are server-only): $changed")
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
                if (segments[1] != uid) deny("history is private")
                if (kind != Access.READ) deny("applied results are written by the server")
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
            // rooms (목록 조회): 공개 방만
            segments.size == 1 && segments[0] == "rooms" ->
                if (kind != Access.QUERY || query != (R.VISIBILITY to "PUBLIC")) deny("only public rooms can be listed")
            // rooms/{id}
            segments.size == 2 && segments[0] == "rooms" -> when (kind) {
                Access.READ -> Unit
                Access.CREATE -> {
                    checkRoomCreate(uid, segments[1], incoming ?: deny("no data"), getAfter, ::deny)
                    if (requestTime == null || incoming.long(R.UPDATED_AT) != requestTime) deny("updatedAt must be the server time")
                }
                Access.UPDATE -> {
                    val before = existing!!
                    val after = incoming ?: deny("no data")
                    if (requestTime == null || after.long(R.UPDATED_AT) != requestTime) deny("updatedAt must be the server time")
                    if (isRoomTakeover(uid, before, after, getAfter)) {
                        if (after.long(R.VERSION) != before.long(R.VERSION)!! + 1) deny("version must increase by one")
                        if (before.str(R.STATUS) == "CLOSED") deny("closed rooms are frozen")
                        return
                    }
                    checkRoomUpdate(uid, before, after, ::deny)
                }
                else -> deny("rooms cannot be deleted")
            }
            // joinCodes/{code}
            segments.size == 2 && segments[0] == "joinCodes" -> when (kind) {
                Access.READ -> Unit
                Access.CREATE -> {
                    val roomId = incoming?.str(FirestoreSchema.JoinCodeDoc.ROOM_ID) ?: deny("roomId missing")
                    if (get("rooms/$roomId") != null) deny("codes are claimed when the room is created")
                    val room = getAfter("rooms/$roomId") ?: deny("room must be created in the same batch")
                    if (room.str(R.HOST_UID) != uid || room.str(R.JOIN_CODE) != segments[1]) deny("not your room's code")
                }
                Access.DELETE -> {
                    val roomId = existing?.str(FirestoreSchema.JoinCodeDoc.ROOM_ID) ?: deny("roomId missing")
                    val room = getAfter("rooms/$roomId")
                    if (room == null || room.str(R.HOST_UID) != uid || room.str(R.STATUS) != "CLOSED") deny("codes are released when the host closes the room")
                }
                else -> deny("codes are immutable")
            }
            // user/{email} (옛 앱)
            segments.size == 2 && segments[0] == "user" -> {
                if (kind != Access.READ) deny("legacy documents are read-only")
                if (auth.email == null || auth.email != segments[1]) deny("not your email")
            }
            else -> deny("unknown path")
        }
    }

    private val R = FirestoreSchema.Room
    private val ROOM_IMMUTABLE = setOf(R.HOST_UID, R.CREATED_AT, R.MIN_APP_VERSION, R.VISIBILITY, R.JOIN_CODE)
    private val GUEST_KEYS = setOf(R.MEMBERS, R.MEMBER_ORDER, R.VERSION, R.UPDATED_AT)
    private val HOST_KEYS = setOf(
        R.TITLE, R.MAX_PLAYERS, R.RULE_SET, R.BOTS, R.CONFIG_VERSION, R.STATUS, R.GAME_ID, R.BANNED, R.MEMBERS, R.MEMBER_ORDER, R.VERSION, R.UPDATED_AT,
    )
    private const val MAX_SEATS = 6

    /** 방장 이어받기(ADR 0017): 생존 신호가 60초 넘게 끊긴 진행 중인 판을 좌석의 다른 사람이 이어받는다. 방장·생존 신호만 바뀐다. */
    private fun checkTakeover(uid: String, before: JsonObject, after: JsonObject, changed: Set<String>, requestTime: Long?, deny: (String) -> Nothing) {
        if (before.str(FirestoreSchema.Game.STATUS) != FirestoreSchema.GameStatus.PLAYING) deny("only a game in progress can be taken over")
        if (uid !in (before.strings(FirestoreSchema.Game.SEATS) ?: emptyList())) deny("only a seat may take over")
        if (after.str(FirestoreSchema.Game.HOST_UID) != uid) deny("you can only take over for yourself")
        if (!setOf(FirestoreSchema.Game.HOST_UID, FirestoreSchema.Game.HOST_HEARTBEAT_AT).containsAll(changed)) deny("a takeover changes only the host and heartbeat")
        if (requestTime == null || after.long(FirestoreSchema.Game.HOST_HEARTBEAT_AT) != requestTime) deny("the new heartbeat must be the server time")
        val last = before.long(FirestoreSchema.Game.HOST_HEARTBEAT_AT) ?: deny("no heartbeat")
        if (requestTime - last <= HOST_SILENT_MILLIS) deny("the host is still here")
    }

    /** 게임 중인 방의 방장 이어받기: 그 방 게임의 방장(이 배치 뒤)이 나이고, 방장·버전·시각만 바뀐다. */
    private fun isRoomTakeover(uid: String, before: JsonObject, after: JsonObject, getAfter: (String) -> JsonObject?): Boolean {
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        if (before.str(R.STATUS) != "PLAYING" || after.str(R.HOST_UID) != uid) return false
        if (!setOf(R.HOST_UID, R.VERSION, R.UPDATED_AT).containsAll(changed)) return false
        if (uid !in ((before[R.MEMBERS] as? JsonObject)?.keys ?: emptySet())) return false
        val gameId = before.str(R.GAME_ID) ?: return false
        return getAfter("games/$gameId")?.str(FirestoreSchema.Game.HOST_UID) == uid
    }

    /** 규칙의 hostSilent와 같은 값. */
    const val HOST_SILENT_MILLIS: Long = 60_000

    private fun checkRoomCreate(
        uid: String,
        roomId: String,
        d: JsonObject,
        getAfter: (String) -> JsonObject?,
        deny: (String) -> Nothing,
    ) {
        if (d.str(R.HOST_UID) != uid) deny("creator must be the host")
        val members = d[R.MEMBERS] as? JsonObject ?: deny("members missing")
        if (members.keys != setOf(uid) || d.strings(R.MEMBER_ORDER) != listOf(uid)) deny("a new room holds only its host")
        if ((d[R.BOTS] as? JsonArray)?.isEmpty() != true) deny("a new room has no bots")
        if (d.str(R.STATUS) != "WAITING" || d[R.GAME_ID] !is JsonNull) deny("a new room is waiting")
        if ((d[R.BANNED] as? JsonArray)?.isEmpty() != true) deny("a new room bans nobody")
        if (d.long(R.VERSION) != 1L || d.long(R.CONFIG_VERSION) != 0L) deny("a new room starts at version 1")
        if (d.long(R.MAX_PLAYERS) !in 2L..MAX_SEATS.toLong()) deny("maxPlayers out of range")
        val code = d.str(R.JOIN_CODE)
        when (d.str(R.VISIBILITY)) {
            "PUBLIC" -> if (code != null) deny("public rooms have no code")
            "PRIVATE" -> if (code == null || getAfter("joinCodes/$code")?.str(FirestoreSchema.JoinCodeDoc.ROOM_ID) != roomId) deny("claim the join code in the same batch")
            else -> deny("bad visibility")
        }
    }

    private fun checkRoomUpdate(uid: String, before: JsonObject, after: JsonObject, deny: (String) -> Nothing) {
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
        if (before.str(R.STATUS) == "CLOSED") deny("closed rooms are frozen")
        if (after.long(R.VERSION) != before.long(R.VERSION)!! + 1) deny("version must increase by one")
        if (changed.any { it in ROOM_IMMUTABLE }) deny("immutable room fields changed: ${changed intersect ROOM_IMMUTABLE}")
        val mBefore = before[R.MEMBERS] as JsonObject
        val mAfter = after[R.MEMBERS] as? JsonObject ?: deny("members missing")
        val orderBefore = before.strings(R.MEMBER_ORDER)!!
        val orderAfter = after.strings(R.MEMBER_ORDER) ?: deny("memberOrder missing")
        val bots = (after[R.BOTS] as? JsonArray) ?: deny("bots missing")
        val max = after.long(R.MAX_PLAYERS) ?: deny("maxPlayers missing")
        if (max !in 2L..MAX_SEATS.toLong() || orderAfter.size + bots.size > max) deny("room is over capacity")
        if (orderAfter.toSet() != mAfter.keys || orderAfter.size != mAfter.size) deny("memberOrder must list the members")

        if (before.str(R.HOST_UID) == uid) {
            // ---- 방장 ----
            if (!HOST_KEYS.containsAll(changed)) deny("host cannot change $changed")
            if (!mBefore.keys.containsAll(mAfter.keys)) deny("the host cannot add people, only remove them")
            if (mAfter.any { (k, v) -> mBefore[k] != v }) deny("the host cannot edit members")
            if (uid !in mAfter) deny("the host stays in the room (close it instead)")
            val cBefore = before.long(R.CONFIG_VERSION)!!
            val cAfter = after.long(R.CONFIG_VERSION) ?: deny("configVersion missing")
            if (cAfter != cBefore && cAfter != cBefore + 1) deny("configVersion moves by one")
            val configChanged = R.RULE_SET in changed || R.BOTS in changed
            if (configChanged && cAfter != cBefore + 1) deny("changing rules or bots must reset readiness (configVersion + 1)")
            val sBefore = before.str(R.STATUS)
            val sAfter = after.str(R.STATUS)
            if (sBefore != "WAITING" && changed.any { it !in setOf(R.STATUS, R.GAME_ID, R.CONFIG_VERSION, R.VERSION, R.UPDATED_AT) }) deny("only status changes while playing")
            when {
                sAfter == "CLOSED" -> Unit
                sBefore == "WAITING" && sAfter == "WAITING" -> if (R.GAME_ID in changed) deny("gameId is set by starting")
                sBefore == "WAITING" && sAfter == "PLAYING" -> {
                    if (after.str(R.GAME_ID) == null) deny("starting needs a gameId")
                    if (configChanged) deny("cannot change rules while starting")
                    val guests = orderAfter.filter { it != uid }
                    if (guests.any { (mAfter[it] as JsonObject).long(FirestoreSchema.Member.READY_VERSION) != cAfter }) deny("every guest must be ready for the current setup")
                }
                sBefore == "PLAYING" && sAfter == "WAITING" -> if (after[R.GAME_ID] !is JsonNull) deny("ending clears gameId")
                sBefore == "PLAYING" && sAfter == "PLAYING" -> if (R.GAME_ID in changed) deny("cannot swap the game")
                else -> deny("bad status change $sBefore -> $sAfter")
            }
        } else {
            // ---- 게스트: 자기 항목만 ----
            if (!GUEST_KEYS.containsAll(changed)) deny("guests can only change their own seat")
            if (before.str(R.STATUS) != "WAITING") deny("seats are fixed once the game starts")
            val touched = (mBefore.keys + mAfter.keys).filter { mBefore[it] != mAfter[it] }.toSet()
            if (touched != setOf(uid)) deny("guests can only change their own seat")
            val entry = mAfter[uid] as? JsonObject
            val configVersion = before.long(R.CONFIG_VERSION)!!
            when {
                uid !in mBefore -> { // 입장
                    if (uid in (before.strings(R.BANNED) ?: emptyList())) deny("kicked from this room")
                    if (orderAfter != orderBefore + uid) deny("joiners go to the end of memberOrder")
                    val name = entry?.str(FirestoreSchema.Member.NAME) ?: deny("name missing")
                    if (name.length !in 1..20) deny("bad name")
                    if (entry.long(FirestoreSchema.Member.READY_VERSION) != -1L) deny("joiners start not ready")
                    if (entry.long(FirestoreSchema.Member.SEQ) == null || entry.keys != setOf(FirestoreSchema.Member.NAME, FirestoreSchema.Member.READY_VERSION, FirestoreSchema.Member.SEQ)) {
                        deny("bad member entry")
                    }
                }
                entry == null -> { // 퇴장
                    if (orderAfter != orderBefore - uid) deny("leaving removes only yourself")
                }
                else -> { // 준비/해제
                    val old = mBefore[uid] as JsonObject
                    if (orderAfter != orderBefore) deny("memberOrder unchanged")
                    if (entry.keys != old.keys || entry.str(FirestoreSchema.Member.NAME) != old.str(FirestoreSchema.Member.NAME) || entry.long(FirestoreSchema.Member.SEQ) != old.long(FirestoreSchema.Member.SEQ)) {
                        deny("only readiness can change")
                    }
                    if (entry.long(FirestoreSchema.Member.READY_VERSION) !in setOf(-1L, configVersion)) deny("ready only for the current setup")
                }
            }
        }
    }

    private fun validNickname(data: JsonObject): Boolean {
        val name = data.str("nickname") ?: return false
        return name.length in NICK_MIN..NICK_MAX // 규칙의 size()와 같은 UTF-16 단위
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.strings(key: String): List<String>? =
        (this[key] as? JsonArray)?.map { e: JsonElement -> (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null }
}
