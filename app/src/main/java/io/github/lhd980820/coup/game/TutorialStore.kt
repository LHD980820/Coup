package io.github.lhd980820.coup.game

import android.content.Context
import androidx.core.content.edit

/** 끝낸 튜토리얼 과정 기록. 기기에만 저장한다(계정과 무관). */
internal interface TutorialStore {
    fun completed(): Set<String>
    fun markCompleted(lessonId: String)
}

internal class InMemoryTutorialStore(initial: Set<String> = emptySet()) : TutorialStore {
    private var done = initial
    override fun completed(): Set<String> = done
    override fun markCompleted(lessonId: String) {
        done = done + lessonId
    }
}

internal class PrefsTutorialStore(context: Context) : TutorialStore {
    private val prefs = context.applicationContext.getSharedPreferences("tutorial", Context.MODE_PRIVATE)
    override fun completed(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
    override fun markCompleted(lessonId: String) {
        prefs.edit { putStringSet(KEY, completed() + lessonId) }
    }

    private companion object {
        const val KEY = "completed_lessons"
    }
}
