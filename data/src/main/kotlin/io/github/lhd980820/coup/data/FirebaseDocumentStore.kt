package io.github.lhd980820.coup.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import io.github.lhd980820.coup.remote.DocumentStore
import io.github.lhd980820.coup.remote.DocumentTransaction
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
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject

/**
 * [DocumentStore]의 Firebase 구현(ADR 0009). 코어(`:remote`)의 전송·방·사용자 저장소가 이 위에서 그대로 돈다.
 *
 * 모델(`FakeFirestore`)과 같은 의미를 지키는 것이 이 클래스의 전부다:
 * - [WriteOp.Merge]는 **필드 단위 교체**(`SetOptions.mergeFieldPaths`)다. Firestore의 기본 merge는 중첩 맵을 깊게 합치지만 모델은 최상위 필드를 통째로 바꾼다.
 * - [get]은 캐시가 아니라 **서버**에서 읽는다(판단에 쓰는 읽기). 구독([observe])은 캐시 값으로 먼저 올 수 있다.
 * - 실패는 모두 [TransportException]으로 바꾼다(규칙 거부 포함). 코어는 이 예외만 안다.
 * - 구독은 **최신 스냅숏만** 남긴다(`CONFLATED`). 스냅숏은 문서/쿼리 전체를 담으므로 중간 것을 건너뛰어도 잃는 것이 없고,
 *   느린 소비자 때문에 리스너 콜백에서 최신 값이 버려지는 일이 없다.
 * - [transaction]의 블록은 Firestore가 경합 시 여러 번 다시 실행한다 — 코어의 블록은 부수 효과가 없게 쓰여 있다.
 *
 * @param db 로그인한 한 사용자의 Firestore 인스턴스(규칙이 그 사용자 기준으로 적용된다)
 */
public class FirebaseDocumentStore(private val db: FirebaseFirestore) : DocumentStore {

    override suspend fun write(ops: List<WriteOp>) {
        guarded {
            val batch = db.batch()
            ops.forEach { op ->
                val ref = db.document(op.path)
                when (op) {
                    is WriteOp.Set -> batch.set(ref, FirestoreJson.toFirestore(op.data))
                    is WriteOp.Merge -> batch.set(ref, FirestoreJson.toFirestore(op.fields), fieldsOf(op.fields))
                    is WriteOp.Delete -> batch.delete(ref)
                }
            }
            batch.commit().await()
        }
    }

    override suspend fun get(path: String): JsonObject? = guarded {
        val snap = db.document(path).get(Source.SERVER).await()
        if (snap.exists()) FirestoreJson.fromFirestore(snap.data.orEmpty()) else null
    }

    override fun observe(path: String): Flow<JsonObject?> = callbackFlow {
        val registration = db.document(path).addSnapshotListener { snap, error ->
            when {
                error != null -> close(error.toTransport())
                snap != null -> trySend(if (snap.exists()) FirestoreJson.fromFirestore(snap.data.orEmpty()) else null)
            }
        }
        awaitClose { registration.remove() }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()

    override fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>> =
        observeQuery(db.collection(collectionPath).whereEqualTo(field, value), sortById = true)

    override fun observeOrdered(collectionPath: String, orderBy: String, descending: Boolean, limit: Int): Flow<List<StoredDocument>> =
        observeQuery(
            db.collection(collectionPath).orderBy(orderBy, if (descending) Query.Direction.DESCENDING else Query.Direction.ASCENDING).limit(limit.toLong()),
            sortById = false, // 서버가 정한 순서(값 -> 문서 ID)를 그대로 쓴다
        )

    /**
     * Firestore 트랜잭션. 경합(ABORTED)은 SDK가 다시 시도한다. 그런데 경합에서 진 쪽의 쓰기는 **옛 읽기로 만든 값**이라
     * 규칙(예: `version == 이전 + 1`)에 걸려 `PERMISSION_DENIED`로 끝날 수 있다(에뮬레이터에서 확인, ADR 0009) —
     * 그 경우도 경합으로 보고 처음부터(새로 읽어서) 다시 시도한다. 진짜 거부라면 [TRANSACTION_ATTEMPTS]번 뒤 그대로 올라간다.
     */
    override suspend fun <T> transaction(block: suspend (DocumentTransaction) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return guarded {
                    db.runTransaction { tx ->
                        // Firestore가 백그라운드 스레드에서 부르고, 경합하면 다시 부른다. tx.get은 동기 호출이다.
                        runBlocking { block(TransactionAdapter(tx)) }
                    }.await()
                }
            } catch (e: FirestoreFailure) {
                val contention = e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                    e.code == FirebaseFirestoreException.Code.ABORTED ||
                    e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                if (!contention || ++attempt >= TRANSACTION_ATTEMPTS) throw e
            }
        }
    }

    private inner class TransactionAdapter(private val tx: Transaction) : DocumentTransaction {
        override suspend fun get(path: String): JsonObject? {
            val snap = tx.get(db.document(path))
            return if (snap.exists()) FirestoreJson.fromFirestore(snap.data.orEmpty()) else null
        }

        override fun write(op: WriteOp) {
            val ref: DocumentReference = db.document(op.path)
            when (op) {
                is WriteOp.Set -> tx.set(ref, FirestoreJson.toFirestore(op.data))
                is WriteOp.Merge -> tx.set(ref, FirestoreJson.toFirestore(op.fields), fieldsOf(op.fields))
                is WriteOp.Delete -> tx.delete(ref)
            }
        }
    }

    private fun observeQuery(query: Query, sortById: Boolean): Flow<List<StoredDocument>> = callbackFlow {
        val registration = query.addSnapshotListener { snap, error ->
            when {
                error != null -> close(error.toTransport())
                snap != null -> {
                    val docs = snap.documents.map { StoredDocument(it.id, FirestoreJson.fromFirestore(it.data.orEmpty())) }
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
    } catch (e: FirebaseFirestoreException) {
        throw e.toTransport()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // 트랜잭션 블록 안에서 던진 예외는 Firestore가 감싸서 돌려준다
        val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is TransportException || it is FirebaseFirestoreException }
        when (cause) {
            is TransportException -> throw cause
            is FirebaseFirestoreException -> throw cause.toTransport()
            else -> throw TransportException("firestore failure: ${e.message}", e)
        }
    }

    private fun FirebaseFirestoreException.toTransport(): TransportException = FirestoreFailure(code, this)

    private companion object {
        const val TRANSACTION_ATTEMPTS = 5
    }
}

/** 규칙 거부·네트워크 오류 등. [code]로 구분할 수 있다(예: `PERMISSION_DENIED`). */
public class FirestoreFailure(public val code: FirebaseFirestoreException.Code, cause: FirebaseFirestoreException) :
    TransportException("firestore ${code.name}: ${cause.message}", cause)
