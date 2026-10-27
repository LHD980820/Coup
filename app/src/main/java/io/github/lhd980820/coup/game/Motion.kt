package io.github.lhd980820.coup.game

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import io.github.lhd980820.coup.game.sound.SoundLevel

/** 애니메이션 속도(설계 §10.3: 설정에서 속도 조절). 꺼짐이면 모든 연출이 즉시 끝난다. */
internal enum class MotionSpeed(val scale: Float) {
    OFF(0f),
    FAST(0.5f),
    NORMAL(1f),
}

/**
 * 화면 연출의 길이를 정하는 값. 기본 길이(밀리초)에 속도 배율을 곱한다.
 * [LocalMotion]의 기본값은 꺼짐이다 — 미리보기와 자동 테스트는 움직임 없이 결정적으로 그려지고, 앱은 설정에서 켠다.
 */
internal class Motion(val speed: MotionSpeed) {
    val enabled: Boolean get() = speed != MotionSpeed.OFF
    fun ms(base: Int): Int = (base * speed.scale).toInt()

    companion object {
        val Off = Motion(MotionSpeed.OFF)
    }
}

internal val LocalMotion = staticCompositionLocalOf { Motion.Off }

/** 기기의 "애니메이션 없음"(전환 애니메이션 배율 0)이 켜져 있으면 고른 속도와 상관없이 끈다 — 움직임에 민감한 사용자를 지킨다. */
internal fun effectiveSpeed(chosen: MotionSpeed, systemAnimatorScale: Float): MotionSpeed =
    if (systemAnimatorScale <= 0f) MotionSpeed.OFF else chosen

/** 화면에 줄 연출 값: 설정이 없으면 꺼짐, 있으면 고른 속도(기기의 애니메이션 없음이 켜져 있으면 꺼짐). */
internal fun motionFor(settings: AppSettings?, systemAnimatorScale: Float): Motion =
    if (settings == null) Motion.Off else Motion(effectiveSpeed(settings.motion, systemAnimatorScale))

/** 기기에 저장하는 앱 설정. 계정과 무관하다. */
internal interface SettingsStore {
    fun motion(): MotionSpeed
    fun setMotion(speed: MotionSpeed)

    /** 효과음 크기(없거나 알 수 없는 값이면 보통). */
    fun sound(): SoundLevel
    fun setSound(level: SoundLevel)

    /** 싱글플레이 설정 화면에서 마지막으로 시작한 선택(없으면 null). */
    fun lastSetup(): SetupChoice?
    fun setLastSetup(choice: SetupChoice)
}

internal class InMemorySettingsStore(
    private var speed: MotionSpeed = MotionSpeed.NORMAL,
    private var setup: SetupChoice? = null,
    private var soundLevel: SoundLevel = SoundLevel.NORMAL,
) : SettingsStore {
    override fun motion() = speed
    override fun setMotion(speed: MotionSpeed) {
        this.speed = speed
    }

    override fun sound() = soundLevel
    override fun setSound(level: SoundLevel) {
        soundLevel = level
    }

    override fun lastSetup() = setup
    override fun setLastSetup(choice: SetupChoice) {
        setup = choice
    }
}

internal class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 저장된 값이 없거나 알 수 없는 값(버전이 달라졌을 때)이면 기본인 보통. */
    override fun motion(): MotionSpeed = MotionSpeed.entries.firstOrNull { it.name == prefs.getString(KEY_MOTION, null) } ?: MotionSpeed.NORMAL
    override fun setMotion(speed: MotionSpeed) {
        prefs.edit { putString(KEY_MOTION, speed.name) }
    }

    override fun sound(): SoundLevel = SoundLevel.entries.firstOrNull { it.name == prefs.getString(KEY_SOUND, null) } ?: SoundLevel.NORMAL
    override fun setSound(level: SoundLevel) {
        prefs.edit { putString(KEY_SOUND, level.name) }
    }

    override fun lastSetup(): SetupChoice? = SetupChoice.decode(prefs.getString(KEY_SETUP, null))
    override fun setLastSetup(choice: SetupChoice) {
        prefs.edit { putString(KEY_SETUP, choice.encode()) }
    }

    private companion object {
        const val KEY_MOTION = "motion"
        const val KEY_SETUP = "single_setup"
        const val KEY_SOUND = "sound"
    }
}

/** 화면이 관찰하는 앱 설정. 바꾸면 곧바로 저장한다. */
internal class AppSettings(private val store: SettingsStore) {
    var motion by mutableStateOf(store.motion())
        private set

    /** 효과음 크기. 바꾸면 바로 적용되고 저장된다. */
    var sound by mutableStateOf(store.sound())
        private set

    fun chooseSound(level: SoundLevel) {
        sound = level
        store.setSound(level)
    }

    /** 마지막으로 시작한 싱글플레이 설정. 설정 화면이 다시 열릴 때 채워 둔다. */
    var lastSetup by mutableStateOf(store.lastSetup())
        private set

    fun rememberSetup(choice: SetupChoice) {
        lastSetup = choice
        store.setLastSetup(choice)
    }

    fun chooseMotion(speed: MotionSpeed) {
        motion = speed
        store.setMotion(speed)
    }
}
