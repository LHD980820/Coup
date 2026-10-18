package io.github.lhd980820.coup.data

import com.google.firebase.Timestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JSON <-> Firestore 값 변환(에뮬레이터 없이). 정수는 Long으로, 실수로 바뀌지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirestoreJsonTest {
    @Test
    fun `모든 종류가 왕복한다`() {
        val doc = buildJsonObject {
            put("s", "한글")
            put("i", 42)
            put("neg", -3)
            put("big", 9_000_000_000L)
            put("d", 1.5)
            put("b", true)
            put("n", JsonNull)
            put("list", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive("x")); add(buildJsonObject { put("k", 2) }) })
            put("map", buildJsonObject { put("inner", buildJsonObject { put("deep", false) }); put("empty", JsonObject(emptyMap())) })
            put("emptyList", JsonArray(emptyList()))
        }
        val fs = FirestoreJson.toFirestore(doc)
        assertEquals(42L, fs["i"])
        assertEquals(9_000_000_000L, fs["big"])
        assertEquals(1.5, fs["d"])
        assertEquals(true, fs["b"])
        assertEquals(null, fs["n"])
        assertEquals(doc, FirestoreJson.fromFirestore(fs))
    }

    @Test
    fun `Firestore가 주는 Int와 Timestamp도 읽는다`() {
        val back = FirestoreJson.fromFirestore(mapOf("i" to 7, "t" to Timestamp(12, 345_000_000)))
        assertEquals(JsonPrimitive(7L), back["i"])
        assertEquals(JsonPrimitive(12_345L), back["t"])
    }
}
