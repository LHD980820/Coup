package io.github.lhd980820.coup.game

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.UiEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 극적인 순간의 규칙(화면 없이): 어떤 신호가 순간이 되는가, 밀려오면 무엇을 남기는가, 하나씩 무대에 오르는가. */
class MomentLogicTest {
    private val me = PlayerId("me")
    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val duke = setOf(RoleId("duke"))

    private fun director() = MomentDirector { me }

    // ---- 신호 -> 순간 ----

    @Test
    fun `도전 뒤의 공개는 그 도전의 주장과 도전자를 함께 안다`() {
        val d = director()
        assertEquals(Moment.Challenge(a, me, duke), d.moment(UiEffect.Challenged(a, me, duke)))
        assertEquals(Moment.Reveal(me, RoleId("duke"), proven = true, claimed = duke, challenger = a), d.moment(UiEffect.CardRevealed(me, RoleId("duke"), proven = true)))
    }

    @Test
    fun `블러핑 발각은 뒤집은 카드와 거짓 주장을 함께 안다`() {
        val d = director()
        d.moment(UiEffect.Challenged(me, a, duke))
        assertEquals(Moment.Reveal(a, RoleId("contessa"), proven = false, claimed = duke, challenger = me), d.moment(UiEffect.CardRevealed(a, RoleId("contessa"), proven = false)))
    }

    @Test
    fun `도전을 못 봤거나 다른 사람의 공개면 주장과 도전자를 모른다`() {
        val d = director()
        assertEquals(Moment.Reveal(a, RoleId("duke"), true, emptySet(), null), d.moment(UiEffect.CardRevealed(a, RoleId("duke"), true)))
        d.moment(UiEffect.Challenged(me, a, duke))
        assertEquals(Moment.Reveal(b, RoleId("duke"), true, emptySet(), null), d.moment(UiEffect.CardRevealed(b, RoleId("duke"), true)))
    }

    @Test
    fun `공개가 끝나면 그 도전은 잊는다(다음 공개에 잘못 붙지 않게)`() {
        val d = director()
        d.moment(UiEffect.Challenged(b, a, duke))
        d.moment(UiEffect.CardRevealed(a, RoleId("duke"), true))
        assertEquals(Moment.Reveal(a, RoleId("captain"), false, emptySet(), null), d.moment(UiEffect.CardRevealed(a, RoleId("captain"), false)))
    }

    @Test
    fun `쿠 선언만 순간이 되고 다른 행동 선언은 아니다`() {
        val d = director()
        assertEquals(Moment.Coup(a, me), d.moment(UiEffect.ActionDeclared(a, ActionId("coup"), me, emptySet())))
        assertNull(d.moment(UiEffect.ActionDeclared(a, ActionId("tax"), null, duke)))
        assertNull(d.moment(UiEffect.ActionDeclared(a, ActionId("assassinate"), me, setOf(RoleId("assassin")))))
    }

    @Test
    fun `막기와 탈락은 순간이 되고, 탈락은 나인지 안다`() {
        val d = director()
        assertEquals(Moment.Block(a, RoleId("contessa"), ActionId("assassinate")), d.moment(UiEffect.BlockDeclared(a, RoleId("contessa"), ActionId("assassinate"))))
        assertEquals(Moment.Eliminated(a, isMe = false), d.moment(UiEffect.Eliminated(a)))
        assertEquals(Moment.Eliminated(me, isMe = true), d.moment(UiEffect.Eliminated(me)))
    }

    @Test
    fun `코인·허용·카드 교체·영향력 상실·차례 시작·판 끝은 순간이 아니다`() {
        val d = director()
        listOf(
            UiEffect.CoinsChanged(a, 3), UiEffect.Passed(a), UiEffect.CardReplaced(a), UiEffect.InfluenceLost(a, RoleId("duke")),
            UiEffect.TurnStarted(a, 3), UiEffect.GameEnded(a),
        ).forEach { assertNull("$it", d.moment(it)) }
    }

    @Test
    fun `순간의 이름과 무게`() {
        assertEquals("proven", Moment.Reveal(a, RoleId("duke"), true, duke, b).kind)
        assertEquals("bluff", Moment.Reveal(a, RoleId("duke"), false, duke, b).kind)
        assertEquals("eliminated_me", Moment.Eliminated(me, true).kind)
        assertEquals("eliminated", Moment.Eliminated(a, false).kind)
        // 결과(공개·탈락)가 선언(도전·쿠)보다, 선언이 막기보다 무겁다
        assertTrue(Moment.Reveal(a, RoleId("duke"), true, duke, b).weight > Moment.Challenge(a, b, duke).weight)
        assertTrue(Moment.Eliminated(a, false).weight > Moment.Coup(a, b).weight)
        assertTrue(Moment.Challenge(a, b, duke).weight > Moment.Block(a, RoleId("duke"), ActionId("foreign_aid")).weight)
    }

    // ---- 대기열 ----

    private val block = Moment.Block(a, RoleId("duke"), ActionId("foreign_aid"))
    private val challenge = Moment.Challenge(a, b, duke)
    private val reveal = Moment.Reveal(b, RoleId("duke"), true, duke, a)
    private val coup = Moment.Coup(a, b)

    @Test
    fun `대기열은 들어온 순서대로 내보낸다`() {
        val q = MomentQueue()
        q.offer(challenge)
        q.offer(reveal)
        assertEquals(challenge, q.poll())
        assertEquals(reveal, q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `넘치면 가장 가벼운 것을 버린다`() {
        val q = MomentQueue(capacity = 2)
        q.offer(block)
        q.offer(challenge)
        q.offer(reveal)
        assertEquals(listOf(challenge, reveal), q.pending)
    }

    @Test
    fun `무게가 같으면 오래된 것을 버린다`() {
        val q = MomentQueue(capacity = 2)
        q.offer(challenge)
        q.offer(coup)
        q.offer(Moment.Challenge(b, a, duke))
        assertEquals(listOf(coup, Moment.Challenge(b, a, duke)), q.pending)
    }

    @Test
    fun `새로 온 것이 가장 가벼우면 새로 온 것을 버린다`() {
        val q = MomentQueue(capacity = 2)
        q.offer(challenge)
        q.offer(reveal)
        q.offer(block)
        assertEquals(listOf(challenge, reveal), q.pending)
    }

    // ---- 무대 ----

    @Test
    fun `비어 있는 무대에 오면 바로 오르고, 끝나면 다음 것이 오른다`() {
        val stage = MomentStage()
        assertTrue(stage.idle)
        stage.offer(challenge)
        val first = stage.playing!!
        assertEquals(challenge, first.moment)
        stage.offer(reveal)
        assertEquals("앞의 것이 끝날 때까지 기다린다", first, stage.playing)
        assertFalse(stage.idle)
        stage.finished(first)
        assertEquals(reveal, stage.playing!!.moment)
        stage.finished(stage.playing!!)
        assertNull(stage.playing)
        assertTrue(stage.idle)
    }

    @Test
    fun `이미 지난 순간의 끝 알림은 무시한다`() {
        val stage = MomentStage()
        stage.offer(challenge)
        val first = stage.playing!!
        stage.finished(first)
        stage.offer(reveal)
        val second = stage.playing!!
        stage.finished(first) // 늦게 도착한 옛 알림
        assertEquals(second, stage.playing)
    }

    @Test
    fun `같은 순간이 연달아 와도 따로 재생된다`() {
        val stage = MomentStage()
        stage.offer(block)
        val first = stage.playing!!
        stage.offer(block)
        stage.finished(first)
        val second = stage.playing!!
        assertEquals(block, second.moment)
        assertTrue(second != first)
    }

    @Test
    fun `흔들림은 처음과 끝에 0이고 점점 잦아든다`() {
        assertEquals(0f, shake(0f), 0f)
        assertEquals(0f, shake(1f), 0f)
        val early = (1..9).maxOf { kotlin.math.abs(shake(it / 60f)) }
        val late = (51..59).maxOf { kotlin.math.abs(shake(it / 60f)) }
        assertTrue("처음이 더 크게 흔들린다: $early > $late", early > late)
    }
}
