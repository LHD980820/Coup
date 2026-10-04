package io.github.lhd980820.coup.engine.model

import kotlinx.serialization.Serializable

@Serializable
public data class TurnInfo(public val number: Int, public val activePlayer: PlayerId)
