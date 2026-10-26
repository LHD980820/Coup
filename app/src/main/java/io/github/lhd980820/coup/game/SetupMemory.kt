package io.github.lhd980820.coup.game

import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.presentation.rules.RuleSelection

/** 싱글플레이 설정 화면에서 마지막으로 시작한 선택. 다음에 설정 화면을 열면 그대로 채워 둔다. */
internal data class SetupChoice(val opponents: Int, val difficulty: AiDifficulty, val rules: RuleSelection) {
    /** 기기에 저장하는 한 줄 문자열. */
    fun encode(): String = buildList {
        add("v1")
        add("opponents=$opponents")
        add("difficulty=${difficulty.name}")
        rules.houseRules.sorted().forEach { add("h:$it") }
        rules.params.entries.sortedBy { it.key }.forEach { add("p:${it.key}=${it.value}") }
    }.joinToString("|")

    companion object {
        const val MIN_OPPONENTS = 1
        const val MAX_OPPONENTS = 5

        /** 저장된 문자열을 읽는다. 알 수 없는 형식이면 null, 값이 범위 밖이거나 모르는 난이도는 안전한 값으로 맞춘다. */
        fun decode(text: String?): SetupChoice? {
            val parts = text?.split('|') ?: return null
            if (parts.firstOrNull() != "v1") return null
            val opponents = parts.firstOrNull { it.startsWith("opponents=") }?.substringAfter('=')?.toIntOrNull() ?: return null
            val difficulty = AiDifficulty.entries.firstOrNull { it.name == parts.firstOrNull { p -> p.startsWith("difficulty=") }?.substringAfter('=') }
                ?: AiDifficulty.NORMAL
            val houses = parts.filter { it.startsWith("h:") }.map { it.removePrefix("h:") }.toSet()
            val params = parts.filter { it.startsWith("p:") && '=' in it }.associate { it.removePrefix("p:").substringBefore('=') to it.substringAfter('=') }
            return SetupChoice(opponents.coerceIn(MIN_OPPONENTS, MAX_OPPONENTS), difficulty, RuleSelection(houses, params))
        }
    }
}
