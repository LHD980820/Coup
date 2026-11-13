package io.github.lhd980820.coup.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 게임에서 쓰는 그림 기호. 직접 그린 24x24 벡터라 글꼴·그림 파일 없이 어느 크기에서도 선명하고, `Icon(tint = ...)`으로 색을 입힌다.
 * 채운 모양([filled])과 선으로 그린 모양([stroked])이 있다.
 */
object CoupIcons {
    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private fun stroked(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    /** 공작 / 세금. */
    val Crown: ImageVector = filled("crown", "M3.6 18.4h16.8v2.1H3.6z", "M2.5 7.3l4.9 3.7L12 4.2l4.6 6.8 4.9-3.7-1.7 9.6H4.2z")

    /** 암살자 / 암살. */
    val Dagger: ImageVector = filled(
        "dagger",
        "M12 1.4l2.7 11.4H9.3z",
        "M7.2 13.1h9.6a1 1 0 0 1 0 2H7.2a1 1 0 0 1 0-2z",
        "M10.9 15.2h2.2v4.1h-2.2z",
        "M12 19.1a1.75 1.75 0 1 0 0 3.5 1.75 1.75 0 0 0 0-3.5z",
    )

    /** 사령관 / 갈취. */
    val Anchor: ImageVector = stroked(
        "anchor", 2f,
        "M12 2.7a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
        "M12 6.7V21",
        "M8 10.2h8",
        "M4.6 13.6c0 4.1 3.3 7.4 7.4 7.4s7.4-3.3 7.4-7.4",
        "M2.9 15.4l1.7-2.5 2.2 2",
        "M21.1 15.4l-1.7-2.5-2.2 2",
    )

    /** 대사 / 교환. */
    val Swap: ImageVector = stroked("swap", 2.1f, "M4 8.4h14.6", "M15.2 4.9l3.5 3.5-3.5 3.5", "M20 15.6H5.4", "M8.8 12.1l-3.5 3.5 3.5 3.5")

    /** 백작부인 / 막기. */
    val Shield: ImageVector = filled("shield", "M12 2.2l8 3v5.9c0 5-3.3 9-8 10.7-4.7-1.7-8-5.7-8-10.7V5.2z")

    /** 쿠. */
    val Bolt: ImageVector = filled("bolt", "M13.6 1.6L4.6 13.4h5.5L8.9 22.4l10.6-12.8h-6.1z")

    /** 도전. */
    val Swords: ImageVector = stroked(
        "swords", 2f,
        "M4.5 4.5l11 11", "M19.5 4.5l-11 11",
        "M13.2 17.8l4.6-4.6", "M10.8 17.8l-4.6-4.6",
        "M15.8 15.8l3.7 3.7", "M8.2 15.8l-3.7 3.7",
    )

    /** 허용 / 완료. */
    val Check: ImageVector = stroked("check", 2.5f, "M5 12.6l4.4 4.4L19 7.4")

    /** 기권. */
    val Flag: ImageVector = filled("flag", "M5 3h1.8v18H5z", "M7.6 4h11l-2.6 3.8 2.6 3.8h-11z")

    /** 내 차례. */
    val Star: ImageVector = filled("star", "M12 3L14.35 9.26L21.03 9.56L15.8 13.74L17.58 20.19L12 16.5L6.42 20.19L8.2 13.74L2.97 9.56L9.65 9.26Z")

    /** 다른 사람을 기다리는 중. */
    val Hourglass: ImageVector = filled(
        "hourglass",
        "M6 2.5h12v2.1c0 2.9-2.1 5-4.3 7.4 2.2 2.4 4.3 4.5 4.3 7.4v2.1H6v-2.1c0-2.9 2.1-5 4.3-7.4C8.1 9.6 6 7.5 6 4.6z",
    )

    /** 탈락 / 잃은 카드. */
    val Cross: ImageVector = stroked("cross", 2.6f, "M6 6l12 12", "M18 6L6 18")

    /** 규칙 / 튜토리얼. */
    val Scroll: ImageVector = stroked("scroll", 1.9f, "M7 3.5h9.5a1.5 1.5 0 0 1 1.5 1.5v15.5H8.5A1.5 1.5 0 0 1 7 19z", "M10 8h5", "M10 11.8h5", "M10 15.6h3")

    /** 나. */
    val Person: ImageVector = filled("person", "M12 12a4.2 4.2 0 1 0 0-8.4 4.2 4.2 0 0 0 0 8.4z", "M3.8 21c0-4.1 3.7-6.9 8.2-6.9s8.2 2.8 8.2 6.9z")

    /** 멀티플레이. */
    val Group: ImageVector = filled(
        "group",
        "M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        "M16.6 11a2.6 2.6 0 1 0 0-5.2 2.6 2.6 0 0 0 0 5.2z",
        "M2.5 19.5c0-3.1 2.4-5.6 5.5-5.6s5.5 2.5 5.5 5.6z",
        "M15 19.5c0-1.8-.6-3.4-1.6-4.7.9-.6 2-.9 3.2-.9 2.8 0 4.9 2.2 4.9 5v.6z",
    )

    /** 설정. */
    val Sliders: ImageVector = stroked(
        "sliders", 1.9f,
        "M4 7h8.5", "M17.5 7H20", "M15 4.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
        "M4 12h1.5", "M10.5 12H20", "M8 9.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
        "M4 17h10.5", "M19.5 17H20", "M17 14.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
    )
}
