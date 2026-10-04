package io.github.lhd980820.coup.engine.command

import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 플레이어 입력. [expectedVersion]이 주어졌는데 상태 버전과 다르면 [Rejection.STALE_VERSION]으로 거절된다.
 * 원격 명령은 항상 채우고, 로컬 UI도 중복 탭 방지를 위해 채우는 것을 권장한다.
 */
@Serializable
public sealed interface Command {
    public val actor: PlayerId
    public val expectedVersion: Long?

    @Serializable
    @SerialName("declare_action")
    public data class DeclareAction(
        override val actor: PlayerId,
        public val actionId: ActionId,
        public val target: PlayerId? = null,
        override val expectedVersion: Long? = null,
    ) : Command

    @Serializable
    @SerialName("pass")
    public data class Pass(override val actor: PlayerId, override val expectedVersion: Long? = null) : Command

    @Serializable
    @SerialName("challenge")
    public data class Challenge(override val actor: PlayerId, override val expectedVersion: Long? = null) : Command

    @Serializable
    @SerialName("block")
    public data class Block(
        override val actor: PlayerId,
        public val asRole: RoleId,
        override val expectedVersion: Long? = null,
    ) : Command

    @Serializable
    @SerialName("reveal_card")
    public data class RevealCard(
        override val actor: PlayerId,
        public val cardId: CardId,
        override val expectedVersion: Long? = null,
    ) : Command

    @Serializable
    @SerialName("lose_influence")
    public data class LoseInfluence(
        override val actor: PlayerId,
        public val cardId: CardId,
        override val expectedVersion: Long? = null,
    ) : Command

    @Serializable
    @SerialName("choose_exchange")
    public data class ChooseExchange(
        override val actor: PlayerId,
        public val keep: List<CardId>,
        override val expectedVersion: Long? = null,
    ) : Command
}

public enum class Rejection {
    STALE_VERSION,
    NOT_YOUR_DECISION,
    WRONG_PHASE,
    PLAYER_ELIMINATED,
    UNKNOWN_ACTION,
    INSUFFICIENT_COINS,
    FORCED_ACTION_REQUIRED,
    INVALID_TARGET,
    ROLE_CANNOT_BLOCK,
    CARD_NOT_OWNED,
    CARD_ALREADY_REVEALED,
    INVALID_EXCHANGE_SELECTION,
    GAME_OVER,
}

/** 규칙 위반은 예외가 아닌 [Rejected] 값으로 돌려준다. 거절 시 상태는 바뀌지 않는다. */
public sealed interface ApplyResult {
    public data class Accepted(public val state: GameState, public val events: List<GameEvent>) : ApplyResult

    public data class Rejected(public val reason: Rejection) : ApplyResult
}
