package io.github.lhd980820.coup.engine.view

import io.github.lhd980820.coup.engine.model.PlayerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 뷰를 보는 주체. 관전자는 아무의 비공개 정보도 볼 수 없다. */
@Serializable
public sealed interface Viewer {
    @Serializable
    @SerialName("player")
    public data class Player(public val id: PlayerId) : Viewer

    @Serializable
    @SerialName("spectator")
    public data object Spectator : Viewer
}
