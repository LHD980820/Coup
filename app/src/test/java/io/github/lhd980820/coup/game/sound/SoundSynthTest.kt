package io.github.lhd980820.coup.game.sound

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 합성한 효과음: 길이·크기·시작과 끝의 부드러움·음높이의 방향을 파형에서 직접 확인한다(소리를 들어 보지 않고도). */
class SoundSynthTest {
    private val rate = SoundSynth.SAMPLE_RATE

    private fun ms(m: Int) = rate * m / 1000

    /** [fromMs, toMs) 구간의 영점 통과 횟수. 사인파면 2 × 주파수 × 구간 길이(초)에 가깝다. */
    private fun crossings(pcm: ShortArray, fromMs: Int, toMs: Int): Int {
        var n = 0
        for (i in ms(fromMs) + 1 until ms(toMs)) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) n++
        return n
    }

    private fun peak(pcm: ShortArray, fromMs: Int, toMs: Int) = (ms(fromMs) until minOf(ms(toMs), pcm.size)).maxOf { abs(pcm[it].toInt()) }

    @Test
    fun `모든 효과음은 짧고 비어 있지 않으며 정해진 크기를 넘지 않는다`() {
        SoundCue.entries.forEach { cue ->
            val pcm = SoundSynth.render(cue)
            val duration = SoundSynth.durationMs(cue)
            assertTrue("$cue 길이 ${duration}ms", duration in 50..700)
            val max = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$cue 최대 $max", max > 3_000)
            assertTrue("$cue 너무 크다: $max", max <= (SoundSynth.PEAK * Short.MAX_VALUE).toInt() + 1)
        }
    }

    @Test
    fun `최대 진폭은 여러 소리가 겹쳐도 깨지지 않도록 여유를 둔다`() {
        assertTrue(SoundSynth.PEAK in 0.2..0.7)
        SoundCue.entries.forEach { assertTrue(SoundSynth.render(it).maxOf { s -> abs(s.toInt()) } <= (0.7 * Short.MAX_VALUE).toInt()) }
    }

    @Test
    fun `시작과 끝이 부드러워 딱 소리가 나지 않는다`() {
        SoundCue.entries.forEach { cue ->
            val pcm = SoundSynth.render(cue)
            assertTrue("$cue 시작", abs(pcm.first().toInt()) < 800)
            assertTrue("$cue 끝", abs(pcm.last().toInt()) < 800)
        }
    }

    @Test
    fun `같은 효과음은 언제나 똑같이 합성되고, 효과음끼리는 서로 다르다`() {
        SoundCue.entries.forEach { assertArrayEquals(SoundSynth.render(it).toList().toShortArray(), SoundSynth.render(it)) }
        val all = SoundCue.entries.map { SoundSynth.render(it).toList() }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `내 차례 소리는 낮은 음에서 높은 음으로 올라간다`() {
        val pcm = SoundSynth.render(SoundCue.YOUR_TURN)
        val first = crossings(pcm, 20, 100) // 660Hz 음
        val second = crossings(pcm, 130, 250) // 880Hz 음
        assertEquals(2 * 660 * 0.080, first.toDouble(), 6.0)
        assertEquals(2 * 880 * 0.120, second.toDouble(), 8.0)
    }

    @Test
    fun `응답 소리는 한 음이고 내 차례 소리보다 구별된다`() {
        val pcm = SoundSynth.render(SoundCue.RESPOND)
        assertEquals(2 * 740 * 0.080, crossings(pcm, 20, 100).toDouble(), 6.0)
        assertTrue(SoundSynth.durationMs(SoundCue.RESPOND) < SoundSynth.durationMs(SoundCue.YOUR_TURN))
    }

    @Test
    fun `코인을 얻는 소리는 올라가고 잃는 소리는 내려간다`() {
        val gain = SoundSynth.render(SoundCue.COIN_GAIN)
        val loss = SoundSynth.render(SoundCue.COIN_LOSS)
        // 같은 길이의 두 음 구간에서 영점 통과 수를 비교한다(첫 음 10~50ms, 둘째 음 70~140ms를 1ms당으로)
        fun rate(pcm: ShortArray, from: Int, to: Int) = crossings(pcm, from, to).toDouble() / (to - from)
        assertTrue(rate(gain, 70, 140) > rate(gain, 10, 50) * 1.2)
        assertTrue(rate(loss, 70, 140) < rate(loss, 10, 50) * 0.8)
    }

    @Test
    fun `탈락 소리는 계속 내려가고 음 하나씩 사그라진다`() {
        val pcm = SoundSynth.render(SoundCue.ELIMINATED)
        fun rate(from: Int, to: Int) = crossings(pcm, from, to).toDouble() / (to - from)
        assertTrue(rate(20, 100) > rate(160, 240))
        assertTrue(rate(160, 240) > rate(300, 400))
        assertTrue(peak(pcm, 0, 100) > peak(pcm, 320, 420))
    }

    @Test
    fun `승리 소리는 네 음이 차례로 올라가고 패배 소리는 세 음이 내려간다`() {
        val win = SoundSynth.render(SoundCue.WIN)
        val notes = listOf(20 to 100, 130 to 210, 240 to 320, 360 to 560).map { (a, b) -> crossings(win, a, b).toDouble() / (b - a) }
        assertTrue(notes.zipWithNext().all { (a, b) -> b > a })
        val lose = SoundSynth.render(SoundCue.LOSE)
        val down = listOf(20 to 140, 180 to 290, 330 to 580).map { (a, b) -> crossings(lose, a, b).toDouble() / (b - a) }
        assertTrue(down.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `각 음은 바로 사그라진다`() {
        val pcm = SoundSynth.render(SoundCue.YOUR_TURN)
        assertTrue(peak(pcm, 10, 40) > peak(pcm, 70, 100))
    }

    @Test
    fun `카드 소리는 음높이가 없는 짧은 잡음이다`() {
        val pcm = SoundSynth.render(SoundCue.CARD_REVEAL)
        // 잡음이면 영점 통과가 아주 많고(음 하나의 사인파보다) 불규칙하다
        assertTrue(crossings(pcm, 5, 60) > 2 * 1500 * 0.055 * 0.5)
        assertTrue(peak(pcm, 5, 30) > peak(pcm, 60, 85))
    }

    @Test
    fun `WAV 파일은 올바른 머리말과 같은 소리 데이터를 담는다`() {
        val pcm = SoundSynth.render(SoundCue.COIN_GAIN)
        val wav = SoundSynth.toWav(pcm)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        fun int(at: Int) = (0 until 4).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }
        fun short(at: Int) = (0 until 2).sumOf { (wav[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(36 + pcm.size * 2, int(4))
        assertEquals(1, short(20)) // PCM
        assertEquals(1, short(22)) // 모노
        assertEquals(SoundSynth.SAMPLE_RATE, int(24))
        assertEquals(SoundSynth.SAMPLE_RATE * 2, int(28))
        assertEquals(16, short(34))
        assertEquals(pcm.size * 2, int(40))
        // 데이터는 리틀 엔디언 16비트로 그대로
        val i = pcm.indexOfFirst { abs(it.toInt()) > 1_000 }
        assertEquals(pcm[i].toInt(), (short(44 + 2 * i) shl 16) shr 16)
        assertFalse(wav.all { it == 0.toByte() })
    }

    @Test
    fun `소리 크기 설정은 꺼짐이 0이고 작게가 보통보다 작다`() {
        assertEquals(0f, SoundLevel.OFF.volume, 0f)
        assertTrue(SoundLevel.LOW.volume > 0f && SoundLevel.LOW.volume < SoundLevel.NORMAL.volume && SoundLevel.NORMAL.volume <= 1f)
    }
}
