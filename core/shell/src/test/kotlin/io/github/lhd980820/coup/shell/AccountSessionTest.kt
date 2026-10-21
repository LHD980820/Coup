package io.github.lhd980820.coup.shell

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.shell.account.AccountSession
import io.github.lhd980820.coup.shell.account.AccountState
import io.github.lhd980820.coup.remote.FirestoreGameTransport
import io.github.lhd980820.coup.remote.FirestoreSchema
import io.github.lhd980820.coup.remote.Settlement
import io.github.lhd980820.coup.remote.WriteOp
import io.github.lhd980820.coup.remote.seedStartedRoom
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun `판정을 지켜본다 - 확인 중에서 판정으로, 구독이 끊겨도 다시 붙고, 지우면 멈춘다`() = fixture { f ->
        val flaky = FlakyObserveStore(f.store("kim"))
        val account = f.account("kim", flaky).also { it.start() }
        f.signedUp("lee")
        val roomId = f.fs.seedStartedRoom("g", "kim", listOf("kim", "lee"), rated = true)
        FirestoreGameTransport(f.store("kim")).createGame("g", roomId, "kim", listOf("kim", "lee"), f.classic, rated = true)
        assertThat(account.lastSettlement.value).isNull()

        account.watchSettlement("g", retryDelayMillis = 5)
        assertThat(account.lastSettlement.await { it != null }).isEqualTo(Settlement.Verifying)
        flaky.breakNext = true // 다음 스냅숏에서 구독이 끊긴다 -> 다시 구독한다
        f.fs.admin().write(listOf(WriteOp.Merge(FirestoreSchema.game("g"), buildJsonObject { put(FirestoreSchema.Game.VERDICT, FirestoreSchema.Verdict.REJECTED) })))
        assertThat(account.lastSettlement.await { it?.isFinal == true }).isEqualTo(Settlement.Rejected)

        account.clearSettlement()
        assertThat(account.lastSettlement.value).isNull()
    }
}
