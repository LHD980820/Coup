package io.github.lhd980820.coup.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 모델([AccessPolicy])이 내린 판단을 시간 순서대로 기록한다(JSON Lines). `-Pruletrace=<dir>`로 테스트를 돌리면 테스트마다 한 파일이 생기고,
 * `firebase/rules-test/replay.mjs`가 같은 순서로 **실제 `firestore.rules`를 에뮬레이터에서** 실행해 모델의 허용/거부와 일치하는지 비교한다.
 * 그래서 모든 접근 테스트(수백 개의 허용·거부)가 규칙 파일의 검증 케이스가 된다.
 */
class RuleTrace private constructor(private val file: File) {
    private val seenSinceWrite = mutableSetOf<String>()

    private var started = false

    private fun append(event: JsonObject) = synchronized(this) {
        if (!started) {
            started = true // 첫 판단이 나올 때 테스트 이름을 남긴다(인스턴스는 beforeEach보다 먼저 만들어지므로)
            file.writeText(buildJsonObject { put("t", "meta"); put("test", TraceTestName.current) }.toString() + "\n")
        }
        file.appendText(event.toString() + "\n")
    }

    private fun authJson(auth: Auth?): JsonElement =
        if (auth == null) JsonNull else buildJsonObject { put("uid", auth.uid); auth.email?.let { put("email", it) } }

    private fun opsJson(ops: List<WriteOp>) = buildJsonArray {
        ops.forEach { op ->
            add(
                buildJsonObject {
                    when (op) {
                        is WriteOp.Set -> { put("op", "set"); put("data", op.data) }
                        is WriteOp.Merge -> { put("op", "merge"); put("data", op.fields) }
                        is WriteOp.Delete -> put("op", "delete")
                    }
                    put("path", op.path)
                },
            )
        }
    }

    fun write(auth: Auth?, ops: List<WriteOp>, allowed: Boolean) {
        append(
            buildJsonObject {
                put("t", if (auth == null) "admin" else "write")
                put("auth", authJson(auth))
                put("ops", opsJson(ops))
                put("allowed", allowed)
            },
        )
        if (allowed) seenSinceWrite.clear()
    }

    /** 읽기·조회. 쓰기가 없었던 동안 같은 판단이 반복되면(구독이 변경마다 다시 평가함) 한 번만 남긴다. */
    fun read(auth: Auth, kind: String, path: String, query: JsonObject?, allowed: Boolean) {
        val key = "${auth.uid}|${auth.email}|$kind|$path|$query|$allowed"
        if (!synchronized(this) { seenSinceWrite.add(key) }) return
        append(
            buildJsonObject {
                put("t", kind)
                put("auth", authJson(auth))
                put("path", path)
                if (query != null) put("query", query)
                put("allowed", allowed)
            },
        )
    }

    companion object {
        private var counter = 0

        /** `-Dcoup.rules.trace=<dir>`가 있을 때만. */
        fun create(): RuleTrace? {
            val dir = System.getProperty("coup.rules.trace")?.takeIf { it.isNotBlank() } ?: return null
            File(dir).mkdirs()
            val n = synchronized(Companion) { counter++ }
            return RuleTrace(File(dir, "%04d-%d.jsonl".format(n, ProcessHandle.current().pid())))
        }
    }
}
