package io.github.lhd980820.coup.engine.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.command.Rejection
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.event.GameEvent
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.LossReason
import io.github.lhd980820.coup.engine.model.Phase
import io.github.lhd980820.coup.engine.testing.accept
import io.github.lhd980820.coup.engine.testing.allCardIds
import io.github.lhd980820.coup.engine.testing.coinsOf
import io.github.lhd980820.coup.engine.testing.declare
import io.github.lhd980820.coup.engine.testing.hiddenRoles
import io.github.lhd980820.coup.engine.testing.id
import io.github.lhd980820.coup.engine.testing.isPhase
import io.github.lhd980820.coup.engine.testing.only
import io.github.lhd980820.coup.engine.testing.reject
import io.github.lhd980820.coup.engine.testing.revealedRoles
import io.github.lhd980820.coup.engine.testing.scenario
import io.github.lhd980820.coup.engine.testing.testEngine
import io.github.lhd980820.coup.engine.view.DecisionRequest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DeclareActionTest {

    private val threePlayers = scenario {
        player("a", "duke", "captain", coins = 2)
        player("b", "contessa", "assassin", coins = 2)
        player("c", "ambassador", "duke", coins = 2)
    }

    @Nested
    inner class 수입 {
        @Test
        fun `응답 창 없이 즉시 코인 +1 후 다음 플레이어 턴`() {
            val (state, events) = threePlayers.declare("a", "income")
            assertThat(state.coinsOf("a")).isEqualTo(3)
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(state.turn.number).isEqualTo(2)
            assertThat(state.currentAction).isNull()
            assertThat(state.version).isEqualTo(1L)
            assertThat(events.map { it::class }).containsExactly(
                GameEvent.ActionDeclared::class,
                GameEvent.ActionResolved::class,
                GameEvent.CoinsChanged::class,
                GameEvent.TurnStarted::class,
            )
        }
    }

    @Nested
    inner class 쿠 {
        private val rich = scenario {
            player("a", "duke", "captain", coins = 7)
            player("b", "contessa", "assassin")
            player("c", "ambassador", "duke")
        }

        @Test
        fun `정확히 7코인으로 가능, 선언 시 비용을 지불하고 대상이 잃을 카드를 고른다`() {
            val (state, _) = rich.declare("a", "coup", "b")
            assertThat(state.coinsOf("a")).isEqualTo(0)
            assertThat(state.phase).isEqualTo(Phase.AwaitingInfluenceLoss(id("b"), LossReason.ActionEffect(ActionId("coup"))))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(setOf(id("b")))
            val decision = testEngine.legalOptions(state, id("b"))
            assertThat(decision).isNotNull().isInstanceOf(DecisionRequest.ChooseInfluenceToLose::class)
                .prop(DecisionRequest.ChooseInfluenceToLose::cards).hasSize(2)
        }

        @Test
        fun `대상이 고른 카드를 잃고 턴이 넘어간다`() {
            val afterCoup = rich.declare("a", "coup", "b").state
            val assassinCard = afterCoup.players.getValue(id("b")).hiddenCards.first { it.role.value == "assassin" }
            val (state, events) = afterCoup.accept(Command.LoseInfluence(id("b"), assassinCard.id))
            assertThat(state.revealedRoles("b")).containsExactly("assassin")
            assertThat(state.hiddenRoles("b")).containsExactly("contessa")
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("b")))
            assertThat(events.only<GameEvent.InfluenceLost>()).hasSize(1)
            assertThat(state.version).isEqualTo(2L)
        }

        @Test
        fun `6코인으로는 불가`() {
            val poor = scenario {
                player("a", "duke", "captain", coins = 6)
                player("b", "contessa", "assassin")
            }
            assertThat(poor.reject(Command.DeclareAction(id("a"), ActionId("coup"), id("b"))))
                .isEqualTo(Rejection.INSUFFICIENT_COINS)
        }

        @Test
        fun `카드 1장 남은 대상은 자동으로 잃고 탈락, 탈락자 턴은 건너뛴다`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin", revealed = setOf(0))
                player("c", "ambassador", "duke")
            }
            val (state, events) = s.declare("a", "coup", "b")
            assertThat(state.players.getValue(id("b")).isAlive).isFalse()
            assertThat(state.eliminationOrder).containsExactly(id("b"))
            assertThat(state.phase).isEqualTo(Phase.AwaitingAction(id("c")))
            assertThat(events.only<GameEvent.PlayerEliminated>()).hasSize(1)
        }

        @Test
        fun `마지막 상대를 탈락시키면 즉시 게임 종료, 순위는 승자 - 늦게 탈락한 순`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin", revealed = setOf(0))
                player("c", "ambassador", "duke", revealed = setOf(0, 1))
            }
            val (state, events) = s.declare("a", "coup", "b")
            assertThat(state.isOver).isTrue()
            assertThat(state.phase).isPhase<Phase.GameOver>().prop(Phase.GameOver::ranking)
                .containsExactly(id("a"), id("b"), id("c"))
            assertThat(testEngine.pendingDeciders(state)).isEqualTo(emptySet())
            assertThat(events.last()).isInstanceOf(GameEvent.GameEnded::class)
            assertThat(state.reject(Command.DeclareAction(id("a"), ActionId("income")))).isEqualTo(Rejection.GAME_OVER)
        }

        @Test
        fun `자기 자신, 탈락자, 대상 누락은 INVALID_TARGET`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin", revealed = setOf(0, 1))
                player("c", "ambassador", "duke")
            }
            listOf("a", "b", null).forEach { target ->
                assertThat(s.reject(Command.DeclareAction(id("a"), ActionId("coup"), target?.let(::id))))
                    .isEqualTo(Rejection.INVALID_TARGET)
            }
        }
    }

    @Nested
    inner class 강제_쿠 {
        private val tenCoins = scenario {
            player("a", "duke", "captain", coins = 10)
            player("b", "contessa", "assassin")
        }

        @Test
        fun `코인이 정확히 10이면 쿠 외 행동은 거절`() {
            assertThat(tenCoins.reject(Command.DeclareAction(id("a"), ActionId("income"))))
                .isEqualTo(Rejection.FORCED_ACTION_REQUIRED)
            assertThat(tenCoins.declare("a", "coup", "b").state.coinsOf("a")).isEqualTo(3)
        }

        @Test
        fun `선택지는 쿠 하나뿐이고 forcedOnly로 표시된다`() {
            val decision = testEngine.legalOptions(tenCoins, id("a")) as DecisionRequest.ChooseAction
            assertThat(decision.options.map { it.actionId.value }).containsExactly("coup")
            assertThat(decision.options.single().forcedOnly).isTrue()
        }

        @Test
        fun `다른 사람 턴에 10코인이 되어도 다음 자기 턴에 강제된다`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 9)
                player("b", "contessa", "assassin")
            }
            val afterA = s.declare("a", "income").state
            val afterB = afterA.declare("b", "income").state
            assertThat(afterB.reject(Command.DeclareAction(id("a"), ActionId("income"))))
                .isEqualTo(Rejection.FORCED_ACTION_REQUIRED)
        }
    }

    @Nested
    inner class 검증 {
        @Test
        fun `자기 턴이 아니면 NOT_YOUR_DECISION`() {
            assertThat(threePlayers.reject(Command.DeclareAction(id("b"), ActionId("income"))))
                .isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `없는 행동은 UNKNOWN_ACTION`() {
            assertThat(threePlayers.reject(Command.DeclareAction(id("a"), ActionId("fly"))))
                .isEqualTo(Rejection.UNKNOWN_ACTION)
        }

        @Test
        fun `대상이 필요 없는 행동에 대상을 주면 INVALID_TARGET`() {
            assertThat(threePlayers.reject(Command.DeclareAction(id("a"), ActionId("income"), id("b"))))
                .isEqualTo(Rejection.INVALID_TARGET)
        }

        @Test
        fun `지난 버전 명령은 STALE_VERSION`() {
            assertThat(threePlayers.reject(Command.DeclareAction(id("a"), ActionId("income"), expectedVersion = 5)))
                .isEqualTo(Rejection.STALE_VERSION)
            threePlayers.accept(Command.DeclareAction(id("a"), ActionId("income"), expectedVersion = 0))
        }

        @Test
        fun `탈락자 명령은 PLAYER_ELIMINATED, 좌석에 없는 플레이어는 NOT_YOUR_DECISION`() {
            val s = scenario {
                player("a", "duke", "captain")
                player("b", "contessa", "assassin", revealed = setOf(0, 1))
                player("c", "ambassador", "duke")
            }
            assertThat(s.reject(Command.DeclareAction(id("b"), ActionId("income")))).isEqualTo(Rejection.PLAYER_ELIMINATED)
            assertThat(s.reject(Command.DeclareAction(id("zz"), ActionId("income")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `행동 대기 중 다른 종류의 명령은 결정권자면 WRONG_PHASE, 아니면 NOT_YOUR_DECISION`() {
            assertThat(threePlayers.reject(Command.Pass(id("a")))).isEqualTo(Rejection.WRONG_PHASE)
            assertThat(threePlayers.reject(Command.Pass(id("b")))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `거절되면 상태는 바뀌지 않는다`() {
            val before = threePlayers
            before.reject(Command.DeclareAction(id("a"), ActionId("coup"), id("b")))
            assertThat(before.version).isEqualTo(0L)
            assertThat(before.coinsOf("a")).isEqualTo(2)
        }
    }

    @Nested
    inner class 영향력_상실_검증 {
        private val awaitingLoss = scenario {
            player("a", "duke", "captain", coins = 7)
            player("b", "contessa", "assassin")
        }.declare("a", "coup", "b").state

        @Test
        fun `남의 카드나 다른 플레이어의 명령은 거절`() {
            val aCard = awaitingLoss.players.getValue(id("a")).hiddenCards.first()
            assertThat(awaitingLoss.reject(Command.LoseInfluence(id("b"), aCard.id))).isEqualTo(Rejection.CARD_NOT_OWNED)
            val bCard = awaitingLoss.players.getValue(id("b")).hiddenCards.first()
            assertThat(awaitingLoss.reject(Command.LoseInfluence(id("a"), bCard.id))).isEqualTo(Rejection.NOT_YOUR_DECISION)
        }

        @Test
        fun `이미 공개된 카드는 거절`() {
            val s = scenario {
                player("a", "duke", "captain", coins = 7)
                player("b", "contessa", "assassin", "duke", revealed = setOf(0))
            }.declare("a", "coup", "b").state
            val revealed = s.players.getValue(id("b")).influences.first { it.revealed }.card
            assertThat(s.reject(Command.LoseInfluence(id("b"), revealed.id))).isEqualTo(Rejection.CARD_ALREADY_REVEALED)
        }
    }

    @Nested
    inner class 선택지 {
        @Test
        fun `기본 상황에서 모든 행동이 나오고 블러핑 여부와 비용 충족 여부가 표시된다`() {
            val decision = testEngine.legalOptions(threePlayers, id("a")) as DecisionRequest.ChooseAction
            val byId = decision.options.associateBy { it.actionId.value }
            assertThat(byId.keys).containsExactlyInAnyOrder(
                "income", "foreign_aid", "coup", "tax", "assassinate", "steal", "exchange",
            )
            assertThat(byId.getValue("tax").iHoldClaimedRole).isTrue()
            assertThat(byId.getValue("steal").iHoldClaimedRole).isTrue()
            assertThat(byId.getValue("assassinate").iHoldClaimedRole).isFalse()
            assertThat(byId.getValue("income").iHoldClaimedRole).isTrue()
            assertThat(byId.getValue("coup").affordable).isFalse()
            assertThat(byId.getValue("coup").selectable).isFalse()
            assertThat(byId.getValue("steal").validTargets).isEqualTo(setOf(id("b"), id("c")))
            assertThat(byId.getValue("income").validTargets).isNull()
        }

        @Test
        fun `결정권자가 아니면 선택지가 없다`() {
            assertThat(testEngine.legalOptions(threePlayers, id("b"))).isNull()
        }
    }

    @Test
    fun `카드 총량은 행동 후에도 보존된다`() {
        val s = scenario {
            player("a", "duke", "captain", coins = 7)
            player("b", "contessa", "assassin")
        }
        val before = s.allCardIds().sortedBy { it.value }
        val after = s.declare("a", "coup", "b").state
        assertThat(after.allCardIds().sortedBy { it.value }).isEqualTo(before)
        assertThat(before).hasSize(15)
    }

    @Test
    fun `행동 결과 이벤트는 SUCCESS`() {
        val events = threePlayers.declare("a", "income").events
        assertThat(events.only<GameEvent.ActionResolved>().single().outcome).isEqualTo(ActionOutcome.SUCCESS)
    }
}
