package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

class PermissionDenied(message: String) : TransportException(message)

/**
 * 메모리 속 가짜 Firestore. [storeFor]가 돌려주는 [DocumentStore]는 **그 uid로 로그인한 클라이언트**처럼 동작하며,
 * 모든 읽기·쓰기에 [AccessPolicy]를 적용한다(= 설계 §8.3 보안 규칙의 실행 가능한 모델).
 * 실제 `firebase/firestore.rules`는 이 정책의 손 번역이다 — 둘이 어긋나지 않게 함께 고칠 것.
 */
class FakeFirestore {
    val docs = ConcurrentHashMap<String, JsonObject>()
    private val changes = MutableStateFlow(0L)

    /** 테스트가 규칙을 건너뛰고 직접 문서를 넣거나 읽는 통로(관리자 권한). */
    fun admin(): DocumentStore = Store(uid = null)

    fun storeFor(uid: String): DocumentStore = Store(uid)

    private fun parentOf(path: String) = path.substringBeforeLast('/')

    private inner class Store(val uid: String?) : DocumentStore {
        override suspend fun write(ops: List<WriteOp>) {
            // 전부 검사한 뒤 전부 적용한다(원자성)
            val staged = docs.toMutableMap()
            ops.forEach { op ->
                val exists = staged.containsKey(op.path)
                if (uid != null) {
                    val kind = when {
                        op is WriteOp.Set && !exists -> Access.CREATE
                        else -> Access.UPDATE
                    }
                    val incoming = when (op) {
                        is WriteOp.Set -> op.data
                        is WriteOp.Merge -> JsonObject((staged[op.path] ?: JsonObject(emptyMap())) + op.fields)
                    }
                    AccessPolicy.check(uid, kind, op.path, incoming, staged[op.path]) { staged[it] }
                }
                staged[op.path] = when (op) {
                    is WriteOp.Set -> op.data
                    is WriteOp.Merge -> JsonObject((staged[op.path] ?: JsonObject(emptyMap())) + op.fields)
                }
            }
            docs.clear()
            docs.putAll(staged)
            changes.value = changes.value + 1
        }

        override fun observe(path: String): Flow<JsonObject?> = changes.map {
            if (uid != null) AccessPolicy.check(uid, Access.READ, path, null, docs[path]) { docs[it] }
            docs[path]
        }.distinctUntilChanged()

        override fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>> = changes.map {
            if (uid != null) AccessPolicy.check(uid, Access.QUERY, collectionPath, null, null) { docs[it] }
            docs.filterKeys { parentOf(it) == collectionPath }
                .filterValues { (it[field] as? JsonPrimitive)?.contentOrNull == value }
                .map { (path, data) -> StoredDocument(path.substringAfterLast('/'), data) }
                .sortedBy { it.id }
        }.distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }
    }
}

enum class Access { READ, QUERY, CREATE, UPDATE }

/**
 * 접근 매트릭스(설계 §8.3):
 * - games/{id}: 읽기 = 좌석 uid, 쓰기 = 방장
 * - games/{id}/views/{viewerId}: 읽기 = viewerId == uid (관전 뷰는 좌석 uid), 쓰기 = 방장
 * - games/{id}/authority/state: 읽기·쓰기 = 방장만
 * - games/{id}/commands/{cid}: 생성 = senderUid == uid 이고 방장이 아닌 좌석, 읽기 = 방장 또는 보낸 사람, 수정 = 방장, 컬렉션 조회 = 방장
 * - results/{id}: 생성 = 방장(게임 문서의 hostUid), 읽기 = 로그인한 누구나, 수정 불가
 */
object AccessPolicy {
    fun check(uid: String, kind: Access, path: String, incoming: JsonObject?, existing: JsonObject?, lookup: (String) -> JsonObject?) {
        val segments = path.split('/')
        fun deny(reason: String): Nothing = throw PermissionDenied("$uid cannot $kind $path: $reason")
        fun game(id: String): JsonObject = lookup("games/$id") ?: deny("game document missing")
        fun hostOf(id: String) = (game(id)[FirestoreSchema.Game.HOST_UID] as JsonPrimitive).content
        fun seatsOf(id: String) = (game(id)[FirestoreSchema.Game.SEATS] as JsonArray).map { (it as JsonPrimitive).content }

        when {
            // results/{id}
            segments.size == 2 && segments[0] == "results" -> when (kind) {
                Access.READ -> Unit
                Access.CREATE -> if (uid != hostOf(segments[1])) deny("only the host may write results")
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
            segments.size == 4 && segments[2] == "views" -> {
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
            segments.size == 4 && segments[2] == "authority" ->
                if (uid != hostOf(segments[1])) deny("authority state is host-only")
            // games/{id}/commands (컬렉션 조회)
            segments.size == 3 && segments[2] == "commands" ->
                if (kind != Access.QUERY || uid != hostOf(segments[1])) deny("only the host may list commands")
            // games/{id}/commands/{cid}
            segments.size == 4 && segments[2] == "commands" -> {
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
            else -> deny("unknown path")
        }
    }
}
