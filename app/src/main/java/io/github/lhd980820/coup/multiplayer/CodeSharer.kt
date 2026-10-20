package io.github.lhd980820.coup.multiplayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 참가 코드를 기기 밖으로 내보내는 통로(클립보드, 공유 시트). 화면은 이 인터페이스만 알아서 기기 없이 시험한다.
 * 제공되지 않으면(null) 화면은 복사·공유 버튼을 보이지 않는다.
 */
internal interface CodeSharer {
    /** 클립보드에 복사한다. 성공하면 true. */
    fun copy(label: String, text: String): Boolean

    /** 공유 시트를 연다. */
    fun share(text: String, chooserTitle: String)
}

internal val LocalCodeSharer = staticCompositionLocalOf<CodeSharer?> { null }

internal class AndroidCodeSharer(private val context: Context) : CodeSharer {
    override fun copy(label: String, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (e: SecurityException) {
            false // 일부 기기는 백그라운드·제한 상태의 쓰기를 막는다
        }
    }

    override fun share(text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, chooserTitle).addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
