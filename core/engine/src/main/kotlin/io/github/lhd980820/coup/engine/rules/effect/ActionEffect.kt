package io.github.lhd980820.coup.engine.rules.effect

import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleParams

/** 효과가 해결되는 시점의 공개 상태 조회 + 행위자/대상. */
public class EffectContext(
    public val actor: PlayerId,
    public val target: PlayerId?,
    public val params: RuleParams,
    private val coins: (PlayerId) -> Int,
    private val influences: (PlayerId) -> Int,
) {
    public fun coinsOf(player: PlayerId): Int = coins(player)

    public fun influenceCountOf(player: PlayerId): Int = influences(player)

    /** 대상이 필요한 행동의 효과에서 호출한다. 대상 없이 호출되면 정의 오류이므로 예외를 던진다. */
    public fun requireTarget(): PlayerId = checkNotNull(target) { "action effect requires a target" }
}

/** 효과가 만들어내는 최소 단위 동작. 엔진 코어가 순서대로 해결한다. */
public sealed interface Primitive {
    public data class GainCoins(public val player: PlayerId, public val amount: Int) : Primitive

    public data class PayCoins(public val player: PlayerId, public val amount: Int) : Primitive

    /** [from]에서 [to]로 최대 [max]코인 이동(보유량이 부족하면 가진 만큼). */
    public data class TransferCoins(public val from: PlayerId, public val to: PlayerId, public val max: Int) : Primitive

    public data class LoseInfluence(public val player: PlayerId) : Primitive

    public data class Exchange(public val player: PlayerId, public val drawCount: Int) : Primitive
}

/** 효과 정의가 사용하는 빌더. 호출한 순서대로 [Primitive]를 기록할 뿐 상태를 직접 바꾸지 않는다. */
public class EffectScope {
    private val recorded = mutableListOf<Primitive>()

    public val primitives: List<Primitive> get() = recorded.toList()

    public fun gainCoins(player: PlayerId, amount: Int) {
        recorded += Primitive.GainCoins(player, amount)
    }

    public fun payCoins(player: PlayerId, amount: Int) {
        recorded += Primitive.PayCoins(player, amount)
    }

    public fun transferCoins(from: PlayerId, to: PlayerId, max: Int) {
        recorded += Primitive.TransferCoins(from, to, max)
    }

    public fun loseInfluence(player: PlayerId) {
        recorded += Primitive.LoseInfluence(player)
    }

    public fun exchange(player: PlayerId, drawCount: Int) {
        recorded += Primitive.Exchange(player, drawCount)
    }
}

public fun interface ActionEffect {
    public fun resolve(scope: EffectScope, ctx: EffectContext)
}

/** [ActionEffect]를 실행해 기록된 동작 목록을 돌려준다. */
public fun ActionEffect.plan(ctx: EffectContext): List<Primitive> =
    EffectScope().also { resolve(it, ctx) }.primitives
