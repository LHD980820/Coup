package io.github.lhd980820.coup.engine.core

import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId

/**
 * 뷰에서 알 수 없는 정보에 대한 "가정". AI가 신념에 따라 샘플링해 [GameEngine.determinize]에 넘긴다.
 * 진짜 비공개 정보가 아니므로 이것으로 만든 상태를 시뮬레이션해도 치팅이 아니다.
 *
 * @property hands 상대별 미공개 카드 역할(장수는 뷰의 hiddenCount와 같아야 한다). 관전자 시점이면 전원.
 * @property deckOrder 덱의 역할 순서(맨 위부터). 내가 교환 중이라 이미 아는 덱 위 카드는 제외한 나머지.
 */
public data class HiddenAssignment(
    public val hands: Map<PlayerId, List<RoleId>>,
    public val deckOrder: List<RoleId>,
)
