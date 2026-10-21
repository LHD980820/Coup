package io.github.lhd980820.coup.multiplayer

import androidx.annotation.StringRes
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.lobby.BotLevel
import io.github.lhd980820.coup.lobby.BotStyle
import io.github.lhd980820.coup.lobby.RoomError
import io.github.lhd980820.coup.shell.auth.AuthError
import io.github.lhd980820.coup.shell.auth.FieldError
import io.github.lhd980820.coup.shell.lobby.LobbyProblem

/** 코어의 실패·상태 값 -> 문자열 리소스. `when`이 모든 값을 덮도록 컴파일러가 강제하고, `MultiplayerTextsTest`가 ko/en 번역을 확인한다. */
internal object MultiplayerTexts {
    @StringRes
    fun auth(error: AuthError): Int = when (error) {
        AuthError.INVALID_EMAIL -> R.string.mp_auth_invalid_email
        AuthError.WEAK_PASSWORD -> R.string.mp_auth_weak_password
        AuthError.WRONG_CREDENTIALS -> R.string.mp_auth_wrong_credentials
        AuthError.EMAIL_IN_USE -> R.string.mp_auth_email_in_use
        AuthError.ACCOUNT_NOT_FOUND -> R.string.mp_auth_account_not_found
        AuthError.TOO_MANY_REQUESTS -> R.string.mp_auth_too_many_requests
        AuthError.NETWORK -> R.string.mp_auth_network
        AuthError.CREDENTIAL_CONFLICT -> R.string.mp_auth_credential_conflict
        AuthError.GOOGLE_NO_ACCOUNT -> R.string.mp_auth_google_no_account
        AuthError.GOOGLE_UNAVAILABLE -> R.string.mp_auth_google_unavailable
        AuthError.UNKNOWN -> R.string.mp_auth_unknown
    }

    @StringRes
    fun field(error: FieldError): Int = when (error) {
        FieldError.EMAIL_EMPTY -> R.string.mp_field_email_empty
        FieldError.EMAIL_MALFORMED -> R.string.mp_field_email_malformed
        FieldError.PASSWORD_TOO_SHORT -> R.string.mp_field_password_too_short
        FieldError.PASSWORD_MISMATCH -> R.string.mp_field_password_mismatch
    }

    @StringRes
    fun nickname(error: NicknameError): Int = when (error) {
        NicknameError.TOO_SHORT -> R.string.mp_nickname_too_short
        NicknameError.TOO_LONG -> R.string.mp_nickname_too_long
        NicknameError.INVALID_CHARACTERS -> R.string.mp_nickname_invalid
    }

    @StringRes
    fun room(error: RoomError): Int = when (error) {
        RoomError.ROOM_NOT_FOUND -> R.string.mp_room_error_not_found
        RoomError.ROOM_NOT_WAITING -> R.string.mp_room_error_not_waiting
        RoomError.ROOM_FULL -> R.string.mp_room_error_full
        RoomError.WRONG_CODE -> R.string.mp_room_error_wrong_code
        RoomError.KICKED -> R.string.mp_room_error_kicked
        RoomError.APP_UPDATE_REQUIRED -> R.string.mp_room_error_update_required
        RoomError.NOT_IN_ROOM -> R.string.mp_room_error_not_in_room
        RoomError.NOT_HOST -> R.string.mp_room_error_not_host
        RoomError.NOT_ALLOWED -> R.string.mp_room_error_not_allowed
        RoomError.INVALID_TITLE -> R.string.mp_room_error_invalid_title
        RoomError.INVALID_PLAYER_COUNT -> R.string.mp_room_error_invalid_player_count
        RoomError.INVALID_RULES -> R.string.mp_room_error_invalid_rules
        RoomError.RULES_CONFLICT -> R.string.mp_room_error_rules_conflict
        RoomError.NOT_ENOUGH_PLAYERS -> R.string.mp_room_error_not_enough_players
        RoomError.NOT_ALL_READY -> R.string.mp_room_error_not_all_ready
        RoomError.TARGET_NOT_FOUND -> R.string.mp_room_error_target_not_found
        RoomError.JOIN_CODE_REQUIRED -> R.string.mp_room_error_code_required
        RoomError.JOIN_CODE_TAKEN -> R.string.mp_room_error_code_taken
    }

    @StringRes
    fun problem(problem: LobbyProblem): Int = when (problem) {
        is LobbyProblem.Room -> room(problem.error)
        LobbyProblem.Network -> R.string.mp_problem_network
    }

    @StringRes
    fun botLevel(level: BotLevel?): Int = when (level ?: BotLevel.NORMAL) {
        BotLevel.EASY -> R.string.mp_bot_easy
        BotLevel.NORMAL -> R.string.mp_bot_normal
    }

    @StringRes
    fun botStyle(style: BotStyle?): Int = when (style ?: BotStyle.BALANCED) {
        BotStyle.BALANCED -> R.string.mp_bot_balanced
        BotStyle.CAUTIOUS -> R.string.mp_bot_cautious
        BotStyle.AGGRESSIVE -> R.string.mp_bot_aggressive
    }

    @StringRes
    fun notice(notice: RoomNotice): Int = when (notice) {
        RoomNotice.CLOSED -> R.string.mp_notice_closed
        RoomNotice.KICKED -> R.string.mp_notice_kicked
    }
}
