package io.github.lhd980820.coup

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowDialog

/**
 * 실제 기기와 같은 순서로 뒤로 가기를 보낸다. **열려 있는 대화상자(별도 창)가 있으면 그 대화상자가 먼저** 받고, 없으면 액티비티가 받는다.
 * 액티비티에만 보내면 대화상자가 떠 있어도 화면의 BackHandler가 반응해서, 기기에서는 뒤로 가기가 대화상자에 삼켜지는 문제를 테스트가 놓친다.
 */
internal fun ComposeContentTestRule.pressBack() {
    @Suppress("UNCHECKED_CAST")
    val rule = this as AndroidComposeTestRule<*, ComponentActivity>
    runOnUiThread {
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog is ComponentDialog && dialog.isShowing) {
            dialog.onBackPressedDispatcher.onBackPressed()
        } else {
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }
    waitForIdle()
}
