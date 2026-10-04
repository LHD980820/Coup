package io.github.lhd980820.coup.engine.event

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public enum class ActionOutcome {
    SUCCESS,
    BLOCKED,
    FAILED,

    /** 효과 적용 시점에 대상이 이미 탈락해 아무 일도 일어나지 않음. */
    FIZZLED,
}

/**
 * 엔진 내부의 완전한 이벤트. 시점별 가시성 필터링(투영)은 10단계에서 추가된다.
 * UI 애니메이션·로그·AI 신념 갱신의 입력이며, 상태 재구성의 근거로 쓰지 않는다.
 */
@Serializable
public sealed interface GameEvent {
    @Serializable
    @SerialName("turn_started")
    public data class TurnStarted(public val player: PlayerId, public val turnNumber: Int) : GameEvent

    @Serializable
    @SerialName("action_declared")
    public data class ActionDeclared(
        public val actor: PlayerId,
        public val actionId: ActionId,
        public val target: PlayerId?,
        public val claimedRoles: Set<RoleId>,
    ) : GameEvent

    @Serializable
    @SerialName("coins_changed")
    public data class CoinsChanged(
        public val player: PlayerId,
        public val delta: Int,
        public val newTotal: Int,
        public val actionId: ActionId?,
    ) : GameEvent

    /** 공개된(잃은) 카드. 공개된 카드는 모두가 볼 수 있으므로 역할을 포함한다. */
    @Serializable
    @SerialName("influence_lost")
    public data class InfluenceLost(
        public val player: PlayerId,
        public val card: Card,
        public val reason: LossReason,
    ) : GameEvent

    @Serializable
    @SerialName("action_resolved")
    public data class ActionResolved(public val actionId: ActionId, public val outcome: ActionOutcome) : GameEvent

    @Serializable
    @SerialName("player_eliminated")
    public data class PlayerEliminated(public val player: PlayerId) : GameEvent

    @Serializable
    @SerialName("game_ended")
    public data class GameEnded(public val winner: PlayerId, public val ranking: List<PlayerId>) : GameEvent
}
