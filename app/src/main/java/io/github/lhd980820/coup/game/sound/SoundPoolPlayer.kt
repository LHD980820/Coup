package io.github.lhd980820.coup.game.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * [SoundSynth]가 합성한 효과음을 SoundPool로 낸다. 소리는 시작할 때 한 번 WAV로 만들어 캐시 폴더에 두고 불러 놓는다(지연이 짧다).
 * 아직 불러오는 중이거나 실패한 소리는 조용히 건너뛴다 — 효과음은 없어도 게임에 지장이 없다.
 * 기기의 미디어 볼륨을 따르며 다른 앱의 소리를 끊지 않는다.
 */
internal class SoundPoolPlayer(context: Context) : SoundPlayer {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids = ConcurrentHashMap<SoundCue, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val dir = File(context.applicationContext.cacheDir, "sfx").apply { mkdirs() }
        thread(name = "sfx-load", isDaemon = true) {
            SoundCue.entries.forEach { cue ->
                runCatching {
                    val file = File(dir, "${cue.name.lowercase()}.wav")
                    file.writeBytes(SoundSynth.toWav(SoundSynth.render(cue)))
                    ids[cue] = pool.load(file.path, 1)
                }
            }
        }
    }

    override fun play(cue: SoundCue, volume: Float) {
        val id = ids[cue] ?: return
        if (id in loaded) pool.play(id, volume, volume, 1, 0, 1f)
    }

    override fun release() {
        pool.release()
    }

    private companion object {
        const val MAX_STREAMS = 4
    }
}
