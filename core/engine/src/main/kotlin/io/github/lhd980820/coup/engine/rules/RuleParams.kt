package io.github.lhd980820.coup.engine.rules

public data class RuleParams(
    public val startingCoins: Int = 2,
    public val handSize: Int = 2,
    public val minPlayers: Int = 2,
    public val maxPlayers: Int = 6,
    /** 이 코인 수 이상이면 [ActionDefinition.isForcedWhenRich] 행동만 허용된다. */
    public val forcedActionThreshold: Int = 10,
    /** 행동에 대한 도전이 성공해(블러핑 발각) 행동이 취소되면 지불한 비용을 돌려준다. */
    public val refundCostWhenActionChallengeLost: Boolean = true,
    /** 교환 후 돌려놓는 카드를 덱에 넣고 셔플한다. */
    public val exchangeReturnShuffles: Boolean = true,
)
