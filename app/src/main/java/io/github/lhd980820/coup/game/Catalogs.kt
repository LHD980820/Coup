package io.github.lhd980820.coup.game

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.lhd980820.coup.ui.kit.CoupIcons
import androidx.annotation.StringRes
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

/**
 * 역할 ID -> 표시 이름·능력 설명. 엔진은 표시 문자열을 갖지 않는다(설계 §10.6). 모르는 역할은 제네릭 카드로 폴백.
 * 카드의 모습(색·문양·초상)은 [io.github.lhd980820.coup.ui.kit.RoleVisuals]가 같은 ID로 갖는다.
 */
internal object RoleUiCatalog {
    data class Entry(@StringRes val name: Int, @StringRes val ability: Int)

    private val entries = mapOf(
        "duke" to Entry(R.string.game_role_duke, R.string.game_role_ability_duke),
        "assassin" to Entry(R.string.game_role_assassin, R.string.game_role_ability_assassin),
        "captain" to Entry(R.string.game_role_captain, R.string.game_role_ability_captain),
        "ambassador" to Entry(R.string.game_role_ambassador, R.string.game_role_ability_ambassador),
        "contessa" to Entry(R.string.game_role_contessa, R.string.game_role_ability_contessa),
    )
    private val unknown = Entry(R.string.game_role_unknown, R.string.game_role_ability_unknown)

    fun of(role: RoleId): Entry = entries[role.value] ?: unknown
}

/** 행동 ID -> 버튼 이름과 "누가 무엇을 한다" 문구. 모르는 행동은 ID 그대로 보여준다. */
internal object ActionUiCatalog {
    /** 역할을 주장하지 않는 행동의 문양. 역할을 주장하는 행동은 그 역할의 문양을 쓴다. */
    fun icon(action: ActionId): ImageVector = when (action.value) {
        "coup" -> CoupIcons.Bolt
        "tax" -> CoupIcons.Crown
        "assassinate" -> CoupIcons.Dagger
        "steal" -> CoupIcons.Anchor
        "exchange" -> CoupIcons.Swap
        else -> CoupIcons.Star
    }

    data class Entry(@StringRes val name: Int, @StringRes val description: Int)

    private val entries = mapOf(
        "income" to Entry(R.string.game_action_income, R.string.game_action_desc_income),
        "foreign_aid" to Entry(R.string.game_action_foreign_aid, R.string.game_action_desc_foreign_aid),
        "coup" to Entry(R.string.game_action_coup, R.string.game_action_desc_coup),
        "tax" to Entry(R.string.game_action_tax, R.string.game_action_desc_tax),
        "assassinate" to Entry(R.string.game_action_assassinate, R.string.game_action_desc_assassinate),
        "steal" to Entry(R.string.game_action_steal, R.string.game_action_desc_steal),
        "exchange" to Entry(R.string.game_action_exchange, R.string.game_action_desc_exchange),
    )

    fun of(action: ActionId): Entry? = entries[action.value]
}
