package io.github.lhd980820.coup.presentation.singleplayer

import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.ai.AiFactory
import io.github.lhd980820.coup.ai.Personality
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.TimeoutPolicy
import io.github.lhd980820.coup.runtime.session.LocalGameSession
import io.github.lhd980820.coup.runtime.session.LocalPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** AI 상대 한 명의 설정. [name]이 null이면 "AI n"으로 붙는다(앱이 번역된 이름을 넘길 수도 있다). */
public data class OpponentSpec(
    val difficulty: AiDifficulty = AiDifficulty.NORMAL,
    val personality: Personality = Personality.BALANCED,
    val name: String? = null,
)

/** 싱글플레이 설정 화면이 만드는 값(설계 §10.2: AI 수·난이도·룰셋/하우스룰). */
public data class SinglePlayerConfig(
    val opponents: List<OpponentSpec>,
    val ruleSet: RuleSetConfig = BuiltinRules.classicConfig(),
    val humanName: String = "Player",
    val humanId: PlayerId = PlayerId("human"),
    /** 싱글플레이는 기본적으로 사람 차례에 시간 제한이 없다(D4). */
    val unlimitedHumanTime: Boolean = true,
    /** AI가 "생각하는 척"하는 시간 범위(연출). */
    val aiThinkTime: ClosedRange<Duration> = 600.milliseconds..1_500.milliseconds,
    val seed: Long = Random.nextLong(),
)

public enum class ConfigIssue { NO_OPPONENTS, TOO_MANY_OPPONENTS, DUPLICATE_NAMES }

public object SinglePlayerSessionFactory {

    /** 설정이 유효한지 확인한다. 빈 목록이면 유효. 룰셋의 인원 범위(2~6)를 따른다. */
    public fun validate(config: SinglePlayerConfig, engine: GameEngine? = null): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        val maxPlayers = MAX_PLAYERS
        if (config.opponents.isEmpty()) issues += ConfigIssue.NO_OPPONENTS
        if (config.opponents.size + 1 > maxPlayers) issues += ConfigIssue.TOO_MANY_OPPONENTS
        val names = config.opponents.mapNotNull { it.name } + config.humanName
        if (names.toSet().size != names.size) issues += ConfigIssue.DUPLICATE_NAMES
        return issues
    }

    /**
     * 사람 1명 + AI들로 로컬 게임 세션을 만든다. 좌석 순서와 선 플레이어는 [SinglePlayerConfig.seed]로 정해진다
     * (같은 설정이면 같은 게임 — 버그 재현에 쓸 수 있다). AI마다 seed를 따로 받아 서로 다르게 행동한다.
     * @throws IllegalArgumentException 설정이 유효하지 않을 때
     */
    public fun create(
        config: SinglePlayerConfig,
        engine: GameEngine,
        scope: CoroutineScope,
        clock: Clock = Clock.System,
        aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): LocalGameSession {
        val issues = validate(config)
        require(issues.isEmpty()) { "invalid single player config: $issues" }
        val random = Random(config.seed)

        val ais = config.opponents.mapIndexed { i, spec ->
            LocalPlayer(
                id = PlayerId("ai-${i + 1}"),
                displayName = spec.name ?: "AI ${i + 1}",
                agent = AiFactory.create(spec.difficulty, spec.personality, seed = random.nextLong()),
            )
        }
        val human = LocalPlayer(config.humanId, config.humanName)
        val players = (ais + human).shuffled(random)

        return LocalGameSession(
            engine = engine,
            setup = GameSetup(
                gameId = "single-${config.seed}",
                seats = players.map { it.id },
                ruleSetConfig = config.ruleSet,
                seed = random.nextLong(),
            ),
            players = players,
            timeoutPolicy = TimeoutPolicy.standard(unlimitedLocalHuman = config.unlimitedHumanTime),
            clock = clock,
            scope = scope,
            aiDispatcher = aiDispatcher,
            aiThinkTime = config.aiThinkTime,
        )
    }

    private const val MAX_PLAYERS = 6
}
