package io.github.lhd980820.coup.game

import io.github.lhd980820.coup.ui.kit.avatarGlyph
import org.junit.Assert.assertEquals
import org.junit.Test

/** 한국어 조사 고르기: 이름을 끼워 넣은 뒤 앞 글자의 받침에 맞는 꼴 하나만 남긴다. */
class JosaTest {
    @Test
    fun `받침이 있으면 이 을 은 과 으로, 없으면 가 를 는 와 로`() {
        assertEquals("공작이 대사를", Josa.fix("공작이(가) 대사을(를)"))
        assertEquals("대사가 공작을", Josa.fix("대사이(가) 공작을(를)"))
        assertEquals("공작은 대사는", Josa.fix("공작은(는) 대사은(는)"))
        assertEquals("공작과 대사와", Josa.fix("공작과(와) 대사과(와)"))
        assertEquals("공작과 대사와", Josa.fix("공작와(과) 대사와(과)"))
        assertEquals("공작으로 대사로", Josa.fix("공작(으)로 대사(으)로"))
    }

    @Test
    fun `리을 받침 뒤의 으로는 로`() {
        assertEquals("칼로 막기", Josa.fix("칼(으)로 막기"))
        assertEquals("칼이 칼을", Josa.fix("칼이(가) 칼을(를)")) // 다른 조사는 받침 있는 꼴 그대로
    }

    @Test
    fun `숫자로 끝나는 이름은 읽는 소리의 받침을 따른다`() {
        // 일·삼·육·칠·팔·영은 받침이 있고 이·사·오·구는 없다
        assertEquals("AI 1이 AI 2가 AI 3이 AI 4가 AI 5가", Josa.fix("AI 1이(가) AI 2이(가) AI 3이(가) AI 4이(가) AI 5이(가)"))
        assertEquals("6을 7을 8을 9를 0을", Josa.fix("6을(를) 7을(를) 8을(를) 9을(를) 0을(를)"))
        // 일·칠·팔은 ㄹ 받침이라 "로", 삼·육·영은 "으로"
        assertEquals("AI 1로 AI 7로 AI 8로 AI 3으로 AI 6으로 AI 0으로 AI 2로", Josa.fix("AI 1(으)로 AI 7(으)로 AI 8(으)로 AI 3(으)로 AI 6(으)로 AI 0(으)로 AI 2(으)로"))
    }

    @Test
    fun `나 뒤의 가는 내가이고, 다른 낱말의 끝 글자 나는 그대로 가`() {
        assertEquals("내가 도전했습니다", Josa.fix("나이(가) 도전했습니다"))
        assertEquals("AI 1이 내가 아닌", Josa.fix("AI 1이(가) 나이(가) 아닌"))
        assertEquals("하나가", Josa.fix("하나이(가)"))
        assertEquals("나를 나는 나로", Josa.fix("나을(를) 나은(는) 나(으)로"))
    }

    @Test
    fun `받침을 알 수 없는 이름은 두 꼴을 그대로 둔다`() {
        assertEquals("Bob이(가) Bob을(를) Bob(으)로", Josa.fix("Bob이(가) Bob을(를) Bob(으)로"))
        assertEquals("★이(가)", Josa.fix("★이(가)"))
    }

    @Test
    fun `표기가 없는 문장과 영어 문장은 바뀌지 않는다`() {
        assertEquals("내 차례입니다. 행동을 고르세요.", Josa.fix("내 차례입니다. 행동을 고르세요."))
        assertEquals("AI 1 claims Duke (again)", Josa.fix("AI 1 claims Duke (again)"))
        assertEquals("", Josa.fix(""))
        assertEquals("이(가)", Josa.fix("이(가)")) // 앞 글자가 없으면 건드리지 않는다
    }

    @Test
    fun `아바타 글자는 이름이 숫자로 끝나면 그 숫자, 아니면 첫 글자`() {
        assertEquals("3", avatarGlyph("AI 3"))
        assertEquals("나", avatarGlyph("나"))
        assertEquals("B", avatarGlyph("bob"))
        assertEquals("B", avatarGlyph("  bob  "))
        assertEquals("?", avatarGlyph(""))
        assertEquals("?", avatarGlyph("   "))
    }
}
