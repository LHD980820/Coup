package io.github.lhd980820.coup

import android.content.Context
import android.graphics.drawable.VectorDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.lhd980820.coup.ui.kit.RoleVisuals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 카드 초상(docs/DESIGN.md "카드 초상"): 역할 다섯에 초상이 있고, 모두 이 저장소의 스크립트가 그린 벡터 그림이다.
 * 사진·비트맵이 다시 들어오면(출처를 알 수 없는 그림이 섞이면) 여기서 걸린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardArtTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val roles = listOf("duke", "assassin", "captain", "ambassador", "contessa")

    @Test
    fun `역할마다 초상이 있고 서로 다른 그림이다`() {
        val portraits = roles.map { RoleVisuals.of(it).portrait }
        portraits.forEach { assertNotNull(it) }
        assertEquals(roles.size, portraits.toSet().size)
    }

    @Test
    fun `초상은 모두 벡터 그림이고 가로 세로가 3 대 4다`() {
        roles.forEach { role ->
            val drawable = ContextCompat.getDrawable(context, RoleVisuals.of(role).portrait!!)
            assertTrue("$role 초상이 벡터가 아니다: ${drawable?.javaClass}", drawable is VectorDrawable)
            // 카드가 이 비율의 타원으로 잘라 보인다(PlayingCard 의 PORTRAIT_ASPECT)
            assertEquals("$role 초상의 비율", 3 * drawable!!.intrinsicHeight, 4 * drawable.intrinsicWidth)
        }
    }

    @Test
    fun `초상 파일 이름은 역할 ID를 따른다`() {
        roles.forEach { role ->
            assertEquals("portrait_$role", context.resources.getResourceEntryName(RoleVisuals.of(role).portrait!!))
            assertEquals("drawable", context.resources.getResourceTypeName(RoleVisuals.of(role).portrait!!))
        }
    }

    @Test
    fun `모르는 역할에는 초상이 없다`() {
        assertEquals(null, RoleVisuals.of("future_role").portrait)
    }
}
