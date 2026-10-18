package io.github.lhd980820.coup.data

import com.google.firebase.Timestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON(코어의 문서 표현) <-> Firestore 값. 정수는 Long(규칙의 `int`), 실수는 Double, 맵·리스트는 그대로.
 * 코어는 정수만 쓰므로 정수가 실수로 바뀌는 일이 없게 정수를 먼저 판별한다.
 */
internal object FirestoreJson {
    fun toFirestore(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> value(v) }

    private fun value(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> toFirestore(e)
        is JsonArray -> e.map { value(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> requireNotNull(e.doubleOrNull) { "not a number: $e" }
        }
    }

    fun fromFirestore(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> element(v) })

    private fun element(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v.toLong())
        is Double -> JsonPrimitive(v)
        is Timestamp -> JsonPrimitive(v.seconds * 1_000 + v.nanoseconds / 1_000_000)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to element(x) })
        is List<*> -> JsonArray(v.map { element(it) })
        else -> throw IllegalArgumentException("unsupported Firestore value: ${v::class.java.name}")
    }
}
