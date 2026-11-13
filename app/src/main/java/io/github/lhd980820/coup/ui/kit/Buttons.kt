package io.github.lhd980820.coup.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.Parchment

/** 게임 버튼의 모양: 알약형 대신 모서리가 둥근 판. */
val ButtonShape: Shape = RoundedCornerShape(14.dp)
private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
private fun Label(icon: ImageVector?, text: String) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

/** 가장 중요한 행동(시작, 확정): 금색으로 채운 큰 버튼. [container]를 바꾸면 같은 모양의 다른 색 버튼이 된다(도전 = 붉은색, 막기 = 역할 색). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    container: Color = Gold,
    content: Color = GoldInk,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Label(icon, text) }
}

/** 덜 중요한 행동(허용, 취소, 둘째 메뉴): 테두리만 있는 버튼. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    line: Color = Gold.copy(alpha = 0.7f),
    content: Color = Parchment,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.5.dp, if (enabled) line else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
        modifier = modifier.heightIn(min = 52.dp),
    ) { Label(icon, text) }
}
