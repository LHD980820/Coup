package io.github.lhd980820.coup.game

import androidx.compose.ui.geometry.Offset
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.UiEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 날아가는 코인의 규칙(화면 없이): 신호 -> 이동, 그림 나누기, 날아가는 동안 보이는 숫자. */
class CoinFlightTest {
    private val me = PlayerId("me")
    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private fun coins(p: PlayerId, d: Int) = UiEffect.CoinsChanged(p, d)

    // ---- 신호 -> 이동 ----

    @Test
    fun `얻으면 은행에서 온다`() {
        val d = CoinDirector()
        assertEquals(listOf(CoinFlight(CoinEnd.Bank, CoinEnd.Purse(me), 3)), d.on(coins(me, 3)))
        assertFalse(d.holding)
    }

    @Test
    fun `잃으면 짝을 기다렸다가, 짝이 없으면 은행으로 간다`() {
        val d = CoinDirector()
        assertEquals(emptyList<CoinFlight>(), d.on(coins(a, -7)))
        assertTrue("짝이 될 얻음을 기다린다", d.holding)
        // 코인이 아닌 다음 신호가 오면 은행으로
        assertEquals(listOf(CoinFlight(CoinEnd.Purse(a), CoinEnd.Bank, 7)), d.on(UiEffect.ActionDeclared(a, ActionId("coup"), me, emptySet())))
        assertFalse(d.holding)
        // 신호가 더 오지 않으면 기다림을 끝낸다
        d.on(coins(b, -3))
        assertEquals(listOf(CoinFlight(CoinEnd.Purse(b), CoinEnd.Bank, 3)), d.flush())
        assertEquals(emptyList<CoinFlight>(), d.flush())
    }

    @Test
    fun `한 사람이 잃고 바로 다른 사람이 같은 만큼 얻으면 자리에서 자리로 간다(갈취)`() {
        val d = CoinDirector()
        d.on(coins(me, -2))
        assertEquals(listOf(CoinFlight(CoinEnd.Purse(me), CoinEnd.Purse(a), 2)), d.on(coins(a, 2)))
        assertFalse(d.holding)
    }

    @Test
    fun `금액이 다르거나 같은 사람이면 묶지 않는다`() {
        val d = CoinDirector()
        d.on(coins(me, -2))
        assertEquals(
            listOf(CoinFlight(CoinEnd.Purse(me), CoinEnd.Bank, 2), CoinFlight(CoinEnd.Bank, CoinEnd.Purse(a), 1)),
            d.on(coins(a, 1)),
        )
        d.on(coins(a, -3))
        assertEquals(
            listOf(CoinFlight(CoinEnd.Purse(a), CoinEnd.Bank, 3), CoinFlight(CoinEnd.Bank, CoinEnd.Purse(a), 3)),
            d.on(coins(a, 3)),
        )
        // 잃음 뒤의 잃음: 앞의 것은 은행으로, 뒤의 것은 다시 짝을 기다린다
        d.on(coins(me, -1))
        assertEquals(listOf(CoinFlight(CoinEnd.Purse(me), CoinEnd.Bank, 1)), d.on(coins(b, -4)))
        assertTrue(d.holding)
    }

    @Test
    fun `변화가 0인 신호와 다른 신호는 이동이 아니다`() {
        val d = CoinDirector()
        assertEquals(emptyList<CoinFlight>(), d.on(coins(me, 0)))
        assertEquals(emptyList<CoinFlight>(), d.on(UiEffect.Challenged(me, a, setOf(RoleId("duke")))))
    }

    @Test
    fun `이동의 이름`() {
        assertEquals("bank>me:3", CoinFlight(CoinEnd.Bank, CoinEnd.Purse(me), 3).label)
        assertEquals("me>a:2", CoinFlight(CoinEnd.Purse(me), CoinEnd.Purse(a), 2).label)
        assertEquals("a>bank:7", CoinFlight(CoinEnd.Purse(a), CoinEnd.Bank, 7).label)
    }

    // ---- 그림 나누기 ----

    @Test
    fun `닢마다 그림 하나, 많으면 다섯 개에 나눠 싣는다`() {
        assertEquals(listOf(1), spritesOf(1))
        assertEquals(listOf(1, 1, 1), spritesOf(3))
        assertEquals(listOf(1, 1, 1, 1, 1), spritesOf(5))
        assertEquals(listOf(2, 2, 1, 1, 1), spritesOf(7))
        assertEquals(listOf(3, 3, 2, 2, 2), spritesOf(12))
        assertEquals(emptyList<Int>(), spritesOf(0))
        (1..20).forEach { assertEquals(it, spritesOf(it).sum()) }
    }

    @Test
    fun `코인의 길은 출발점에서 시작해 도착점에서 끝나고 가운데가 휜다`() {
        val from = Offset(0f, 0f)
        val to = Offset(0f, 400f)
        assertEquals(from, coinPath(from, to, 0, 3, 0f, 10f))
        assertEquals(to, coinPath(from, to, 0, 3, 1f, 10f))
        val mid = coinPath(from, to, 1, 3, 0.5f, 10f)
        assertTrue("휘어 간다: $mid", mid.x != 0f)
        // 그림마다 조금씩 다른 길
        assertTrue(coinPath(from, to, 0, 3, 0.5f, 10f) != coinPath(from, to, 2, 3, 0.5f, 10f))
    }

    // ---- 날아가는 동안 보이는 숫자 ----

    @Test
    fun `떠나지 않은 코인은 보낸 사람에게, 닿지 않은 코인은 받는 사람에게서 빼고 보인다`() = runBlocking {
        val run = CoinRun(1, CoinFlight(CoinEnd.Purse(me), CoinEnd.Purse(a), 3), fly = 500, stagger = 100)
        // 0: 첫 코인이 바로 떠난다
        assertEquals(1, run.departed)
        assertEquals(0, run.arrived)
        assertEquals(2, run.offsetFor(me))
        assertEquals(-3, run.offsetFor(a))
        assertEquals(0, run.offsetFor(b))
        // 모두 떠났고 첫 코인이 닿았다
        run.clock.snapTo(run.lands(0))
        assertEquals(3, run.departed)
        assertEquals(1, run.arrived)
        assertEquals(0, run.offsetFor(me))
        assertEquals(-2, run.offsetFor(a))
        // 모두 닿았다
        run.clock.snapTo(1f)
        assertEquals(0, run.offsetFor(a))
        assertTrue("마지막 코인이 닿은 뒤에도 잠깐(반짝임·+N) 남는다", run.lands(2) < 1f)
    }

    @Test
    fun `은행이 끝이면 은행 쪽 숫자는 없다`() = runBlocking {
        val run = CoinRun(1, CoinFlight(CoinEnd.Bank, CoinEnd.Purse(me), 7), fly = 500, stagger = 100)
        assertEquals(5, run.sprites.size)
        assertEquals(-7, run.offsetFor(me))
        run.clock.snapTo(run.lands(1))
        assertEquals("두 그림(2+2닢)이 닿았다", -3, run.offsetFor(me))
    }

    @Test
    fun `짝을 기다리는 잃음도 숫자를 잃기 전 값으로 보인다`() {
        val stage = CoinStage()
        stage.take(coins(a, -7))
        assertTrue(stage.pairing)
        assertEquals(7, stage.offset(a))
        stage.endPairing()
        assertEquals("은행으로 가는 이동이 되어 기다린다", listOf(CoinFlight(CoinEnd.Purse(a), CoinEnd.Bank, 7)), stage.waiting.toList())
        assertEquals(7, stage.offset(a))
        // 짝이 오면 자리에서 자리로
        stage.clear()
        stage.take(coins(me, -2))
        stage.take(coins(a, 2))
        assertEquals(listOf(CoinFlight(CoinEnd.Purse(me), CoinEnd.Purse(a), 2)), stage.waiting.toList())
        assertEquals(2, stage.offset(me))
        assertEquals(-2, stage.offset(a))
    }

    @Test
    fun `기다리는 이동도 숫자를 날기 전 값으로 보인다`() {
        val stage = CoinStage()
        // 날릴 시계가 아직 없으면(무대가 돌기 전) 기다린다
        stage.offer(CoinFlight(CoinEnd.Purse(me), CoinEnd.Purse(a), 2))
        stage.offer(CoinFlight(CoinEnd.Bank, CoinEnd.Purse(a), 3))
        assertEquals(2, stage.waiting.size)
        assertEquals(2, stage.offset(me))
        assertEquals(-5, stage.offset(a))
        stage.clear()
        assertEquals(0, stage.offset(a))
    }
}
