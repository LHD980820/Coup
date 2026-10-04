package io.github.lhd980820.coup.engine.rules

import io.github.lhd980820.coup.engine.model.PlayerId

/** 대상 필터가 볼 수 있는 대상의 공개 정보. 비공개 정보는 포함하지 않는다. */
public data class TargetContext(public val id: PlayerId, public val coins: Int, public val influenceCount: Int)

public fun interface TargetFilter {
    public fun accepts(target: TargetContext): Boolean
}

public sealed interface Targeting {
    public data object None : Targeting

    /** 자신을 제외한 생존자 중 한 명. [filter]가 있으면 통과한 대상만 지정 가능. */
    public data class OtherAlivePlayer(public val filter: TargetFilter? = null) : Targeting
}

public enum class BlockPolicy {
    /** 막을 수 없다. */
    NONE,

    /** 대상으로 지정된 플레이어만 막을 수 있다. */
    TARGET_ONLY,

    /** 행위자를 제외한 모든 생존자가 막을 수 있다. */
    ANY_OTHER_PLAYER,
}
