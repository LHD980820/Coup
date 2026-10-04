package io.github.lhd980820.coup.ai

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.Viewer
import org.junit.jupiter.api.Test
import java.io.File

class AiBoundaryTest {
    private val engine = GameEngines.create()

    /** 설계 §7.2 정적 검사: AI 소스는 진짜 게임 상태 타입을 참조하지 않는다. */
    @Test
    fun `AI 소스는 GameState를 참조하지 않는다`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> codeOnly(file.readText()).contains(Regex("""\bGameState\b""")) }
            .map { it.path }
            .toList()
        assertThat(offenders).isEmpty()
    }

    /** 주석(블록·줄)을 제거한 코드. 설명 주석에서 GameState를 언급하는 것은 허용한다. */
    private fun codeOnly(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `정적 검사기 자체 검증 - 코드의 GameState 참조는 잡고 주석은 무시한다`() {
        assertThat(codeOnly("import x.GameState\n// GameState").contains("GameState")).isEqualTo(true)
        assertThat(codeOnly("/** GameState */\nval a = 1 // GameState").contains("GameState")).isEqualTo(false)
    }

    @Test
    fun `무작위 에이전트의 결정은 항상 엔진에 수락된다`() {
        var decisions = 0
        repeat(200) { game ->
            val seats = List(2 + game % 5) { PlayerId("p$it") }
            val agents = seats.associateWith { RandomAgent(seed = game * 31L + it.value.hashCode()) }
            var state = engine.newGame(GameSetup("g$game", seats, BuiltinRules.classicConfig(), seed = game.toLong()))
            var steps = 0
            while (!state.isOver) {
                check(++steps < 3000)
                val who = engine.pendingDeciders(state).first()
                val view = engine.view(state, Viewer.Player(who))
                val command = agents.getValue(who).decide(view, checkNotNull(view.myDecision))
                val result = engine.apply(state, command)
                check(result is ApplyResult.Accepted) { "agent produced a rejected command $command: $result" }
                state = result.state
                decisions++
            }
        }
        assertThat(decisions > 10_000).isEqualTo(true)
    }
}
