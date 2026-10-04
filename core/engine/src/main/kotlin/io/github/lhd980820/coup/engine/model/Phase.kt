package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public enum class WindowKind {
    /** 행동 선언 직후: 도전 또는 막기 가능. */
    ACTION,

    /** 행동에 대한 도전이 실패한 뒤: 막기만 가능. */
    BLOCK_ONLY,

    /** 막기 선언 직후: 그 막기에 대한 도전만 가능. */
    BLOCK_CHALLENGE,
}

public enum class ChallengeContext { ACTION, BLOCK }

@Serializable
public data class AllowedResponses(
    public val canChallenge: Boolean,
    public val blockRoles: Set<RoleId>,
)

/** 응답 창. [eligible] 전원이 Pass하면 닫히고, 첫 도전/막기가 수락되면 즉시 닫힌다. */
@Serializable
public data class ResponseWindow(
    public val kind: WindowKind,
    public val eligible: Set<PlayerId>,
    public val passed: Set<PlayerId>,
    public val allowed: Map<PlayerId, AllowedResponses>,
) {
    public val waitingOn: Set<PlayerId> get() = eligible - passed
}

@Serializable
public sealed interface LossReason {
    /** 도전했다가 상대가 역할을 증명해 진 경우. */
    @Serializable
    @SerialName("challenge_lost")
    public data object ChallengeLost : LossReason

    /** 도전받고 주장 역할이 아닌 카드를 공개해(블러핑 발각) 그 카드를 잃는 경우. */
    @Serializable
    @SerialName("bluff_exposed")
    public data object BluffExposed : LossReason

    /** 기권으로 남은 카드를 모두 잃는 경우. */
    @Serializable
    @SerialName("concede")
    public data object Concede : LossReason

    /** 행동 효과(쿠, 암살 등)로 잃는 경우. */
    @Serializable
    @SerialName("action_effect")
    public data class ActionEffect(public val actionId: ActionId) : LossReason
}

/** 엔진이 지금 누구의 어떤 입력을 기다리는지. */
@Serializable
public sealed interface Phase {
    @Serializable
    @SerialName("awaiting_action")
    public data class AwaitingAction(public val actor: PlayerId) : Phase

    @Serializable
    @SerialName("awaiting_responses")
    public data class AwaitingResponses(public val window: ResponseWindow) : Phase

    /** [challenged]가 공개할 카드를 고른다. [claimedRoles] 중 하나를 공개하면 증명 성공. */
    @Serializable
    @SerialName("awaiting_reveal")
    public data class AwaitingReveal(
        public val challenged: PlayerId,
        public val challenger: PlayerId,
        public val claimedRoles: Set<RoleId>,
        public val context: ChallengeContext,
    ) : Phase

    /** 미공개 카드가 2장 이상일 때 잃을 카드를 고른다(1장이면 자동 처리되어 이 페이즈에 오지 않는다). */
    @Serializable
    @SerialName("awaiting_influence_loss")
    public data class AwaitingInfluenceLoss(public val player: PlayerId, public val reason: LossReason) : Phase

    /** 교환 후보(손패 + 드로우) 중 [keepCount]장을 남긴다. 후보는 당사자에게만 공개된다. */
    @Serializable
    @SerialName("awaiting_exchange")
    public data class AwaitingExchange(
        public val player: PlayerId,
        public val candidates: List<Card>,
        public val keepCount: Int,
    ) : Phase

    @Serializable
    @SerialName("game_over")
    public data class GameOver(public val winner: PlayerId, public val ranking: List<PlayerId>) : Phase
}
