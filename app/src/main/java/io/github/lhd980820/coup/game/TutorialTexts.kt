package io.github.lhd980820.coup.game

import androidx.annotation.StringRes
import io.github.lhd980820.coup.R

/**
 * 코어의 튜토리얼은 문구를 키로만 갖는다(번역은 앱 몫). 키 -> 문자열 리소스. 코어에 새 키가 생겼는데 여기 없으면
 * 화면은 키를 그대로 보여주고, 테스트(`TutorialTextsTest`)가 모든 과정의 키가 있는지 확인한다.
 */
internal object TutorialTexts {
    private val map: Map<String, Int> = mapOf(
        "tutorial_basics_title" to R.string.tutorial_basics_title,
        "tutorial_basics_income" to R.string.tutorial_basics_income,
        "tutorial_basics_foreign_aid" to R.string.tutorial_basics_foreign_aid,
        "tutorial_basics_done" to R.string.tutorial_basics_done,
        "tutorial_claim_title" to R.string.tutorial_claim_title,
        "tutorial_claim_tax" to R.string.tutorial_claim_tax,
        "tutorial_claim_done" to R.string.tutorial_claim_done,
        "tutorial_challenge_title" to R.string.tutorial_challenge_title,
        "tutorial_challenge_challenge" to R.string.tutorial_challenge_challenge,
        "tutorial_challenge_done" to R.string.tutorial_challenge_done,
        "tutorial_block_title" to R.string.tutorial_block_title,
        "tutorial_block_block" to R.string.tutorial_block_block,
        "tutorial_block_done" to R.string.tutorial_block_done,
    )

    @StringRes
    fun res(key: String): Int? = map[key]

    fun has(key: String): Boolean = key in map
}
