package io.github.lhd980820.coup.multiplayer

import io.github.lhd980820.coup.ui.kit.TableSurface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.theme.Parchment
import io.github.lhd980820.coup.ui.theme.GoldInk
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.kit.panel
import io.github.lhd980820.coup.ui.kit.SecondaryButton
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.game.GoldRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import io.github.lhd980820.coup.shell.auth.AuthFormModel
import io.github.lhd980820.coup.shell.auth.AuthMode
import io.github.lhd980820.coup.shell.auth.Credentials
import io.github.lhd980820.coup.shell.auth.FieldError
import io.github.lhd980820.coup.shell.auth.GoogleTokenSource
import kotlinx.coroutines.launch

internal object LoginTags {
    const val EMAIL = "login_email"
    const val PASSWORD = "login_password"
    const val CONFIRM = "login_confirm"
    const val SUBMIT = "login_submit"
    const val SHOW_PASSWORD = "login_show_password"
    const val GO_REGISTER = "login_go_register"
    const val GO_SIGN_IN = "login_go_sign_in"
    const val GO_RESET = "login_go_reset"
    const val FAILURE = "login_failure"
    const val RESET_SENT = "login_reset_sent"
    const val BACK = "login_back"
    const val GOOGLE = "login_google"
}

@Composable
private fun fieldText(error: FieldError): String =
    if (error == FieldError.PASSWORD_TOO_SHORT) {
        stringResource(MultiplayerTexts.field(error), Credentials.MIN_PASSWORD_LENGTH)
    } else {
        stringResource(MultiplayerTexts.field(error))
    }

/**
 * 로그인·가입·비밀번호 재설정 한 화면(설계 §10.2). 입력 검사와 서버 호출은 모두 코어의 [AuthFormModel]이 한다.
 * 성공하면 로그인 상태가 바뀌므로 이 화면은 스스로 닫지 않고, 부모가 상태를 보고 화면을 바꾼다.
 */
@Composable
internal fun LoginScreen(form: AuthFormModel, onBack: () -> Unit, modifier: Modifier = Modifier, google: GoogleTokenSource? = null) {
    val state by form.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit: () -> Unit = { scope.launch { form.submit() } }
    // 가입·비밀번호 재설정 화면에서 뒤로 가기는 먼저 로그인으로, 로그인에서는 부모(홈)가 받는다
    BackHandler(enabled = state.mode != AuthMode.SIGN_IN) { form.setMode(AuthMode.SIGN_IN) }
    val title = when (state.mode) {
        AuthMode.SIGN_IN -> R.string.mp_login_title
        AuthMode.REGISTER -> R.string.mp_register_title
        AuthMode.RESET -> R.string.mp_reset_title
    }
    val actionLabel = when (state.mode) {
        AuthMode.SIGN_IN -> R.string.mp_action_sign_in
        AuthMode.REGISTER -> R.string.mp_action_register
        AuthMode.RESET -> R.string.mp_action_reset
    }

    TableSurface(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenBar(null, onBack, LoginTags.BACK)
            // 문장(紋章)과 제목: 왕관 메달 아래 세리프 제목, 그 아래 한 줄 안내
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(64.dp).panel(CircleShape, top = GoldBright, bottom = Gold, line = GoldBright, lineWidth = 2.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.mode == AuthMode.RESET) CoupIcons.Key else CoupIcons.Crown, contentDescription = null, tint = GoldInk, modifier = Modifier.size(32.dp)) }
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Parchment)
                if (state.mode == AuthMode.SIGN_IN) {
                    Text(stringResource(R.string.mp_login_hint), style = MaterialTheme.typography.bodyMedium, color = ParchmentDim, textAlign = TextAlign.Center)
                }
            }

            Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = form::setEmail,
                    label = { Text(stringResource(R.string.mp_email)) },
                    leadingIcon = { Icon(CoupIcons.Mail, contentDescription = null, tint = Gold) },
                    singleLine = true,
                    isError = state.emailError != null,
                    supportingText = state.emailError?.let { { Text(fieldText(it)) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = if (state.mode == AuthMode.RESET) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.EMAIL),
                )
                if (state.mode != AuthMode.RESET) {
                    // 비밀번호 보기/숨기기는 칸 끝의 눈 그림이다
                    val eye = stringResource(if (showPassword) R.string.mp_password_hide else R.string.mp_password_show)
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = form::setPassword,
                        label = { Text(stringResource(R.string.mp_password)) },
                        leadingIcon = { Icon(CoupIcons.Lock, contentDescription = null, tint = Gold) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.semantics { contentDescription = eye }.testTag(LoginTags.SHOW_PASSWORD)) {
                                Icon(if (showPassword) CoupIcons.EyeOff else CoupIcons.Eye, contentDescription = null, tint = ParchmentDim)
                            }
                        },
                        singleLine = true,
                        isError = state.passwordError != null,
                        supportingText = state.passwordError?.let { { Text(fieldText(it)) } },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (state.mode == AuthMode.REGISTER) ImeAction.Next else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
                    )
                }
                if (state.mode == AuthMode.REGISTER) {
                    OutlinedTextField(
                        value = state.confirm,
                        onValueChange = form::setConfirm,
                        label = { Text(stringResource(R.string.mp_password_confirm)) },
                        leadingIcon = { Icon(CoupIcons.Lock, contentDescription = null, tint = Gold) },
                        singleLine = true,
                        isError = state.confirmError != null,
                        supportingText = state.confirmError?.let { { Text(fieldText(it)) } },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().testTag(LoginTags.CONFIRM),
                    )
                }

                // 서버가 거절한 이유와 재설정 메일 안내는 읽어 주도록 라이브 영역으로 둔다
                state.failure?.let { NoticeBar(stringResource(MultiplayerTexts.auth(it)), LoginTags.FAILURE, error = true) }
                if (state.resetSent) NoticeBar(stringResource(R.string.mp_reset_sent), LoginTags.RESET_SENT, error = false)

                PrimaryButton(
                    stringResource(actionLabel), submit, Modifier.fillMaxWidth().padding(top = 4.dp).testTag(LoginTags.SUBMIT),
                    enabled = state.canSubmit, icon = if (state.mode == AuthMode.RESET) CoupIcons.Mail else CoupIcons.Enter,
                )

                // 구글 로그인: 이 기기에서 쓸 수 있을 때만(로그인·가입 모드). 처음이면 계정이 만들어지므로 두 모드 모두 같은 버튼이다.
                if (google != null && state.mode != AuthMode.RESET) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GoldRule(Modifier.weight(1f))
                        Text(stringResource(R.string.mp_or), style = MaterialTheme.typography.labelMedium, color = ParchmentDim)
                        GoldRule(Modifier.weight(1f))
                    }
                    SecondaryButton(
                        stringResource(R.string.mp_google_continue), { scope.launch { form.signInWithGoogle(google) } },
                        Modifier.fillMaxWidth().testTag(LoginTags.GOOGLE), enabled = !state.busy, icon = CoupIcons.Person,
                    )
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (state.mode) {
                    AuthMode.SIGN_IN -> {
                        TextButton(onClick = { form.setMode(AuthMode.REGISTER) }, modifier = Modifier.testTag(LoginTags.GO_REGISTER)) {
                            Text(stringResource(R.string.mp_go_register), color = GoldBright)
                        }
                        TextButton(onClick = { form.setMode(AuthMode.RESET) }, modifier = Modifier.testTag(LoginTags.GO_RESET)) {
                            Text(stringResource(R.string.mp_go_reset), color = ParchmentDim)
                        }
                    }
                    AuthMode.REGISTER, AuthMode.RESET -> TextButton(
                        onClick = { form.setMode(AuthMode.SIGN_IN) },
                        modifier = Modifier.testTag(LoginTags.GO_SIGN_IN),
                    ) { Text(stringResource(R.string.mp_go_sign_in), color = GoldBright) }
                }
            }
        }
    }
}
