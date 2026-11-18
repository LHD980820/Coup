package io.github.lhd980820.coup.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.presentation.UiEffect

/**
 * 극적인 순간(docs/DESIGN.md "극적인 순간"): 판을 흔드는 사건을 화면 가운데에 잠깐 크게 알린다.
 * 상태는 이미 바뀌어 있고(기록·자리·무대에도 나온다), 이것은 그 위에 덧입히는 연출이다 — 연출이 꺼져 있으면 아무것도 하지 않는다.
 */
internal sealed interface Moment {
    /** 여러 개가 한꺼번에 밀려왔을 때 무엇을 남길지. 클수록 중요하다. */
    val weight: Int

    /** 보통 속도에서 나타나서 사라질 때까지의 시간(ms). */
    val totalMillis: Int

    /** 자동 테스트가 지금 무엇이 보이는지 확인하는 이름. */
    val kind: String

    /** 누군가 [challenged]의 주장에 도전했다. 결과는 뒤따르는 [Reveal]이 알린다. */
    data class Challenge(val challenger: PlayerId, val challenged: PlayerId, val claimed: Set<RoleId>) : Moment {
        override val weight get() = 2
        override val totalMillis get() = 1_150
        override val kind get() = "challenge"
    }

    /**
     * 도전받은 [player]가 [revealed]를 뒤집었다. [proven]이면 주장이 진짜였고 도전한 [challenger]가 카드를 잃는다.
     * 아니면 블러핑이 들통나 뒤집은 카드를 잃는다. [claimed]는 도전받은 주장(도전을 놓쳤으면 비어 있다).
     */
    data class Reveal(val player: PlayerId, val revealed: RoleId, val proven: Boolean, val claimed: Set<RoleId>, val challenger: PlayerId?) : Moment {
        override val weight get() = 3
        override val totalMillis get() = 2_000
        override val kind get() = if (proven) "proven" else "bluff"
    }

    data class Block(val blocker: PlayerId, val role: RoleId, val action: ActionId) : Moment {
        override val weight get() = 1
        override val totalMillis get() = 1_150
        override val kind get() = "block"
    }

    /** 쿠: 막을 수도 도전할 수도 없는 일격. */
    data class Coup(val actor: PlayerId, val target: PlayerId?) : Moment {
        override val weight get() = 2
        override val totalMillis get() = 1_250
        override val kind get() = "coup"
    }

    data class Eliminated(val player: PlayerId, val isMe: Boolean) : Moment {
        override val weight get() = 3
        override val totalMillis get() = 1_600
        override val kind get() = if (isMe) "eliminated_me" else "eliminated"
    }
}

/**
 * 연출 신호([UiEffect])를 극적인 순간으로 바꾼다. 도전과 그 결과는 따로 오므로, 결과(카드 공개)에 붙일 도전 내용을 기억해 둔다.
 * 판을 흔들지 않는 신호(코인, 허용, 평범한 행동 선언)는 순간이 아니다.
 */
internal class MomentDirector(private val me: () -> PlayerId?) {
    private var challenge: UiEffect.Challenged? = null

    fun moment(effect: UiEffect): Moment? = when (effect) {
        is UiEffect.Challenged -> {
            challenge = effect
            Moment.Challenge(effect.challenger, effect.challenged, effect.claimedRoles)
        }
        is UiEffect.CardRevealed -> {
            // 카드 공개는 도전의 결과로만 일어난다. 그 도전을 못 봤으면(화면이 나중에 열렸으면) 주장과 도전자를 모른 채로 알린다
            val context = challenge?.takeIf { it.challenged == effect.player }
            challenge = null
            Moment.Reveal(effect.player, effect.role, effect.proven, context?.claimedRoles.orEmpty(), context?.challenger)
        }
        is UiEffect.BlockDeclared -> Moment.Block(effect.blocker, effect.role, effect.actionId)
        is UiEffect.ActionDeclared -> if (effect.actionId.value == COUP) Moment.Coup(effect.actor, effect.target) else null
        is UiEffect.Eliminated -> Moment.Eliminated(effect.player, isMe = effect.player == me())
        else -> null
    }

    private companion object {
        const val COUP = "coup"
    }
}

/**
 * 보여 줄 순간의 대기열. 한꺼번에 많이 밀려오면(빠른 AI끼리의 공방, 다시 연결된 직후) 연출이 판보다 한참 뒤처지지 않게
 * [capacity]개만 남긴다 — 넘치면 가장 가벼운 것(무게가 같으면 오래된 것)을 버린다.
 */
internal class MomentQueue(private val capacity: Int = DEFAULT_CAPACITY) {
    private val items = ArrayDeque<Moment>()

    val size: Int get() = items.size
    val pending: List<Moment> get() = items.toList()

    fun offer(moment: Moment) {
        items.addLast(moment)
        while (items.size > capacity) {
            val lightest = items.indices.minBy { items[it].weight } // 같은 무게면 앞(오래된 것)이 먼저 뽑힌다
            items.removeAt(lightest)
        }
    }

    fun poll(): Moment? = items.removeFirstOrNull()

    companion object {
        const val DEFAULT_CAPACITY = 2
    }
}

/** 지금 보이는 순간. 같은 순간이 연달아 와도 다시 재생되도록 순번을 붙인다. */
internal data class Played(val seq: Int, val moment: Moment)

/**
 * 순간을 하나씩 무대에 올린다. 화면(오버레이)이 [playing]을 그리고, 다 그리면 [finished]로 알려 다음 것을 올린다.
 * [idle]은 결과 화면을 띄우기 전에 마지막 순간(마지막 탈락 등)이 다 지나갔는지 보는 데 쓴다.
 */
@Stable
internal class MomentStage(capacity: Int = MomentQueue.DEFAULT_CAPACITY) {
    private val queue = MomentQueue(capacity)
    private var seq = 0
    private var waiting by mutableIntStateOf(0)

    var playing by mutableStateOf<Played?>(null)
        private set

    val idle: Boolean get() = playing == null && waiting == 0

    fun offer(moment: Moment) {
        queue.offer(moment)
        waiting = queue.size
        if (playing == null) advance()
    }

    fun finished(played: Played) {
        if (playing == played) advance()
    }

    private fun advance() {
        val next = queue.poll()
        waiting = queue.size
        playing = next?.let { Played(++seq, it) }
    }
}
