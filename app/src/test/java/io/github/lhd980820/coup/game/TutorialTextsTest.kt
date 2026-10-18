package io.github.lhd980820.coup.game

import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 코어 튜토리얼이 쓰는 모든 문구 키가 앱에 번역(ko/en)으로 있어야 한다 — 코어에 키가 늘면 여기서 바로 잡힌다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TutorialTextsTest {
    private val keys = TutorialLessons.all.flatMap { l -> listOf(l.titleKey, l.completionKey) + l.steps.map { it.messageKey } }

    private fun check() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        keys.forEach { key ->
            assertTrue("번역이 없다: $key", TutorialTexts.has(key))
            val text = res.getString(TutorialTexts.res(key)!!)
            assertTrue("비었다: $key", text.isNotBlank())
            assertFalse("자리표시자가 남았다: $text", Regex("%\\d?\\$?[sd]").containsMatchIn(text))
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ko")
    fun `한국어 문구가 모두 있다`() = check()

    @Test
    @Config(sdk = [35], qualifiers = "en")
    fun `영어 문구가 모두 있다`() = check()

    @Test
    fun `과정 ID와 키는 서로 겹치지 않는다`() {
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(TutorialLessons.all.size, TutorialLessons.all.map { it.id }.toSet().size)
    }
}
