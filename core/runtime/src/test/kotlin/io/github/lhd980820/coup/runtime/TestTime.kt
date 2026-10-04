package io.github.lhd980820.coup.runtime

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * 가상 시간을 [step]씩 앞으로 돌리며 [done]이 참이 될 때까지 진행한다.
 * (`advanceUntilIdle`은 backgroundScope 작업 — 타이머, AI 생각 — 만 남으면 멈추므로 쓰지 않는다.)
 */
fun TestScope.runUntil(limitMillis: Long = 24 * 3_600_000L, step: Long = 500, done: () -> Boolean) {
    runCurrent()
    val start = testScheduler.currentTime
    while (!done()) {
        check(testScheduler.currentTime - start < limitMillis) { "condition not reached within ${limitMillis}ms of virtual time" }
        advanceTimeBy(step)
        runCurrent()
    }
}
