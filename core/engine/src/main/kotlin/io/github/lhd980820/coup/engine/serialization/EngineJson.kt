package io.github.lhd980820.coup.engine.serialization

import io.github.lhd980820.coup.engine.model.GameState
import kotlinx.serialization.json.Json

/** 엔진 상태 스키마 버전. 직렬화 형식이 호환되지 않게 바뀌면 올린다(골든 파일 테스트 동반). */
public const val ENGINE_SCHEMA_VERSION: Int = 1

/** 엔진 객체의 표준 JSON 설정. 권한자의 상태 백업/복원과 원격 전송에 사용한다. */
public object EngineJson {
    public val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    public fun encodeState(state: GameState): String = json.encodeToString(GameState.serializer(), state)

    public fun decodeState(text: String): GameState = json.decodeFromString(GameState.serializer(), text)
}
