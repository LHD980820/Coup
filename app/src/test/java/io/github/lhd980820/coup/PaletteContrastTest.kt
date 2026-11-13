package io.github.lhd980820.coup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import io.github.lhd980820.coup.ui.theme.ChallengeRed
import io.github.lhd980820.coup.ui.theme.CrimsonLight
import io.github.lhd980820.coup.ui.theme.FeltHigh
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.Panel
import io.github.lhd980820.coup.ui.theme.PanelHigh
import io.github.lhd980820.coup.ui.theme.PanelTop
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.TableGlow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 팔레트의 글씨/바탕 대비(WCAG). 색을 바꿀 때 읽기 어려운 조합이 생기면 여기서 걸린다(docs/DESIGN.md "색과 글꼴").
 * 바탕은 그라데이션의 **밝은 쪽**(대비가 가장 낮은 쪽)으로 잰다.
 */
class PaletteContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun check(name: String, text: Color, background: Color, atLeast: Float) {
        val ratio = contrast(text, background)
        assertTrue("$name: 대비 ${"%.2f".format(ratio)} < $atLeast", ratio >= atLeast)
    }

    private val roles = listOf("duke", "assassin", "captain", "ambassador", "contessa")

    @Test
    fun `본문 글씨와 덜 중요한 글씨는 어느 판 위에서도 7 대 1 이상이다`() {
        listOf("Panel" to Panel, "PanelHigh" to PanelHigh, "PanelTop" to PanelTop, "Felt" to FeltHigh, "Table" to TableGlow).forEach { (name, bg) ->
            check("Parchment / $name", Parchment, bg, 7f)
            check("ParchmentDim / $name", ParchmentDim, bg, 7f)
        }
    }

    @Test
    fun `금색 글씨와 금색 버튼의 글씨, 경고색 글씨는 AA 이상이다`() {
        check("Gold / PanelTop", Gold, PanelTop, 4.5f)
        check("GoldInk / Gold", GoldInk, Gold, 4.5f)
        check("GoldInk / GoldBright", GoldInk, GoldBright, 4.5f)
        check("CrimsonLight / PanelTop", CrimsonLight, PanelTop, 4.5f)
        check("White / ChallengeRed", Color.White, ChallengeRed, 4.5f)
    }

    @Test
    fun `역할 색 글씨는 판 위에서, 본문 글씨는 역할 색으로 채운 칸 위에서 AA 이상이다`() {
        roles.forEach { id ->
            val v = RoleVisuals.of(id)
            // 블러핑 칸·주장 표시: 역할 색 글씨가 판 위에 놓인다
            check("$id accent / PanelTop", v.accent, PanelTop, 4.5f)
            // 들고 있는 역할의 행동·막기: 역할 색으로 채운 칸 위에 본문 글씨가 놓인다
            check("Parchment / $id top", Parchment, v.top, 4.5f)
        }
        check("unknown accent / PanelTop", RoleVisuals.Unknown.accent, PanelTop, 4.5f)
        check("Parchment / unknown top", Parchment, RoleVisuals.Unknown.top, 4.5f)
    }

    @Test
    fun `역할 색은 서로 다르다`() {
        val accents = roles.map { RoleVisuals.of(it).accent }
        assertTrue(accents.toSet().size == roles.size)
        assertTrue(roles.map { RoleVisuals.of(it).emblem.name }.toSet().size == roles.size)
    }
}
