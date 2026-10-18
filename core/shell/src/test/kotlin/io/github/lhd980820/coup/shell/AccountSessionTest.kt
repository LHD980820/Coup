package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.account.AccountState
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test

class AccountSessionTest {
    @Test
    fun `처음 로그인하면 이메일 앞부분을 닉네임으로 프로필을 만든다`() = fixture { f ->
        val account = f.account("kim")
        assertThat(account.state.value).isEqualTo(AccountState.Loading)
        account.start()
        assertThat(account.state.value).isEqualTo(AccountState.Ready)
        assertThat(account.profile.value!!.nickname).isEqualTo("kim")
        assertThat(account.profile.value!!.rating).isEqualTo(1000)
    }

    @Test
    fun `다시 로그인해도 프로필을 새로 만들지 않는다 - 바꾼 닉네임이 유지된다`() = fixture { f ->
        val first = f.account("kim")
        first.start()
        assertThat(first.updateNickname("김철수")).isNull()
        val again = f.account("kim")
        again.start()
        assertThat(again.profile.value!!.nickname).isEqualTo("김철수")
        // 바꾼 값이 구독으로 반영된다
        first.updateNickname("철수2")
        assertThat(first.profile.first { it?.nickname == "철수2" }).isNotNull()
    }

    @Test
    fun `닉네임 규칙 위반은 쓰지 않고 이유를 돌려준다`() = fixture { f ->
        val account = f.account("kim")
        account.start()
        assertThat(account.updateNickname("a")).isEqualTo(NicknameError.TOO_SHORT)
        assertThat(account.updateNickname("가".repeat(13))).isEqualTo(NicknameError.TOO_LONG)
        assertThat(account.profile.value!!.nickname).isEqualTo("kim")
    }

    @Test
    fun `서버에 닿지 못하면 Failed이고, 다시 start하면 복구된다`() = fixture { f ->
        val flaky = FlakyStore(f.store("kim"))
        val account = AccountSession(f.users("kim", flaky), "kim@test.co", "player", this)
        flaky.fail = true
        account.start()
        assertThat(account.state.value).isEqualTo(AccountState.Failed)
        flaky.fail = false
        account.start()
        assertThat(account.state.value).isEqualTo(AccountState.Ready)
    }

    @Test
    fun `결과 문서가 아직 없으면 있을 때까지 기다렸다가 반영한다`() = fixture { f ->
        // 결과 없이 호출하면 끝내 null(정산 대기는 그대로 남아 다음 start에서 반영)
        val account = f.signedUp("kim")
        assertThat(account.settleGame("g-none", attempts = 3, retryDelayMillis = 5)).isNull()
        assertThat(account.lastSettlement.value).isNull()
    }
}
