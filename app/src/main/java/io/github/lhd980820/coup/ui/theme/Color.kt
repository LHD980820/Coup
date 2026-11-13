package io.github.lhd980820.coup.ui.theme

import androidx.compose.ui.graphics.Color

// 시각 방향(docs/DESIGN.md): 촛불 아래의 어두운 호두나무 테이블, 황동·금 장식, 양피지 글씨.
// 다이내믹 컬러는 쓰지 않고(설계 §10.1) 밝은 테마도 두지 않는다 — 게임은 한 가지 모습이다.

/** 테이블: 화면 가장자리(가장 어둡다) -> 가운데의 촛불빛. */
val TableEdge = Color(0xFF0D0A08)
val TableBase = Color(0xFF16110D)
val TableGlow = Color(0xFF35271A)

/** 패널(자리·시트·대화상자): 테이블 위에 얹힌 판. */
val PanelLow = Color(0xFF1C1611)
val Panel = Color(0xFF241C15)
val PanelHigh = Color(0xFF30261C)
val PanelTop = Color(0xFF3B2F23)

/** 가운데 무대(지금 벌어지는 일): 짙은 붉은 펠트. */
val FeltHigh = Color(0xFF4A1A20)
val FeltLow = Color(0xFF240C10)

val Gold = Color(0xFFE6B855)
val GoldBright = Color(0xFFFFE39A)
val GoldDeep = Color(0xFF8F6A1E)
val GoldInk = Color(0xFF2A1D00)

/** 글씨: 본문 / 덜 중요한 글씨. 둘 다 패널 위에서 대비 7:1 이상이다. */
val Parchment = Color(0xFFF5ECDD)
val ParchmentDim = Color(0xFFCDBFA9)

val Crimson = Color(0xFFE5484D)
val CrimsonLight = Color(0xFFFFB3AE)
val CrimsonDeep = Color(0xFF7A1B22)

/** 자리(아바타) 색. 역할 색과 헷갈리지 않게 채도를 낮춘 흙빛 계열이다. */
val SeatTints = listOf(
    Color(0xFFC98F5A),
    Color(0xFF7FB3A6),
    Color(0xFFB98BB0),
    Color(0xFFA3AD6B),
    Color(0xFF8C9BD0),
)
