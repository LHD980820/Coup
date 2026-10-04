package io.github.lhd980820.coup.engine.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `id는 문자열로 직렬화된다`() {
        assertThat(Json.encodeToString(PlayerId.serializer(), PlayerId("p1"))).isEqualTo("\"p1\"")
        assertThat(Json.decodeFromString(CardId.serializer(), "7")).isEqualTo(CardId(7))
    }
}
