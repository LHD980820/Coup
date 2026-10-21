package io.github.lhd980820.coup.judge

import io.github.lhd980820.coup.remote.FakeFirestore
import io.github.lhd980820.coup.remote.FirestoreSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 테스트용 서버 판정: 결과 문서가 생길 때마다 관리자 권한으로 [ServerJudge]를 돌린다.
 * 실제로는 Cloud Function이 `results/{gameId}` 생성 트리거로 같은 일을 한다(ADR 0015).
 */
fun CoroutineScope.launchJudge(fs: FakeFirestore, judge: ServerJudge = ServerJudge(fs.admin())): Job = launch {
    val judged = mutableSetOf<String>()
    fs.admin().observeOrdered(FirestoreSchema.RESULTS, FirestoreSchema.Result.FINISHED_AT, descending = false, limit = 1_000).collect { results ->
        results.map { it.id }.filter { judged.add(it) }.forEach { judge.judge(it) }
    }
}
