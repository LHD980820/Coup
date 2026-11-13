package io.github.lhd980820.coup.game

/**
 * 한국어 조사 고르기. 문자열 리소스는 이름이 무엇일지 몰라 "%s이(가)", "%s을(를)", "%s(으)로"처럼 두 꼴을 함께 적는데,
 * 이름을 끼워 넣은 뒤 앞 글자의 받침을 보고 맞는 꼴 하나만 남긴다("AI 2이(가)" -> "AI 2가", "대사을(를)" -> "대사를", "공작(으)로" -> "공작으로").
 *
 * 받침을 알 수 있는 것은 한글 음절과 숫자(읽는 소리 기준)뿐이다. 로마자 이름처럼 알 수 없으면 두 꼴을 그대로 둔다 — 틀린 조사보다 낫다.
 * 영어 문구에는 이런 표기가 없어 아무것도 바뀌지 않는다.
 */
internal object Josa {
    private enum class Final { NONE, RIEUL, OTHER }

    /** (받침 있을 때, 없을 때). "(으)로"는 ㄹ 받침이면 받침이 없을 때와 같이 "로"다. */
    private val forms = mapOf(
        "이(가)" to ("이" to "가"),
        "을(를)" to ("을" to "를"),
        "은(는)" to ("은" to "는"),
        "과(와)" to ("과" to "와"),
        "와(과)" to ("과" to "와"),
        "(으)로" to ("으로" to "로"),
    )
    private val pattern = Regex("(.)(${forms.keys.joinToString("|") { Regex.escape(it) }})")

    /** 숫자를 한국어로 읽을 때의 받침: 영·일·삼·육·칠·팔은 받침이 있고(일·칠·팔은 ㄹ), 이·사·오·구는 없다. */
    private fun finalOf(c: Char): Final? = when (c) {
        in '가'..'힣' -> when ((c - '가') % 28) {
            0 -> Final.NONE
            8 -> Final.RIEUL
            else -> Final.OTHER
        }
        '1', '7', '8' -> Final.RIEUL
        '0', '3', '6' -> Final.OTHER
        '2', '4', '5', '9' -> Final.NONE
        else -> null
    }

    fun fix(text: String): String = pattern.replace(text) { m ->
        val before = m.groupValues[1][0]
        val marker = m.groupValues[2]
        val final = finalOf(before) ?: return@replace m.value
        val (closed, open) = forms.getValue(marker)
        // "나" + "가"는 "내가"다(이름이 "나"인 싱글플레이의 나). 앞에 다른 글자가 붙은 "…나"는 해당하지 않는다
        if (marker == "이(가)" && before == '나' && (m.range.first == 0 || text[m.range.first - 1].isWhitespace())) return@replace "내가"
        val chosen = when {
            marker == "(으)로" && final == Final.RIEUL -> open
            final == Final.NONE -> open
            else -> closed
        }
        "$before$chosen"
    }
}
