package io.github.lhd980820.coup.engine.view

import io.github.lhd980820.coup.engine.model.ChallengeContext
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.ResponseWindow
import io.github.lhd980820.coup.engine.model.RoleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 모두에게 공개 가능한 페이즈 정보. 교환 후보 카드처럼 당사자만 아는 내용은 빠진다. */
@Serializable
public sealed interface PublicPhase {
    @Serializable
    @SerialName("awaiting_action")
    public data class AwaitingAction(public val actor: PlayerId) : PublicPhase

    /** 응답 자격과 통과 여부는 공개 정보다(룰과 공개된 선언만으로 정해진다). */
    @Serializable
    @SerialName("awaiting_responses")
    public data class AwaitingResponses(public val window: ResponseWindow) : PublicPhase

    @Serializable
    @SerialName("awaiting_reveal")
    public data class AwaitingReveal(
        public val challenged: PlayerId,
        public val challenger: PlayerId,
        public val claimedRoles: Set<RoleId>,
        public val context: ChallengeContext,
    ) : PublicPhase

    @Serializable
    @SerialName("awaiting_influence_loss")
    public data class AwaitingInfluenceLoss(public val player: PlayerId, public val reason: LossReason) : PublicPhase

    /** 교환 중. 후보 카드 자체는 당사자만 안다(당사자는 [PlayerView.myDecision]으로 본다). */
    @Serializable
    @SerialName("awaiting_exchange")
    public data class AwaitingExchange(
        public val player: PlayerId,
        public val candidateCount: Int,
        public val keepCount: Int,
    ) : PublicPhase

    @Serializable
    @SerialName("game_over")
    public data class GameOver(public val winner: PlayerId, public val ranking: List<PlayerId>) : PublicPhase
}
