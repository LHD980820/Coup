package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.Serializable

/** 물리 카드 1장. 같은 역할이라도 [id]가 다르면 다른 카드다. */
@Serializable
public data class Card(public val id: CardId, public val role: RoleId)

/** 플레이어가 가진 영향력 1장. [revealed]가 true면 이미 잃은(공개된) 카드다. */
@Serializable
public data class Influence(public val card: Card, public val revealed: Boolean = false)
