package io.github.lhd980820.coup.presentation

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.ai.Personality
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.presentation.singleplayer.ConfigIssue
import io.github.lhd980820.coup.presentation.singleplayer.OpponentSpec
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerConfig
import io.github.lhd980820.coup.presentation.singleplayer.SinglePlayerSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * "화면이 길을 막지 않는다": 사람은 오직 [GameUiState]의 버튼만 보고 [GameController]의 의도만 눌러서
 * (엔진·세션을 직접 만지지 않고) 게임을 끝까지 마칠 수 있어야 한다.
 */
class UiPlaythroughTest {

    private fun TestScope.start(
        opponents: List<OpponentSpec>,
        seed: Long,
        config: SinglePlayerConfig = SinglePlayerConfig(opponents, seed = seed, aiThinkTime = 0.milliseconds..0.milliseconds),
        scope: CoroutineScope = backgroundScope,
    ): GameController {
        val session = SinglePlayerSessionFactory.create(
            config = config, engine = engine, scope = scope,
            clock = { testScheduler.currentTime }, aiDispatcher = StandardTestDispatcher(testScheduler),
        )
        val controller = GameController(session, scope)
        runCurrent()
        return controller
    }

    /** 현재 UI 결정에서 보이는 버튼 중 하나를 무작위로 누른다. 누를 수 있는 것이 하나도 없으면 false. */
    private suspend fun GameController.pressSomething(random: Random): Boolean {
        val s = state.value ?: return false
        when (val d = s.decision ?: return false) {
            is DecisionUi.Actions -> {
                val enabled = d.buttons.filter { it.enabled }
                if (enabled.isEmpty()) return false
                chooseAction(enabled.random(random).actionId)
                (state.value?.decision as? DecisionUi.PickTarget)?.let { chooseTarget(it.targets.random(random)) }
            }
            is DecisionUi.PickTarget -> chooseTarget(d.targets.random(random))
            is DecisionUi.Respond -> when {
                d.canChallenge && random.nextInt(5) == 0 -> challenge()
                d.blocks.isNotEmpty() && random.nextInt(3) == 0 -> block(d.blocks.random(random).role)
                else -> pass()
            }
            is DecisionUi.PickCard -> {
                selectCard(d.cards.random(random).id)
                confirmCard()
            }
            is DecisionUi.PickExchange -> {
                d.candidates.shuffled(random).take(d.keepCount).forEach { toggleExchange(it.id) }
                confirmExchange()
            }
        }
        return true
    }

    private suspend fun TestScope.playToEnd(controller: GameController, random: Random): Int {
        var presses = 0
        var idle = 0
        while (controller.state.value?.result == null) {
            check(presses < 4_000) { "game did not finish through the UI" }
            if (controller.pressSomething(random)) {
                presses++
                idle = 0
                runCurrent() // 화면에는 새 상태가 도착할 시간이 자연스럽게 있다
            } else {
                // 내 결정이 없다 — AI가 진행하도록 가상 시간을 흘린다
                advanceTimeBy(200)
                runCurrent()
                check(++idle < 5_000) { "stuck: no decision for me and the game is not progressing; ${controller.state.value?.banner}" }
            }
        }
        return presses
    }

    @Test
    fun `AI 1~5명과의 게임을 UI 버튼만으로 끝까지 마칠 수 있다`() = runTest {
        var total = 0
        for (aiCount in 1..5) {
            repeat(8) { g ->
                val seed = aiCount * 100L + g
                val opponents = List(aiCount) { i ->
                    OpponentSpec(if ((g + i) % 2 == 0) AiDifficulty.NORMAL else AiDifficulty.EASY, Personality.BALANCED)
                }
                val controller = start(opponents, seed)
                total += playToEnd(controller, Random(seed))
                val result = checkNotNull(controller.state.value?.result)
                assertThat(result.ranking).hasSize(aiCount + 1)
                controller.close()
            }
        }
        assertThat(total).isGreaterThan(200)
    }

    @Test
    fun `하우스룰과 파라미터 변경 룰셋에서도 끝까지 간다`() = runTest {
        val config = BuiltinRules.classicConfig().copy(
            houseRules = setOf("no_steal_from_broke", "last_stand"),
            paramOverrides = mapOf("forcedActionThreshold" to "8"),
        )
        repeat(10) { g ->
            val controller = start(
                listOf(OpponentSpec(), OpponentSpec(AiDifficulty.EASY)), seed = 900L + g,
                config = SinglePlayerConfig(listOf(OpponentSpec(), OpponentSpec(AiDifficulty.EASY)), ruleSet = config, seed = 900L + g, aiThinkTime = 0.milliseconds..0.milliseconds),
            )
            playToEnd(controller, Random(g.toLong()))
            controller.close()
        }
    }

    @Test
    fun `진행 중 언제든 기권하면 내 게임이 끝난다`() = runTest {
        repeat(10) { g ->
            val controller = start(List(3) { OpponentSpec() }, seed = 700L + g)
            val random = Random(g.toLong())
            repeat(g) { controller.pressSomething(random); advanceTimeBy(300); runCurrent() }
            if (controller.state.value?.result == null) controller.concede()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val me = checkNotNull(controller.state.value?.me)
            assertThat(me.isAlive.not() || controller.state.value?.result != null).isTrue()
            controller.close()
        }
    }

    @Test
    fun `컨트롤러를 늦게 만들어도 AI가 먼저 한 행동이 로그에 남는다`() = runTest {
        val config = SinglePlayerConfig(List(3) { OpponentSpec() }, seed = 5, aiThinkTime = 0.milliseconds..0.milliseconds)
        val session = SinglePlayerSessionFactory.create(config, engine, backgroundScope, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        // 사람이 첫 차례가 아닐 수도 있다 — 가상 시간을 충분히 흘려 AI가 먼저 움직이게 한 뒤에 컨트롤러를 만든다
        advanceTimeBy(5_000)
        runCurrent()
        val controller = GameController(session, backgroundScope)
        runCurrent()
        val state = checkNotNull(controller.state.value)
        assertThat(state.turnNumber).isGreaterThan(0)
        // 첫 차례가 AI였다면 그 행동 기록이 재생(replay)으로 들어온다. 사람이 첫 차례였다면 로그는 비어 있다.
        if (state.turnNumber > 1) assertThat(state.log.isNotEmpty()).isTrue()
    }

    @Test
    fun `싱글플레이 설정 검증`() {
        assertThat(SinglePlayerSessionFactory.validate(SinglePlayerConfig(emptyList()))).isEqualTo(listOf(ConfigIssue.NO_OPPONENTS))
        assertThat(SinglePlayerSessionFactory.validate(SinglePlayerConfig(List(6) { OpponentSpec() })))
            .isEqualTo(listOf(ConfigIssue.TOO_MANY_OPPONENTS))
        assertThat(SinglePlayerSessionFactory.validate(SinglePlayerConfig(List(5) { OpponentSpec() }))).isEqualTo(emptyList())
        assertThat(
            SinglePlayerSessionFactory.validate(
                SinglePlayerConfig(listOf(OpponentSpec(name = "A"), OpponentSpec(name = "A"))),
            ),
        ).isEqualTo(listOf(ConfigIssue.DUPLICATE_NAMES))
        assertThrows<IllegalArgumentException> {
            runTest { SinglePlayerSessionFactory.create(SinglePlayerConfig(emptyList()), engine, backgroundScope) }
        }
    }

    @Test
    fun `같은 설정은 같은 좌석 배치와 이름을 만든다`() = runTest {
        fun seats(seed: Long): List<String> {
            val c = start(listOf(OpponentSpec(name = "봇A"), OpponentSpec(name = "봇B")), seed)
            val s = checkNotNull(c.state.value)
            val order = (s.opponents.map { it.name } + checkNotNull(s.me).name)
            c.close()
            return order
        }
        assertThat(seats(11)).isEqualTo(seats(11))
        assertThat(seats(11).toSet()).isEqualTo(setOf("봇A", "봇B", "Player"))
        assertThat(checkNotNull(start(listOf(OpponentSpec()), 1).state.value).opponents.single().name).isEqualTo("AI 1")
    }

    @Test
    fun `사람 차례의 결정에는 마감이 없고 (싱글플레이 기본) 화면 정보에 비공개 카드가 없다`() = runTest {
        val controller = start(List(3) { OpponentSpec() }, seed = 21)
        val state = checkNotNull(controller.state.value)
        assertThat(state.myDeadline).isEqualTo(null)
        state.opponents.forEach { op ->
            assertThat(op.hiddenCount).isEqualTo(2)
            assertThat(op.revealed.isEmpty()).isTrue()
        }
        // 화면 모델에 상대의 카드 ID가 있는지: 내 카드 ID 집합 외의 CardUi는 존재하지 않는다
        val mine: Set<CardId> = checkNotNull(state.me).hand.map { it.id }.toSet()
        val shown = buildSet {
            state.me.hand.forEach { add(it.id) }
            (state.decision as? DecisionUi.PickCard)?.cards?.forEach { add(it.id) }
            (state.decision as? DecisionUi.PickExchange)?.candidates?.forEach { add(it.id) }
        }
        assertThat(mine.containsAll(shown)).isTrue()
        assertThat(state.me).isNotNull()
    }
}
