package io.github.lhd980820.coup.engine.testing

import assertk.Assert
import assertk.assertions.isInstanceOf
import assertk.fail
import io.github.lhd980820.coup.engine.command.ApplyResult
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.core.GameEngine
import io.github.lhd980820.coup.engine.core.GameEngines
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.Card
import io.github.lhd980820.coup.engine.model.CardId
import io.github.lhd980820.coup.engine.model.GameState
import io.github.lhd980820.coup.engine.model.Influence
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.PlayerState
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.TurnInfo
import io.github.lhd980820.coup.engine.rng.DeterministicRng
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.engine.serialization.ENGINE_SCHEMA_VERSION

/**
 * 손패·코인·덱 순서를 직접 지정해 [GameState]를 만드는 테스트 픽스처(설계 문서 §12.2).
 *
 * 지정하지 않은 덱 카드는 룰셋 구성에서 손패와 덱 위 카드를 뺀 나머지로 채워 총 장수를 보존한다.
 * 카드 ID는 손패(좌석 순서) → 덱 위 → 나머지 순으로 0부터 부여한다.
 */
class ScenarioBuilder(private val config: RuleSetConfig) {
    private class Seat(val id: PlayerId, val roles: List<RoleId>, val coins: Int, val revealed: Set<Int>)

    private val seats = mutableListOf<Seat>()
    private var deckTop: List<RoleId> = emptyList()
    private var first: PlayerId? = null
    private var seed: Long = 0

    /** [revealed]: 이미 잃은 카드의 손패 인덱스. */
    fun player(id: String, vararg roles: String, coins: Int = 2, revealed: Set<Int> = emptySet()) {
        seats += Seat(PlayerId(id), roles.map { RoleId(it) }, coins, revealed)
    }

    fun deckTop(vararg roles: String) {
        deckTop = roles.map { RoleId(it) }
    }

    fun firstPlayer(id: String) {
        first = PlayerId(id)
    }

    fun seed(value: Long) {
        seed = value
    }

    fun build(): GameState {
        val rules = BuiltinRules.registry().build(config)
        val remaining = rules.roles.associate { it.id to it.copies }.toMutableMap()
        var nextId = 0
        fun take(role: RoleId): Card {
            val left = remaining[role] ?: error("unknown role ${role.value}")
            check(left > 0) { "no more ${role.value} cards in the deck composition" }
            remaining[role] = left - 1
            return Card(CardId(nextId++), role)
        }

        val players = seats.associate { seat ->
            seat.id to PlayerState(
                seat.id,
                seat.coins,
                seat.roles.mapIndexed { i, role -> Influence(take(role), revealed = i in seat.revealed) },
            )
        }
        val top = deckTop.map(::take)
        val rest = rules.roles.flatMap { role -> List(remaining.getValue(role.id)) { take(role.id) } }
        val active = first ?: seats.first().id
        return GameState(
            schemaVersion = ENGINE_SCHEMA_VERSION,
            gameId = "scenario",
            version = 0,
            ruleSetConfig = config,
            seats = seats.map { it.id },
            turn = TurnInfo(1, active),
            eliminationOrder = seats.filter { s -> players.getValue(s.id).isAlive.not() }.map { it.id },
            players = players,
            deck = top + rest,
            phase = Phase.AwaitingAction(active),
            currentAction = null,
            stack = emptyList(),
            rng = DeterministicRng.ofSeed(seed),
        )
    }
}

fun scenario(config: RuleSetConfig = BuiltinRules.classicConfig(), block: ScenarioBuilder.() -> Unit): GameState =
    ScenarioBuilder(config).apply(block).build()

val testEngine: GameEngine = GameEngines.create()

fun id(value: String): PlayerId = PlayerId(value)

/** 명령을 적용하고 수락되어야 한다. 새 상태와 이벤트를 돌려준다. */
fun GameState.accept(command: Command): ApplyResult.Accepted =
    when (val r = testEngine.apply(this, command)) {
        is ApplyResult.Accepted -> r
        is ApplyResult.Rejected -> fail("expected $command to be accepted but was rejected: ${r.reason}")
    }

fun GameState.reject(command: Command): Rejection =
    when (val r = testEngine.apply(this, command)) {
        is ApplyResult.Accepted -> fail("expected $command to be rejected but was accepted")
        is ApplyResult.Rejected -> r.reason
    }

fun GameState.declare(actor: String, action: String, target: String? = null): ApplyResult.Accepted =
    accept(Command.DeclareAction(id(actor), ActionId(action), target?.let(::id)))

fun GameState.coinsOf(player: String): Int = players.getValue(id(player)).coins

fun GameState.hiddenRoles(player: String): List<String> = players.getValue(id(player)).hiddenCards.map { it.role.value }

fun GameState.revealedRoles(player: String): List<String> = players.getValue(id(player)).revealedRoles.map { it.value }

/** 덱 + 모든 손패(공개 포함)의 카드 ID. 카드 보존 불변식 검증용. */
fun GameState.allCardIds(): List<CardId> = deck.map { it.id } + players.values.flatMap { p -> p.influences.map { it.card.id } }

inline fun <reified T : GameEvent> List<GameEvent>.only(): List<T> = filterIsInstance<T>()

inline fun <reified T : Phase> Assert<Phase>.isPhase(): Assert<T> = isInstanceOf(T::class)
