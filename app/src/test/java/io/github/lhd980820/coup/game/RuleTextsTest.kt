package io.github.lhd980820.coup.game

import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.rules.RuleSetEditor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 코어에 등록된 모든 하우스룰·조절 항목에 ko/en 문구가 있어야 한다 — 코어에 새 룰이 생기면 여기서 잡힌다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleTextsTest {
    private val editor = RuleSetEditor(BuiltinRules.registry(), BuiltinRules.classicConfig())

    private fun check() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        fun text(id: Int?, what: String): String {
            assertTrue("번역이 없다: $what", id != null)
            return res.getString(id!!).also {
                assertTrue("비었다: $what", it.isNotBlank())
                assertFalse("자리표시자가 남았다: $it", Regex("%\\d?\\$?[sd]").containsMatchIn(it))
            }
        }
        editor.houseRules.forEach { r ->
            text(RuleTexts.houseRule(r.titleKey), r.titleKey)
            text(RuleTexts.houseRule(r.descriptionKey), r.descriptionKey)
        }
        editor.params.forEach { p ->
            text(RuleTexts.paramTitle(p.key), p.key)
            text(RuleTexts.paramDescription(p.key), p.key + " desc")
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ko")
    fun `한국어 문구가 모두 있다`() = check()

    @Test
    @Config(sdk = [35], qualifiers = "en")
    fun `영어 문구가 모두 있다`() = check()
}
