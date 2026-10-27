package io.github.lhd980820.coup.game.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 효과음을 코드로 합성한다 — 소리 파일(과 그 라이선스)이 필요 없고, 결과가 결정적이라 JVM에서 검증할 수 있다.
 * 16비트 모노 PCM. 모든 소리는 짧은 시작 페이드와 끝 페이드가 있어 딱 소리가 나지 않는다.
 */
internal object SoundSynth {
    const val SAMPLE_RATE = 22_050

    /** 가장 큰 진폭(0~1). 여러 소리가 겹쳐도 깨지지 않도록 여유를 둔다. */
    const val PEAK = 0.6

    private const val FADE_IN_MS = 4
    private const val FADE_OUT_MS = 12

    /** 한 음: 주파수(Hz)와 길이(ms). */
    private class Note(val hz: Double, val ms: Int)

    fun render(cue: SoundCue): ShortArray = when (cue) {
        // 부드러운 두 음 상승 — "내 차례"
        SoundCue.YOUR_TURN -> notes(Note(660.0, 110), Note(880.0, 160))
        // 한 음 알림 — "응답하세요"
        SoundCue.RESPOND -> notes(Note(740.0, 140))
        SoundCue.COIN_GAIN -> notes(Note(988.0, 55), Note(1319.0, 90))
        SoundCue.COIN_LOSS -> notes(Note(659.0, 55), Note(440.0, 90))
        SoundCue.CARD_REVEAL -> noiseBurst(durationMs = 90, decayPerMs = 0.05)
        SoundCue.CHALLENGE -> buzz(hz = 180.0, durationMs = 190)
        SoundCue.ELIMINATED -> sweep(fromHz = 440.0, toHz = 110.0, durationMs = 420)
        SoundCue.WIN -> notes(Note(523.0, 110), Note(659.0, 110), Note(784.0, 110), Note(1047.0, 260))
        SoundCue.LOSE -> notes(Note(392.0, 150), Note(330.0, 150), Note(262.0, 300))
    }

    /** 소리의 길이(밀리초). */
    fun durationMs(cue: SoundCue): Int = render(cue).size * 1000 / SAMPLE_RATE

    private fun samples(ms: Int) = SAMPLE_RATE * ms / 1000

    /** 음들을 이어 붙인다. 각 음은 바로 사그라드는 사인파다. */
    private fun notes(vararg notes: Note): ShortArray {
        val out = ArrayList<Short>()
        notes.forEach { note ->
            val n = samples(note.ms)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val decay = exp(-3.5 * i / n) // 길이 끝에서 약 3%
                out += pcm(sin(2 * PI * note.hz * t) * decay * envelope(i, n))
            }
        }
        return out.toShortArray()
    }

    /** 주파수가 변하는 사인파(탈락). */
    private fun sweep(fromHz: Double, toHz: Double, durationMs: Int): ShortArray {
        val n = samples(durationMs)
        var phase = 0.0
        return ShortArray(n) { i ->
            val hz = fromHz + (toHz - fromHz) * i / n
            phase += 2 * PI * hz / SAMPLE_RATE
            pcm(sin(phase) * (1.0 - 0.6 * i / n) * envelope(i, n))
        }
    }

    /** 낮고 거친 소리(도전): 사인파에 배음을 섞어 사각파에 가깝게. */
    private fun buzz(hz: Double, durationMs: Int): ShortArray {
        val n = samples(durationMs)
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val wave = sin(2 * PI * hz * t) + 0.5 * sin(2 * PI * hz * 3 * t) + 0.25 * sin(2 * PI * hz * 5 * t)
            pcm(wave / 1.75 * envelope(i, n))
        }
    }

    /** 짧은 잡음(카드 넘기는 소리). 같은 값이 나오도록 고정된 난수열을 쓴다. */
    private fun noiseBurst(durationMs: Int, decayPerMs: Double): ShortArray {
        val n = samples(durationMs)
        var seed = 0x2545F491L
        var previous = 0.0
        return ShortArray(n) { i ->
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val white = ((seed ushr 33).toInt() and 0xFFFF) / 32768.0 - 1.0
            previous = previous * 0.55 + white * 0.45 // 높은 소리를 눌러 종이 소리처럼
            val ms = i * 1000.0 / SAMPLE_RATE
            pcm(previous * exp(-decayPerMs * ms) * envelope(i, n))
        }
    }

    /** 시작과 끝을 짧게 키우고 줄여 딱 소리를 막는다. */
    private fun envelope(i: Int, n: Int): Double {
        val fadeIn = samples(FADE_IN_MS).coerceAtLeast(1)
        val fadeOut = samples(FADE_OUT_MS).coerceAtLeast(1)
        val inGain = if (i < fadeIn) i.toDouble() / fadeIn else 1.0
        val outGain = if (i >= n - fadeOut) (n - 1 - i).coerceAtLeast(0).toDouble() / fadeOut else 1.0
        return inGain * outGain
    }

    private fun pcm(value: Double): Short = (value.coerceIn(-1.0, 1.0) * PEAK * Short.MAX_VALUE).toInt().toShort()

    /** 16비트 모노 PCM을 WAV 파일 바이트로 감싼다(SoundPool이 파일에서 읽는다). */
    fun toWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun int(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xFF) }
        fun short(v: Int) = repeat(2) { out.write((v ushr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray())
        int(36 + dataSize)
        out.write("WAVEfmt ".toByteArray())
        int(16)
        short(1) // PCM
        short(1) // 모노
        int(SAMPLE_RATE)
        int(SAMPLE_RATE * 2) // 초당 바이트
        short(2) // 블록 크기
        short(16) // 비트 수
        out.write("data".toByteArray())
        int(dataSize)
        pcm.forEach { short(it.toInt()) }
        return out.toByteArray()
    }
}
