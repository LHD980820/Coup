package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.Serializable

@Serializable
public data class PlayerState(
    public val id: PlayerId,
    public val coins: Int,
    public val influences: List<Influence>,
) {
    /** 아직 공개되지 않은 카드들. */
    public val hiddenCards: List<Card> get() = influences.filterNot { it.revealed }.map { it.card }

    /** 이미 공개(상실)된 카드들의 역할. */
    public val revealedRoles: List<RoleId> get() = influences.filter { it.revealed }.map { it.card.role }

    public val isAlive: Boolean get() = influences.any { !it.revealed }
}
