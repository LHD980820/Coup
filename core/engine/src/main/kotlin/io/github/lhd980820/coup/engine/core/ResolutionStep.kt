package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.engine.rules.effect.Primitive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 해결 스택의 한 단계(설계 문서 §4.5). 스택의 맨 앞(index 0)이 다음에 실행될 단계다.
 * 엔진 내부 전용이며 뷰에 노출되지 않는다. 직렬화되는 이유는 권한자가 상태를 백업/복원하기 때문.
 */
@Serializable
internal sealed interface ResolutionStep {
    /** 현재 행동([io.github.lhd980820.coup.engine.model.PendingAction])에 대한 응답 창을 연다. 응답할 사람이 없으면 건너뛴다. */
    @Serializable
    @SerialName("open_response_window")
    data class OpenResponseWindow(val kind: WindowKind) : ResolutionStep

    /** 미공개 0장: 건너뜀 / 1장: 자동 상실 / 2장 이상: 선택 페이즈. */
    @Serializable
    @SerialName("require_influence_loss")
    data class RequireInfluenceLoss(val player: PlayerId, val reason: LossReason) : ResolutionStep

    /** 증명에 쓰인 카드를 덱에 넣고 셔플한 뒤 1장 드로우. */
    @Serializable
    @SerialName("replace_proven_card")
    data class ReplaceProvenCard(val player: PlayerId, val cardId: CardId) : ResolutionStep

    /** 행동 도전이 실패한 뒤: 막기 가능하고 아직 안 막혔으면 막기 전용 창, 아니면 효과 적용. */
    @Serializable
    @SerialName("continue_after_action_challenge_failed")
    data object ContinueAfterActionChallengeFailed : ResolutionStep

    /** 현재 행동의 효과를 [Effect] 단계들로 펼친다. 대상이 탈락했으면 무효(fizzle). */
    @Serializable
    @SerialName("apply_effect")
    data object ApplyEffect : ResolutionStep

    @Serializable
    @SerialName("effect")
    data class Effect(val primitive: Primitive) : ResolutionStep

    /** 행동 도전 패배 시 비용 환불(룰 파라미터에 따름). */
    @Serializable
    @SerialName("refund_cost")
    data object RefundCost : ResolutionStep

    /** 다음 생존 좌석으로 턴을 넘긴다. */
    @Serializable
    @SerialName("end_turn")
    data object EndTurn : ResolutionStep
}
