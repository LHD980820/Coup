package io.github.lhd980820.coup.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.theme.Gold

/**
 * 역할의 모습: 대표 색, 카드 바탕(위 -> 아래), 문양, 초상. 색은 보드게임에서 익숙한 배색을 따른다
 * (공작 보라, 암살자 강철빛, 사령관 파랑, 대사 초록, 백작부인 붉은색). 색만으로 구분하지 않도록 문양·이름이 항상 함께 나온다.
 */
@Immutable
data class RoleVisual(
    /** 어두운 바탕 위에서 읽히는 밝은 대표 색(글씨·테두리·문양). */
    val accent: Color,
    val top: Color,
    val bottom: Color,
    val emblem: ImageVector,
    /** 초상 그림. 모르는 역할은 null(문양만 그린다). */
    @param:DrawableRes val portrait: Int?,
)

object RoleVisuals {
    private val entries = mapOf(
        "duke" to RoleVisual(Color(0xFFC3A1FF), Color(0xFF53338F), Color(0xFF1D1138), CoupIcons.Crown, R.drawable.portrait_duke),
        "assassin" to RoleVisual(Color(0xFFC2CAD6), Color(0xFF454C59), Color(0xFF14161A), CoupIcons.Dagger, R.drawable.portrait_assassin),
        "captain" to RoleVisual(Color(0xFF7CBCFF), Color(0xFF225590), Color(0xFF0B1D36), CoupIcons.Anchor, R.drawable.portrait_captain),
        "ambassador" to RoleVisual(Color(0xFF79DBA2), Color(0xFF21704A), Color(0xFF0A2819), CoupIcons.Swap, R.drawable.portrait_ambassador),
        "contessa" to RoleVisual(Color(0xFFFF95A2), Color(0xFF93283B), Color(0xFF360C15), CoupIcons.Shield, R.drawable.portrait_contessa),
    )

    /** 모르는 역할(새 버전의 카드): 금빛 중립 카드. */
    val Unknown: RoleVisual = RoleVisual(Gold, Color(0xFF574420), Color(0xFF1C150B), CoupIcons.Star, null)

    fun of(roleId: String): RoleVisual = entries[roleId] ?: Unknown
}
