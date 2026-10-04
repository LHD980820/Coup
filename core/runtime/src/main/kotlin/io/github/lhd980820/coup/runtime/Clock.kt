package io.github.lhd980820.coup.runtime

/** 현재 시각(epoch millis). 테스트에서는 코루틴 가상 시간을 주입한다. */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
