package io.github.lhd980820.coup.server

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldPath
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.SetOptions
import com.google.cloud.firestore.Transaction
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.DocumentTransaction
import io.github.lhd980820.coup.remote.ServerValues
import io.github.lhd980820.coup.remote.StoredDocument
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 서버(관리자 권한)용 [DocumentStore]: Google Cloud Firestore 서버 클라이언트 위에 앱의 어댑터(`:data`의 FirebaseDocumentStore)와
 * 같은 의미를 지킨다 — [WriteOp.Merge]는 최상위 필드 통째 교체, 서버 시각 표식은 `serverTimestamp()`, 시각은 밀리초로 읽는다.
 * 관리자 권한이라 **보안 규칙을 거치지 않는다**. 서버 판정(ADR 0015)만 쓴다.
 */
public class AdminDocumentStore(private val db: Firestore) : DocumentStore {

    override suspend fun write(ops: List<WriteOp>) {
        guarded {
            val batch = db.batch()
            ops.forEach { op ->
                val ref = db.document(op.path)
                when (op) {
                    is WriteOp.Set -> batch.set(ref, toFirestore(op.data))
                    is WriteOp.Merge -> batch.set(ref, toFirestore(op.fields), fieldsOf(op.fields))
                    is WriteOp.Delete -> batch.delete(ref)
                }
            }
            batch.commit().await()
        }
    }

    override suspend fun get(path: String): JsonObject? = guarded {
        val snap = db.document(path).get().await()
        if (snap.exists()) fromFirestore(snap.data.orEmpty()) else null
    }

    override fun observe(path: String): Flow<JsonObject?> = callbackFlow {
        val registration = db.document(path).addSnapshotListener { snap, error ->
            when {
                error != null -> close(TransportException("firestore ${error.status}: ${error.message}", error))
                snap != null -> trySend(if (snap.exists()) fromFirestore(snap.data.orEmpty()) else null)
            }
        }
        awaitClose { registration.remove() }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()

    override fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>> =
        observeQuery(db.collection(collectionPath).whereEqualTo(field, value), sortById = true)

    override fun observeOrdered(collectionPath: String, orderBy: String, descending: Boolean, limit: Int): Flow<List<StoredDocument>> =
        observeQuery(
            db.collection(collectionPath).orderBy(orderBy, if (descending) Query.Direction.DESCENDING else Query.Direction.ASCENDING).limit(limit),
            sortById = false,
        )

    /** Firestore 트랜잭션. 경합하면 클라이언트 라이브러리가 [block]을 처음부터 다시 실행한다(코어의 블록은 부수 효과가 없다). */
    override suspend fun <T> transaction(block: suspend (DocumentTransaction) -> T): T = guarded {
        db.runTransaction { tx -> runBlocking { block(TransactionAdapter(tx)) } }.await()
    }

    private inner class TransactionAdapter(private val tx: Transaction) : DocumentTransaction {
        override suspend fun get(path: String): JsonObject? {
            val snap = tx.get(db.document(path)).get()
            return if (snap.exists()) fromFirestore(snap.data.orEmpty()) else null
        }

        override fun write(op: WriteOp) {
            val ref = db.document(op.path)
            when (op) {
                is WriteOp.Set -> tx.set(ref, toFirestore(op.data))
                is WriteOp.Merge -> tx.set(ref, toFirestore(op.fields), fieldsOf(op.fields))
                is WriteOp.Delete -> tx.delete(ref)
            }
        }
    }

    private fun observeQuery(query: Query, sortById: Boolean): Flow<List<StoredDocument>> = callbackFlow {
        val registration = query.addSnapshotListener { snap, error ->
            when {
                error != null -> close(TransportException("firestore ${error.status}: ${error.message}", error))
                snap != null -> {
                    val docs = snap.documents.map { StoredDocument(it.id, fromFirestore(it.data)) }
                    trySend(if (sortById) docs.sortedBy { it.id } else docs)
                }
            }
        }
        awaitClose { registration.remove() }
    }.buffer(Channel.CONFLATED).distinctUntilChanged { a, b -> a.map { it.id to it.data } == b.map { it.id to it.data } }

    /** 최상위 필드만 통째로 바꾼다(점이 들어간 키도 하나의 필드로). */
    private fun fieldsOf(fields: JsonObject): SetOptions = SetOptions.mergeFieldPaths(fields.keys.map { FieldPath.of(it) })

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (e: FirestoreException) {
        throw TransportException("firestore ${e.status}: ${e.message}", e)
    } catch (e: ExecutionException) {
        // 트랜잭션 블록 안에서 던진 예외는 Future가 감싸서 돌려준다
        val cause = e.cause
        if (cause is TransportException) throw cause
        throw TransportException("firestore failure: ${cause?.message ?: e.message}", cause ?: e)
    }

    internal companion object {
        /** JSON(코어의 문서 표현) -> Firestore 값. 정수는 Long, 실수는 Double. 서버 시각 표식은 `serverTimestamp()`. */
        fun toFirestore(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> value(v) }

        private fun value(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> if (ServerValues.isTimestamp(e)) FieldValue.serverTimestamp() else toFirestore(e)
            is JsonArray -> e.map { value(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> requireNotNull(e.doubleOrNull) { "not a number: $e" }
            }
        }

        /** Firestore 값 -> JSON. 정수는 Long, Timestamp는 밀리초. */
        fun fromFirestore(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> element(v) })

        private fun element(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Long -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v.toLong())
            is Double -> JsonPrimitive(v)
            is Timestamp -> JsonPrimitive(v.seconds * 1_000 + v.nanos / 1_000_000)
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to element(x) })
            is List<*> -> JsonArray(v.map { element(it) })
            else -> throw IllegalArgumentException("unsupported Firestore value: ${v::class.java.name}")
        }
    }
}

/** [ApiFuture]를 막지 않고 기다린다. */
private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    ApiFutures.addCallback(
        this,
        object : ApiFutureCallback<T> {
            override fun onSuccess(result: T) = cont.resume(result)
            override fun onFailure(t: Throwable) = cont.resumeWithException(if (t is ExecutionException) t.cause ?: t else t)
        },
        Runnable::run,
    )
    cont.invokeOnCancellation { cancel(false) }
}
