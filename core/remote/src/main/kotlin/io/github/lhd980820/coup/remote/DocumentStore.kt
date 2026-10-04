package io.github.lhd980820.coup.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** 컬렉션 안의 문서 하나. [id]는 문서 ID(경로의 마지막 조각). */
public class StoredDocument(public val id: String, public val data: JsonObject)

/** 원자적으로 함께 적용되는 쓰기 한 건. */
public sealed interface WriteOp {
    public val path: String

    /** 문서 전체를 [data]로 만든다/덮어쓴다. */
    public class Set(override val path: String, public val data: JsonObject) : WriteOp

    /** 문서의 [fields]만 갱신한다(나머지는 유지, 문서가 없으면 만든다). */
    public class Merge(override val path: String, public val fields: JsonObject) : WriteOp
}

/**
 * Firestore 모양의 문서 저장소 추상화. 이 모듈은 Firebase SDK를 모르고, Android 쪽 어댑터가 이 인터페이스를 구현한다.
 * 문서는 JSON 객체다(Firestore의 맵 <-> [JsonObject] 변환은 어댑터가 맡는다). 실패는 [io.github.lhd980820.coup.runtime.transport.TransportException]으로 던진다.
 *
 * 한 인스턴스는 **한 사용자(인증 컨텍스트)** 로서 동작한다 — 보안 규칙이 그 사용자 기준으로 적용된다.
 */
public interface DocumentStore {
    /** [ops]를 한 번에(전부 성공하거나 전부 실패) 적용한다. */
    public suspend fun write(ops: List<WriteOp>)

    /** 문서를 구독한다. 구독 즉시 현재 값(없으면 null)을 한 번 내보내고, 바뀔 때마다 다시 내보낸다. */
    public fun observe(path: String): Flow<JsonObject?>

    /** [collectionPath] 컬렉션에서 [field] 값이 [value]인 문서들을 구독한다(현재 일치 목록이 바뀔 때마다 전체 목록을 내보낸다). */
    public fun observeWhere(collectionPath: String, field: String, value: String): Flow<List<StoredDocument>>
}
