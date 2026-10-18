package io.github.lhd980820.coup.presentation.rules

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.RandomAgent
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.Viewer
import org.junit.jupiter.api.Test

class RuleSetEditorTest {
    private val registry = BuiltinRules.registry()
    private val editor = RuleSetEditor(registry, BuiltinRules.classicConfig())
    private val coins = editor.params.filterIsInstance<ParamSpec.IntParam>().first { it.key == "startingCoins" }
    private val threshold = editor.params.filterIsInstance<ParamSpec.IntParam>().first { it.key == "forcedActionThreshold" }
    private val refund = editor.params.filterIsInstance<ParamSpec.BoolParam>().first { it.key == "refundCostWhenActionChallengeLost" }
    private val shuffle = editor.params.filterIsInstance<ParamSpec.BoolParam>().first { it.key == "exchangeReturnShuffles" }

    @Test
    fun `하우스룰과 조절 항목은 등록된 룰셋에서 온다`() {
        assertThat(editor.houseRules.map { it.id }).containsExactly("no_steal_from_broke", "last_stand")
        // 기본값은 기반 룰셋에서 읽는다(표준 규칙: 시작 코인 2, 쿠 강제 10)
        assertThat(coins.default).isEqualTo(2)
        assertThat(threshold.default).isEqualTo(10)
        assertThat(refund.default).isTrue()
        assertThat(shuffle.default).isTrue()
    }

    @Test
    fun `아무것도 바꾸지 않으면 표준 규칙이고 레이팅 대상이다`() {
        val s = editor.standard
        assertThat(s.isStandard).isTrue()
        assertThat(editor.config(s)).isEqualTo(BuiltinRules.classicConfig())
        assertThat(editor.isRated(s)).isTrue()
        assertThat(editor.issues(s)).isEmpty()
        assertThat(editor.playerRange(s)).isEqualTo(2..6)
    }

    @Test
    fun `하우스룰을 켜고 끄면 설정과 레이팅 여부가 따라간다`() {
        var s = editor.toggleHouseRule(editor.standard, "last_stand")
        assertThat(editor.config(s).houseRules).isEqualTo(setOf("last_stand"))
        assertThat(editor.isRated(s)).isFalse()
        assertThat(s.changeCount).isEqualTo(1)
        s = editor.toggleHouseRule(s, "last_stand")
        assertThat(s).isEqualTo(editor.standard)
        assertThat(editor.isRated(s)).isTrue()
        // 명시적으로 켜기/끄기도 멱등
        assertThat(editor.toggleHouseRule(editor.toggleHouseRule(editor.standard, "last_stand", on = true), "last_stand", on = true).houseRules)
            .isEqualTo(setOf("last_stand"))
        assertThat(editor.toggleHouseRule(editor.standard, "last_stand", on = false)).isEqualTo(editor.standard)
    }

    @Test
    fun `모르는 하우스룰은 거절한다`() {
        assertFailure { editor.toggleHouseRule(editor.standard, "nope") }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `정수 항목은 범위로 맞추고 기본값이면 override를 뺀다`() {
        var s = editor.setInt(editor.standard, coins, 4)
        assertThat(s.params).isEqualTo(mapOf("startingCoins" to "4"))
        assertThat(editor.intValue(s, coins)).isEqualTo(4)
        assertThat(editor.setInt(s, coins, 99).params["startingCoins"]).isEqualTo("7")
        assertThat(editor.setInt(s, coins, -5).params["startingCoins"]).isEqualTo("1")
        s = editor.setInt(s, coins, 2) // 기본값으로 되돌림
        assertThat(s).isEqualTo(editor.standard)
        assertThat(editor.isRated(s)).isTrue()
        assertThat(editor.intValue(editor.standard, threshold)).isEqualTo(10)
    }

    @Test
    fun `불리언 항목도 기본값이면 override를 뺀다`() {
        var s = editor.setBool(editor.standard, refund, false)
        assertThat(s.params).isEqualTo(mapOf("refundCostWhenActionChallengeLost" to "false"))
        assertThat(editor.boolValue(s, refund)).isFalse()
        assertThat(editor.boolValue(s, shuffle)).isTrue()
        s = editor.setBool(s, refund, true)
        assertThat(s).isEqualTo(editor.standard)
    }

    @Test
    fun `저장된 설정을 복원하고 모르는 것은 거절한다`() {
        val config = BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"), paramOverrides = mapOf("forcedActionThreshold" to "8"))
        val s = editor.fromConfig(config)
        assertThat(editor.config(s)).isEqualTo(config)
        assertThat(editor.intValue(s, threshold)).isEqualTo(8)
        assertFailure { editor.fromConfig(config.copy(houseRules = setOf("future_rule"))) }.isInstanceOf(IllegalArgumentException::class)
        assertFailure { editor.fromConfig(config.copy(paramOverrides = mapOf("handSize" to "3"))) }.isInstanceOf(IllegalArgumentException::class)
        assertFailure { editor.fromConfig(config.copy(baseId = "other")) }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `기반은 아무것도 바뀌지 않은 룰셋이어야 한다`() {
        assertFailure { RuleSetEditor(registry, BuiltinRules.classicConfig().copy(houseRules = setOf("last_stand"))) }
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage("base must be the plain rule set")
    }

    // ---- 사용자가 만들 수 있는 모든 조합이 실제 게임이 된다 -----------------------------------------

    private fun everySelection(): List<RuleSelection> {
        val houseSets = editor.houseRules.map { it.id }.let { ids -> (0 until (1 shl ids.size)).map { m -> ids.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet() } }
        val coinValues = listOf(coins.min, coins.default, coins.max)
        val thresholdValues = listOf(threshold.min, threshold.default, threshold.max)
        return buildList {
            for (h in houseSets) for (c in coinValues) for (t in thresholdValues) for (r in listOf(true, false)) for (sh in listOf(true, false)) {
                var s = RuleSelection(houseRules = h)
                s = editor.setInt(s, coins, c)
                s = editor.setInt(s, threshold, t)
                s = editor.setBool(s, refund, r)
                s = editor.setBool(s, shuffle, sh)
                add(s)
            }
        }
    }

    @Test
    fun `극단 값을 포함한 모든 조합이 룰셋으로 만들어진다`() {
        val all = everySelection()
        assertThat(all.size).isEqualTo(4 * 3 * 3 * 2 * 2)
        all.forEach { s -> assertThat(editor.issues(s), s.toString()).isEmpty() }
    }

    /** 무작위 에이전트끼리 끝까지 둔다. 어떤 조합에서도 멈추거나 거절된 수가 나오면 안 된다. */
    private fun playOut(config: RuleSetConfig, players: Int, seed: Long) {
        val engine = GameEngines.create()
        val seats = List(players) { PlayerId("p$it") }
        val agents = seats.associateWith { RandomAgent(seed + it.value.hashCode()) }
        var state = engine.newGame(GameSetup("g$seed", seats, config, seed))
        var steps = 0
        while (!state.isOver) {
            check(steps++ < 6_000) { "did not finish: $config players=$players seed=$seed" }
            val who = engine.pendingDeciders(state).first()
            val view = engine.view(state, Viewer.Player(who))
            val result = engine.apply(state, agents.getValue(who).decide(view, checkNotNull(view.myDecision)))
            check(result is ApplyResult.Accepted) { "rejected under $config: $result" }
            state = result.state
        }
    }

    @Test
    fun `극단 값 조합으로 2명과 6명이 끝까지 둘 수 있다`() {
        val extremes = everySelection().filter { s ->
            editor.intValue(s, coins) != coins.default || editor.intValue(s, threshold) != threshold.default || s.houseRules.isNotEmpty() || s.params.isNotEmpty()
        }
        extremes.forEachIndexed { i, s ->
            playOut(editor.config(s), players = if (i % 2 == 0) 2 else 6, seed = 100L + i)
        }
    }
}
