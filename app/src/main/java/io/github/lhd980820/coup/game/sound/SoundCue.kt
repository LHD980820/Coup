package io.github.lhd980820.coup.game.sound

/** 게임 화면이 낼 수 있는 효과음. 어떤 순간에 어느 소리를 낼지는 [SoundCues]가, 소리 자체는 [SoundSynth]가 정한다. */
internal enum class SoundCue {
    /** 내가 행동을 고를 차례가 됐다. */
    YOUR_TURN,

    /** 내가 응답(허용·도전·막기)할 차례가 됐다. */
    RESPOND,

    COIN_GAIN,
    COIN_LOSS,

    /** 카드가 공개됐다(영향력을 잃거나 도전으로 드러났다). */
    CARD_REVEAL,

    /** 누군가 도전했다. */
    CHALLENGE,

    /** 누군가 탈락했다. */
    ELIMINATED,
    WIN,
    LOSE,
}

/** 소리 크기 설정. [volume]은 0~1 배율이고 꺼짐이면 0이다. */
internal enum class SoundLevel(val volume: Float) {
    OFF(0f),
    LOW(0.35f),
    NORMAL(0.8f),
}

/** 소리를 내는 쪽(Android에서는 SoundPool). 화면은 이 인터페이스만 안다. */
internal interface SoundPlayer {
    fun play(cue: SoundCue, volume: Float)
    fun release() {}
}

internal object NoSoundPlayer : SoundPlayer {
    override fun play(cue: SoundCue, volume: Float) = Unit
}
