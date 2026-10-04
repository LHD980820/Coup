package io.github.lhd980820.coup.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.core.HiddenAssignment
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.view.DecisionRequest
import io.github.lhd980820.coup.engine.view.Viewer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AiBehaviorTest {
    private val engine = Arena.engine
    private fun p(n: Int) = PlayerId("p$n")
    private fun role(r: String) = RoleId(r)

    /** p0의 시작 손패가 [roles]인 3인 게임(선 플레이어 p0)을 seed 탐색으로 찾고, 상대 손패는 [others]로 정한다. */
    private fun crafted(roles: List<String>, others: Map<PlayerId, List<String>>): GameState {
        val seed = (0L..20_000L).first { s ->
            val st = engine.newGame(GameSetup("c", List(3) { p(it) }, BuiltinRules.classicConfig(), s, p(0)))
            engine.view(st, Viewer.Player(p(0))).me!!.hand.map { it.card.role.value }.sorted() == roles.sorted()
        }
        val base = engine.newGame(GameSetup("c", List(3) { p(it) }, BuiltinRules.classicConfig(), seed, p(0)))
        val view = engine.view(base, Viewer.Player(p(0)))
        // 남은 역할로 덱을 채운다(구성 보존)
        val remaining = view.ruleSet.roles.flatMap { r -> List(r.copies) { r.id } }.toMutableList()
        view.me!!.hand.forEach { remaining.remove(it.card.role) }
        val hands = others.mapValues { (_, rs) -> rs.map(::role).onEach { check(remaining.remove(it)) } }
        return engine.determinize(view, HiddenAssignment(hands, remaining), seed = 1)
    }

    private fun GameState.apply(command: Command): GameState =
        (engine.apply(this, command) as? ApplyResult.Accepted ?: error("rejected $command")).state

    private fun GameState.cardOf(player: PlayerId, r: String) =
        engine.view(this, Viewer.Player(player)).me!!.hand.first { !it.revealed && it.card.role.value == r }.card.id

    private fun normalDecision(state: GameState, player: PlayerId): Command {
        val view = engine.view(state, Viewer.Player(player))
        return AiFactory.create(AiDifficulty.NORMAL, seed = 1).decide(view, checkNotNull(view.myDecision))
    }

    @Test
    fun `공작 3장이 모두 보이면 NORMAL은 세금 주장에 반드시 도전한다`() {
        // p0: 공작 2장. p1이 사령관 블러핑 강탈을 하다 도전받아 공작을 공개(상실) → 공작 3장 모두 p0 시점에서 확인됨
        var s = crafted(listOf("duke", "duke"), mapOf(p(1) to listOf("duke", "contessa"), p(2) to listOf("captain", "assassin")))
        s = s.apply(Command.DeclareAction(p(0), ActionId("income")))
        s = s.apply(Command.DeclareAction(p(1), ActionId("steal"), p(2)))
        s = s.apply(Command.Challenge(p(0)))
        s = s.apply(Command.RevealCard(p(1), s.cardOf(p(1), "duke")))
        s = s.apply(Command.DeclareAction(p(2), ActionId("tax")))
        repeat(20) { seed ->
            val view = engine.view(s, Viewer.Player(p(0)))
            val command = AiFactory.create(AiDifficulty.NORMAL, Personality.CAUTIOUS, seed.toLong()).decide(view, checkNotNull(view.myDecision))
            assertThat(command).isInstanceOf(Command.Challenge::class)
        }
    }

    @Test
    fun `회귀 - 마지막 카드끼리 암살과 블러핑 막기를 무한 반복하지 않는다`() {
        // 대결 측정(2인, 공격적 성격끼리, seed 2105)에서 5,000수 동안 끝나지 않던 게임.
        val agents = listOf(
            AiFactory.create(AiDifficulty.NORMAL, Personality.AGGRESSIVE, 2000L + 105 * 31),
            AiFactory.create(AiDifficulty.NORMAL, Personality.AGGRESSIVE, 2105L),
        )
        assertThat(Arena.play(agents, seed = 2105).decisions).isGreaterThan(0)
    }

    @Test
    fun `귀부인을 가진 암살 대상은 막는다`() {
        var s = crafted(listOf("contessa", "duke"), mapOf(p(1) to listOf("assassin", "captain"), p(2) to listOf("ambassador", "captain")))
        s = s.apply(Command.DeclareAction(p(0), ActionId("income")))
        s = s.apply(Command.DeclareAction(p(1), ActionId("income")))
        s = s.apply(Command.DeclareAction(p(2), ActionId("income")))
        s = s.apply(Command.DeclareAction(p(0), ActionId("income")))
        s = s.apply(Command.DeclareAction(p(1), ActionId("assassinate"), p(0)))
        assertThat(normalDecision(s, p(0))).isEqualTo(Command.Block(p(0), role("contessa"), s.version))
    }

    @Test
    fun `교환하면 가치 높은 서로 다른 역할을 남긴다`() {
        var s = crafted(listOf("ambassador", "ambassador"), mapOf(p(1) to listOf("contessa", "captain"), p(2) to listOf("assassin", "captain")))
        s = s.apply(Command.DeclareAction(p(0), ActionId("exchange")))
        s = s.apply(Command.Pass(p(1))).apply(Command.Pass(p(2)))
        val view = engine.view(s, Viewer.Player(p(0)))
        val request = view.myDecision as DecisionRequest.ChooseExchange
        val keep = (normalDecision(s, p(0)) as Command.ChooseExchange).keep
        val kept = request.candidates.filter { it.id in keep }.map { it.role.value }
        assertThat(kept.toSet().size).isEqualTo(2) // 같은 역할 두 장보다 다양성
    }

    @ParameterizedTest
    @EnumSource(AiDifficulty::class)
    fun `뷰 동치 - 비공개 정보만 다른 상태에서 같은 seed의 AI는 같은 결정을 내린다`(difficulty: AiDifficulty) {
        var checked = 0
        var g = 0
        while (checked <= 1_000) {
            g++
            val seats = List(4) { p(it) }
            var state = engine.newGame(GameSetup("eq$g", seats, BuiltinRules.classicConfig(), g.toLong()))
            val agents = seats.associateWith { AiFactory.create(difficulty, seed = g * 7L + it.value.hashCode()) }
            while (!state.isOver) {
                val who = engine.pendingDeciders(state).first()
                val view = engine.view(state, Viewer.Player(who))
                // 상대 손패와 덱을 섞은 "다른 세계"를 만든다(공개 정보는 같다)
                val pool = view.opponents.flatMap { op -> List(op.hiddenCount) { op.id } }
                val unknownRoles = buildList {
                    view.ruleSet.roles.forEach { r -> repeat(r.copies) { add(r.id) } }
                    view.me!!.hand.forEach { remove(it.card.role) }
                    view.opponents.forEach { op -> op.revealed.forEach { remove(it) } }
                    (view.myDecision as? DecisionRequest.ChooseExchange)?.candidates
                        ?.filter { c -> view.me!!.hand.none { it.card.id == c.id } }?.forEach { remove(it.role) }
                }.shuffled(kotlin.random.Random(state.version))
                val hands = view.opponents.associate { op -> op.id to mutableListOf<RoleId>() }
                pool.forEachIndexed { i, owner -> hands.getValue(owner) += unknownRoles[i] }
                val other = engine.determinize(view, HiddenAssignment(hands, unknownRoles.drop(pool.size)), seed = 99)
                val otherView = engine.view(other, Viewer.Player(who))
                check(otherView == view) { "alternative world must look identical to ${who.value}" }

                val a = AiFactory.create(difficulty, seed = 5).decide(view, checkNotNull(view.myDecision))
                val b = AiFactory.create(difficulty, seed = 5).decide(otherView, checkNotNull(otherView.myDecision))
                check(a == b) { "decision depends on hidden information: $a vs $b" }
                checked++

                val command = agents.getValue(who).decide(view, checkNotNull(view.myDecision))
                state = state.apply(command)
            }
        }
        assertThat(checked).isGreaterThan(1_000)
    }

    @ParameterizedTest
    @EnumSource(AiDifficulty::class)
    fun `모든 결정은 합법이다 - 하우스룰 조합 포함, 1만 결정 이상`(difficulty: AiDifficulty) {
        val configs = listOf(
            BuiltinRules.classicConfig(),
            BuiltinRules.classicConfig().copy(houseRules = setOf("no_steal_from_broke", "last_stand")),
            BuiltinRules.classicConfig().copy(paramOverrides = mapOf("forcedActionThreshold" to "8", "startingCoins" to "1")),
        )
        val personalities = listOf(Personality.BALANCED, Personality.CAUTIOUS, Personality.AGGRESSIVE)
        var decisions = 0
        var g = 0
        while (decisions <= 10_000) {
            g++
            val players = 2 + g % 5
            val agents = List(players) { i -> AiFactory.create(difficulty, personalities[(g + i) % 3], seed = g * 13L + i) }
            decisions += Arena.play(agents, seed = g.toLong(), config = configs[g % configs.size]).decisions
        }
        assertThat(decisions).isGreaterThan(10_000)
    }
}
