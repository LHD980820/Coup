package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.ENGINE_SCHEMA_VERSION
import io.github.lhd980820.coup.engine.serialization.EngineJson
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.PlayerView
import io.github.lhd980820.coup.engine.view.Viewer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 직렬화 형식 호환성(설계 §12.6, 리스크 R12). `src/test/resources/golden/v{schemaVersion}/`의 파일은
 * "과거 버전이 만든 저장본"이다. 이 테스트가 깨지면 저장 형식이 호환되지 않게 바뀐 것이므로
 * `ENGINE_SCHEMA_VERSION`을 올리고 이전 버전 골든 파일을 유지한 채 마이그레이션을 추가해야 한다.
 *
 * 골든 파일 재생성(형식을 의도적으로 바꿀 때만): `UPDATE_GOLDEN=1 ./gradlew :engine:test --tests '*GoldenFileTest*'`
 */
class GoldenFileTest {
    private val dir = File("src/test/resources/golden/v$ENGINE_SCHEMA_VERSION")
    private val update = System.getenv("UPDATE_GOLDEN") == "1"

    private fun p(n: Int) = PlayerId("p$n")

    /** 도전 -> 증명 -> 카드 교체 -> 상실 대기까지 가는 결정적인 짧은 게임. */
    private fun commands(): List<Command> = listOf(
        Command.DeclareAction(p(1), ActionId("income")),
        Command.DeclareAction(p(2), ActionId("foreign_aid")),
        Command.Pass(p(3)),
        Command.Pass(p(4)),
        Command.Pass(p(1)),
        Command.DeclareAction(p(3), ActionId("tax")),
        Command.Challenge(p(4)),
    )

    private fun build(): Triple<GameState, List<Command>, PlayerView> {
        var state = testEngine.newGame(
            GameSetup("golden-1", List(4) { p(it + 1) }, BuiltinRules.classicConfig(), seed = 2024, firstPlayer = p(1)),
        )
        val applied = mutableListOf<Command>()
        for (command in commands()) {
            val result = testEngine.apply(state, command)
            if (result !is ApplyResult.Accepted) break // 시드에 따라 달라지는 이후 상황은 기록하지 않는다
            state = result.state
            applied += command
        }
        return Triple(state, applied, testEngine.view(state, Viewer.Player(p(1))))
    }

    private fun check(name: String, actual: String) {
        val file = File(dir, name)
        if (update) {
            dir.mkdirs()
            file.writeText(actual + "\n", Charsets.UTF_8)
        }
        check(file.exists()) { "missing golden file ${file.path}; run with UPDATE_GOLDEN=1" }
        assertThat(actual + "\n").isEqualTo(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `게임 상태 저장본은 그대로 복원되고 같은 형식으로 다시 저장된다`() {
        val (state, _, _) = build()
        check("game_state.json", EngineJson.encodeState(state))
        val stored = File(dir, "game_state.json").readText(Charsets.UTF_8).trim()
        assertThat(EngineJson.decodeState(stored)).isEqualTo(state)
        assertThat(EngineJson.encodeState(EngineJson.decodeState(stored))).isEqualTo(stored)
    }

    @Test
    fun `저장된 상태에서 이어서 게임을 진행할 수 있다`() {
        build() // 파일 생성 보장
        val stored = EngineJson.decodeState(File(dir, "game_state.json").readText(Charsets.UTF_8).trim())
        val who = testEngine.pendingDeciders(stored).first()
        val result = testEngine.apply(stored, testEngine.timeoutCommand(stored, who))
        assertThat(result is ApplyResult.Accepted).isEqualTo(true)
    }

    @Test
    fun `명령과 뷰 형식`() {
        val (_, applied, view) = build()
        val serializer = ListSerializer(Command.serializer())
        check("commands.json", EngineJson.json.encodeToString(serializer, applied))
        check("player_view.json", EngineJson.json.encodeToString(PlayerView.serializer(), view))
        val storedCommands = File(dir, "commands.json").readText(Charsets.UTF_8).trim()
        assertThat(EngineJson.json.decodeFromString(serializer, storedCommands)).isEqualTo(applied)
        val storedView = File(dir, "player_view.json").readText(Charsets.UTF_8).trim()
        assertThat(EngineJson.json.decodeFromString(PlayerView.serializer(), storedView)).isEqualTo(view)
    }
}
