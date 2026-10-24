package io.github.lhd980820.coup.game

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

/** 역할 ID -> 표시 이름·카드 아트. 엔진은 표시 문자열을 갖지 않는다(설계 §10.6). 모르는 역할은 제네릭 카드로 폴백. */
internal object RoleUiCatalog {
    data class Entry(@StringRes val name: Int, @DrawableRes val art: Int, @StringRes val ability: Int)

    private val entries = mapOf(
        "duke" to Entry(R.string.game_role_duke, R.drawable.card_duke, R.string.game_role_ability_duke),
        "assassin" to Entry(R.string.game_role_assassin, R.drawable.card_assassin, R.string.game_role_ability_assassin),
        "captain" to Entry(R.string.game_role_captain, R.drawable.card_captine, R.string.game_role_ability_captain),
        "ambassador" to Entry(R.string.game_role_ambassador, R.drawable.card_ambassador, R.string.game_role_ability_ambassador),
        "contessa" to Entry(R.string.game_role_contessa, R.drawable.card_contessa, R.string.game_role_ability_contessa),
    )
    private val unknown = Entry(R.string.game_role_unknown, R.drawable.card_back, R.string.game_role_ability_unknown)

    fun of(role: RoleId): Entry = entries[role.value] ?: unknown
    val cardBack: Int get() = R.drawable.card_back
}

/** 행동 ID -> 버튼 이름과 "누가 무엇을 한다" 문구. 모르는 행동은 ID 그대로 보여준다. */
internal object ActionUiCatalog {
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
