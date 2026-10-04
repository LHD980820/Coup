package io.github.lhd980820.coup.presentation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.runtime.session.ConnectionState
import org.junit.jupiter.api.Test

class GameUiMapperTest {
    private fun ui(state: io.github.lhd980820.coup.engine.model.GameState, viewer: String = "me", interaction: Interaction = Interaction()) =
        GameUiMapper.map(snapshotOf(state, viewer), ConnectionState.CONNECTED, interaction)

    private val table = crafted(
        myHand = listOf("duke", "captain"),
        others = mapOf("a" to listOf("contessa", "assassin"), "b" to listOf("ambassador", "duke")),
    )

    @Test
    fun `내 차례에는 모든 행동 버튼이 나오고 블러핑과 비용 부족이 표시된다`() {
        val state = ui(table)
        assertThat(state.banner).isEqualTo(Banner.YourTurn(forced = false))
        val actions = (state.decision as DecisionUi.Actions).buttons.associateBy { it.actionId.value }
        assertThat(actions.keys).isEqualTo(setOf("income", "foreign_aid", "coup", "tax", "assassinate", "steal", "exchange"))
        assertThat(actions.getValue("tax").bluff).isFalse() // 공작 보유
        assertThat(actions.getValue("steal").bluff).isFalse() // 사령관 보유
        assertThat(actions.getValue("exchange").bluff).isTrue()
        assertThat(actions.getValue("assassinate").bluff).isTrue()
        assertThat(actions.getValue("income").bluff).isFalse()
        assertThat(actions.getValue("coup").enabled).isFalse()
        assertThat(actions.getValue("coup").disabledReason).isEqualTo(DisabledReason.INSUFFICIENT_COINS)
        assertThat(actions.getValue("assassinate").disabledReason).isEqualTo(DisabledReason.INSUFFICIENT_COINS)
        assertThat(actions.getValue("steal").needsTarget).isTrue()
        assertThat(actions.getValue("steal").validTargets.map { it.value }).containsExactly("a", "b")
        assertThat(actions.getValue("income").enabled).isTrue()
    }

    @Test
    fun `내 정보와 상대 정보 - 상대의 카드는 장수만 보인다`() {
        val state = ui(table)
        assertThat(state.me!!.hand.map { it.role.value }).containsExactly("duke", "captain")
        assertThat(state.me.coins).isEqualTo(2)
        assertThat(state.opponents.map { it.name }).containsExactly("이름-a", "이름-b")
        assertThat(state.opponents.map { it.hiddenCount }).containsExactly(2, 2)
        assertThat(state.opponents.map { it.revealed.size }).containsExactly(0, 0)
        assertThat(state.deckSize).isEqualTo(9)
        assertThat(state.turnNumber).isEqualTo(1)
        assertThat(state.result).isNull()
    }

    @Test
    fun `상대 차례에는 결정이 없고 대기 배너와 행동 중 표시가 나온다`() {
        val waiting = ui(table.after(Command.DeclareAction(p("me"), ActionId("income"))))
        assertThat(waiting.banner).isEqualTo(Banner.WaitingFor(p("a")))
        assertThat(waiting.decision).isNull()
        assertThat(waiting.opponents.first { it.id == p("a") }.status).isEqualTo(OpponentStatus.ACTING)
        assertThat(waiting.opponents.first { it.id == p("b") }.status).isEqualTo(OpponentStatus.IDLE)
        assertThat(waiting.me!!.status).isEqualTo(OpponentStatus.IDLE)
    }

    @Test
    fun `내 행동에 대한 응답 대기 - 배너 대기 목록과 상대 상태 그리고 주장 배지`() {
        val declared = table.after(Command.DeclareAction(p("me"), ActionId("tax")))
        val mine = ui(declared)
        assertThat(mine.banner).isEqualTo(
            Banner.ActionDeclared(p("me"), ActionId("tax"), null, setOf(RoleId("duke")), waitingOn = setOf(p("a"), p("b"))),
        )
        assertThat(mine.decision).isNull()
        assertThat(mine.me!!.status).isEqualTo(OpponentStatus.IDLE) // 응답을 기다리는 쪽은 상대들
        assertThat(mine.opponents.map { it.status }).containsExactly(OpponentStatus.THINKING, OpponentStatus.THINKING)

        val aPassed = declared.after(Command.Pass(p("a")))
        val state = ui(aPassed)
        assertThat(state.opponents.first { it.id == p("a") }.status).isEqualTo(OpponentStatus.RESPONDED)
        assertThat(state.opponents.first { it.id == p("b") }.status).isEqualTo(OpponentStatus.THINKING)
    }

    @Test
    fun `남의 행동에 응답할 때 - 도전과 막기 버튼, 주장 배지, 도전 대상`() {
        val state = ui(table.after(Command.DeclareAction(p("me"), ActionId("income")), Command.DeclareAction(p("a"), ActionId("tax"))))
        val respond = state.decision as DecisionUi.Respond
        assertThat(respond.windowKind).isEqualTo(WindowKind.ACTION)
        assertThat(respond.claimant).isEqualTo(p("a"))
        assertThat(respond.claimedRoles).isEqualTo(setOf(RoleId("duke")))
        assertThat(respond.canChallenge).isTrue()
        assertThat(respond.blocks).isEmpty()
        assertThat(state.opponents.first { it.id == p("a") }.claiming).isEqualTo(setOf(RoleId("duke")))
        assertThat(state.banner).isInstanceOf(Banner.ActionDeclared::class)
    }

    @Test
    fun `해외원조에는 공작으로 막는 버튼이 나오고 내가 공작이면 블러핑 표시가 없다`() {
        val aid = ui(table.after(Command.DeclareAction(p("me"), ActionId("income")), Command.DeclareAction(p("a"), ActionId("foreign_aid"))))
        val respond = aid.decision as DecisionUi.Respond
        assertThat(respond.canChallenge).isFalse()
        assertThat(respond.blocks).containsExactly(BlockButtonUi(RoleId("duke"), bluff = false))
    }

    @Test
    fun `막기에 대한 도전 - 도전 대상은 막은 사람이고 주장은 막기 역할`() {
        val blocked = table.after(
            Command.DeclareAction(p("me"), ActionId("income")),
            Command.DeclareAction(p("a"), ActionId("foreign_aid")),
            Command.Block(p("b"), RoleId("duke")),
        )
        val state = ui(blocked)
        val respond = state.decision as DecisionUi.Respond
        assertThat(respond.windowKind).isEqualTo(WindowKind.BLOCK_CHALLENGE)
        assertThat(respond.claimant).isEqualTo(p("b"))
        assertThat(respond.claimedRoles).isEqualTo(setOf(RoleId("duke")))
        assertThat(state.banner).isInstanceOf(Banner.BlockDeclared::class)
        assertThat(state.opponents.first { it.id == p("b") }.claiming).isEqualTo(setOf(RoleId("duke")))
    }

    @Test
    fun `도전받으면 공개할 카드를 고른다 - 주장을 증명하는 카드가 표시된다`() {
        val challenged = table.after(Command.DeclareAction(p("me"), ActionId("tax")), Command.Challenge(p("a")))
        val state = ui(challenged)
        assertThat(state.banner).isEqualTo(Banner.Challenged(p("a"), p("me"), setOf(RoleId("duke")), againstBlock = false))
        val pick = state.decision as DecisionUi.PickCard
        assertThat(pick.purpose).isEqualTo(CardPurpose.REVEAL)
        assertThat(pick.cards.map { it.satisfiesClaim }).containsExactly(true, false)
        assertThat(pick.selected).isNull()
    }

    @Test
    fun `대상 선택 단계 - 행동을 고른 뒤 고를 수 있는 상대만 표시된다`() {
        val state = ui(table, interaction = Interaction(pendingAction = ActionId("steal")))
        val pick = state.decision as DecisionUi.PickTarget
        assertThat(pick.actionId).isEqualTo(ActionId("steal"))
        assertThat(pick.targets.map { it.value }).containsExactly("a", "b")
        assertThat(state.opponents.map { it.targetable }).containsExactly(true, true)
    }

    @Test
    fun `사용할 수 없는 행동으로는 대상 선택 단계에 들어가지 않는다`() {
        val state = ui(table, interaction = Interaction(pendingAction = ActionId("coup")))
        assertThat(state.decision).isNotNull().isInstanceOf(DecisionUi.Actions::class)
    }

    @Test
    fun `교환 선택 - 후보와 선택 개수, 확정 가능 여부`() {
        val declared = table.after(Command.DeclareAction(p("me"), ActionId("exchange")), Command.Pass(p("a")), Command.Pass(p("b")))
        val pick0 = ui(declared).decision as DecisionUi.PickExchange
        assertThat(pick0.candidates.size).isEqualTo(4)
        assertThat(pick0.keepCount).isEqualTo(2)
        assertThat(pick0.canConfirm).isFalse()
        val two = pick0.candidates.take(2).map { it.id }.toSet()
        val pick2 = ui(declared, interaction = Interaction(selectedCards = two)).decision as DecisionUi.PickExchange
        assertThat(pick2.canConfirm).isTrue()
        assertThat(pick2.candidates.count { it.selected }).isEqualTo(2)
        // 다른 사람 시점에서는 교환 후보를 볼 수 없고 배너만 보인다
        val other = ui(declared, viewer = "a")
        assertThat(other.banner).isEqualTo(Banner.Exchanging(p("me")))
        assertThat(other.decision).isNull()
    }

    @Test
    fun `게임이 끝나면 결과와 내 순위가 나온다`() {
        val over = crafted(listOf("duke", "captain"), mapOf("a" to listOf("contessa", "assassin"))).after(Command.Concede(p("a")))
        val state = ui(over)
        assertThat(state.banner).isInstanceOf(Banner.GameOver::class)
        assertThat(state.result).isEqualTo(ResultUi(p("me"), listOf(p("me"), p("a")), iWon = true, myRank = 1))
        assertThat(state.decision).isNull()
        val loser = ui(over, viewer = "a").result!!
        assertThat(loser.iWon).isFalse()
        assertThat(loser.myRank).isEqualTo(2)
    }

    @Test
    fun `탈락한 상대와 나의 상태`() {
        val state = ui(table.after(Command.Concede(p("a"))))
        assertThat(state.opponents.first { it.id == p("a") }.status).isEqualTo(OpponentStatus.ELIMINATED)
        assertThat(state.opponents.first { it.id == p("a") }.revealed.size).isEqualTo(2)
        val self = ui(table.after(Command.Concede(p("me"))))
        assertThat(self.me!!.isAlive).isFalse()
        assertThat(self.me.status).isEqualTo(OpponentStatus.ELIMINATED)
    }

    @Test
    fun `마감과 연결 상태와 busy 플래그가 그대로 전달된다`() {
        val snap = snapshotOf(table, deadline = 12_345L)
        val state = GameUiMapper.map(snap, ConnectionState.RECONNECTING, busy = true)
        assertThat(state.myDeadline).isEqualTo(12_345L)
        assertThat(state.connection).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(state.busy).isTrue()
        assertThat(state.log).isEmpty()
        assertThat(state.gameId).isEqualTo("ui")
    }
}
