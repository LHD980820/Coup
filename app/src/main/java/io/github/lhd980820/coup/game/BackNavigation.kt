package io.github.lhd980820.coup.game

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.lhd980820.coup.R

internal object BackTags {
    const val DIALOG = "back_dialog"
    const val CONFIRM = "back_confirm"
    const val STAY = "back_stay"
}

/**
 * 화면을 떠나기 전에 한 번 묻는 대화상자(진행 중인 판·튜토리얼·방). 바깥을 눌러도 닫히지 않고, **뒤로 가기는 "머무르기"** 와 같다 —
 * 뒤로 가기를 두 번 눌러 실수로 나가지 않는다.
 */
@Composable
internal fun ConfirmLeaveDialog(
    @StringRes text: Int,
    @StringRes confirm: Int,
    @StringRes stay: Int,
    onConfirm: () -> Unit,
    onStay: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStay,
        modifier = Modifier.testTag(BackTags.DIALOG),
        properties = DialogProperties(dismissOnClickOutside = false),
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(BackTags.CONFIRM)) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onStay, modifier = Modifier.testTag(BackTags.STAY)) { Text(stringResource(stay)) } },
    )
}

/** 홈에서 뒤로 가기를 한 번 누르면 알리기만 하고, [windowMillis] 안에 한 번 더 누르면 앱을 끝낸다(실수로 앱이 꺼지지 않게). */
internal class DoubleBackExit(private val now: () -> Long, private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
    private var lastPress: Long? = null

    /** 뒤로 가기를 눌렀다. 앱을 끝내야 하면 true, 알려야 하면 false. */
    fun onBack(): Boolean {
        val t = now()
        val last = lastPress
        if (last != null && t - last in 0..windowMillis) {
            lastPress = null
            return true
        }
        lastPress = t
        return false
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 2_000L
    }
}
