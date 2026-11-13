package io.github.lhd980820.coup.game

import android.content.res.Resources
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.event.ActionOutcome
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.engine.model.WindowKind
import io.github.lhd980820.coup.presentation.Banner
import io.github.lhd980820.coup.presentation.GameUiState
import io.github.lhd980820.coup.presentation.LogEntry

/**
 * [GameUiState]의 구조화된 값 -> 사용자에게 보이는 문장. 화면과 분리해서 두어 JVM에서 번역 키 누락을 확인할 수 있다.
 */
internal class GameTexts(private val res: Resources, state: GameUiState) {
    private val names: Map<PlayerId, String> =
        (state.opponents.associate { it.id to it.name } + listOfNotNull(state.me).associate { it.id to it.name })

    fun name(id: PlayerId): String = names[id] ?: id.value

    /** 문자열을 만들고 한국어 조사를 앞 글자에 맞춘다([Josa]). */
    private fun str(id: Int, vararg args: Any): String = Josa.fix(res.getString(id, *args))

    fun role(role: RoleId): String = str(RoleUiCatalog.of(role).name)

    fun roles(roles: Set<RoleId>): String = roles.joinToString("/") { role(it) }

    fun actionName(action: ActionId): String = ActionUiCatalog.of(action)?.let { str(it.name) } ?: action.value

    /** "{행동}을 한다"는 서술(대상이 필요하면 이름을 끼워 넣는다). */
    fun actionDescription(action: ActionId, target: PlayerId?): String {
        val entry = ActionUiCatalog.of(action) ?: return action.value
        return if (target != null) str(entry.description, name(target)) else str(entry.description)
    }

    private fun declared(actor: PlayerId, action: ActionId, target: PlayerId?, claims: Set<RoleId>): String {
        val base = str(R.string.game_banner_action, name(actor), actionDescription(action, target))
        return if (claims.isEmpty()) base else base + " " + str(R.string.game_banner_claim, roles(claims))
    }

    fun banner(banner: Banner): String = when (banner) {
        is Banner.YourTurn -> str(if (banner.forced) R.string.game_your_turn_forced else R.string.game_your_turn)
        is Banner.WaitingFor -> str(R.string.game_waiting_for, name(banner.actor))
        is Banner.ActionDeclared -> declared(banner.actor, banner.actionId, banner.target, banner.claimedRoles)
        is Banner.BlockDeclared ->
            str(R.string.game_banner_block, name(banner.blocker), role(banner.role), name(banner.actor), actionName(banner.actionId))
        is Banner.BlockWindow ->
            str(R.string.game_banner_block_window, name(banner.actor), actionName(banner.actionId))
        is Banner.Challenged -> str(
            if (banner.againstBlock) R.string.game_banner_challenged_block else R.string.game_banner_challenged,
            name(banner.challenger),
            name(banner.challenged),
            roles(banner.claimedRoles),
        )
        is Banner.LosingInfluence -> str(R.string.game_banner_losing, name(banner.player))
        is Banner.Exchanging -> str(R.string.game_banner_exchanging, name(banner.player))
        is Banner.GameOver -> str(R.string.game_banner_game_over, name(banner.winner))
    }

    /**
     * 응답 시트의 머리말: 지금 내가 응답해야 하는 일. 주장하는 역할은 시트가 따로(색·카드와 함께) 보여 주므로 뺀다.
     * 진행 배너가 응답 창과 맞지 않으면(있을 수 없지만) 일반 질문으로 돌아간다.
     */
    fun respondHeadline(banner: Banner, actor: PlayerId, action: ActionId, windowKind: WindowKind): String = when (banner) {
        is Banner.ActionDeclared -> str(R.string.game_banner_action, name(banner.actor), actionDescription(banner.actionId, banner.target))
        is Banner.BlockDeclared, is Banner.BlockWindow -> banner(banner)
        else -> respondPrompt(actor, action, windowKind)
    }

    fun respondPrompt(actor: PlayerId, action: ActionId, windowKind: WindowKind): String =
        str(R.string.game_respond_prompt, name(actor), actionName(action))

    /** 노이즈를 줄이려고 일부 항목(교환 상세 등)은 null. */
    fun log(entry: LogEntry): String? = when (entry) {
        is LogEntry.ActionDeclared -> declared(entry.actor, entry.actionId, entry.target, entry.claimedRoles)
        is LogEntry.Blocked -> str(R.string.game_log_blocked, name(entry.blocker), role(entry.role), actionName(entry.actionId))
        is LogEntry.Challenged -> str(R.string.game_log_challenge, name(entry.challenger), name(entry.challenged))
        is LogEntry.Revealed ->
            str(if (entry.proven) R.string.game_log_revealed_ok else R.string.game_log_revealed_fail, name(entry.player), role(entry.role))
        is LogEntry.InfluenceLost -> str(R.string.game_log_lost, name(entry.player), role(entry.role))
        is LogEntry.CardReplaced -> str(R.string.game_log_replaced, name(entry.player))
        is LogEntry.ExchangeDone -> str(R.string.game_log_exchanged, name(entry.player))
        is LogEntry.Resolved -> when (entry.outcome) {
            ActionOutcome.SUCCESS -> null // 선언 줄로 충분하다
            ActionOutcome.BLOCKED -> str(R.string.game_log_resolved, actionName(entry.actionId), str(R.string.game_outcome_blocked))
            else -> str(R.string.game_log_resolved, actionName(entry.actionId), str(R.string.game_outcome_failed))
        }
        is LogEntry.Conceded -> str(R.string.game_log_conceded, name(entry.player))
        is LogEntry.Eliminated -> str(R.string.game_log_eliminated, name(entry.player))
        is LogEntry.GameEnded -> str(R.string.game_log_ended, name(entry.winner))
    }
}
