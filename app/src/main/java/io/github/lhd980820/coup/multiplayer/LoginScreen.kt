package io.github.lhd980820.coup.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(LoginTags.BACK)) { Text(stringResource(R.string.mp_back)) }
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
            if (state.mode == AuthMode.SIGN_IN) {
                Text(stringResource(R.string.mp_login_hint), style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = form::setEmail,
                label = { Text(stringResource(R.string.mp_email)) },
                singleLine = true,
                isError = state.emailError != null,
                supportingText = { state.emailError?.let { Text(fieldText(it)) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = if (state.mode == AuthMode.RESET) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.EMAIL),
            )
            if (state.mode != AuthMode.RESET) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = form::setPassword,
                    label = { Text(stringResource(R.string.mp_password)) },
                    singleLine = true,
                    isError = state.passwordError != null,
                    supportingText = { state.passwordError?.let { Text(fieldText(it)) } },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (state.mode == AuthMode.REGISTER) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
                )
                TextButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag(LoginTags.SHOW_PASSWORD)) {
                    Text(stringResource(if (showPassword) R.string.mp_password_hide else R.string.mp_password_show))
                }
            }
            if (state.mode == AuthMode.REGISTER) {
                OutlinedTextField(
                    value = state.confirm,
                    onValueChange = form::setConfirm,
                    label = { Text(stringResource(R.string.mp_password_confirm)) },
                    singleLine = true,
                    isError = state.confirmError != null,
                    supportingText = { state.confirmError?.let { Text(fieldText(it)) } },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.CONFIRM),
                )
            }

            // 서버가 거절한 이유와 재설정 메일 안내는 읽어 주도록 라이브 영역으로 둔다
            state.failure?.let {
                Text(
                    stringResource(MultiplayerTexts.auth(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LoginTags.FAILURE),
                )
            }
            if (state.resetSent) {
                Text(
                    stringResource(R.string.mp_reset_sent),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LoginTags.RESET_SENT),
                )
            }

            Button(
                onClick = submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.SUBMIT),
            ) { Text(stringResource(actionLabel)) }

            // 구글 로그인: 이 기기에서 쓸 수 있을 때만(로그인·가입 모드). 처음이면 계정이 만들어지므로 두 모드 모두 같은 버튼이다.
            if (google != null && state.mode != AuthMode.RESET) {
                Text(stringResource(R.string.mp_or), style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedButton(
                    onClick = { scope.launch { form.signInWithGoogle(google) } },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag(LoginTags.GOOGLE),
                ) { Text(stringResource(R.string.mp_google_continue)) }
            }

            when (state.mode) {
                AuthMode.SIGN_IN -> {
                    TextButton(onClick = { form.setMode(AuthMode.REGISTER) }, modifier = Modifier.testTag(LoginTags.GO_REGISTER)) {
                        Text(stringResource(R.string.mp_go_register))
                    }
                    TextButton(onClick = { form.setMode(AuthMode.RESET) }, modifier = Modifier.testTag(LoginTags.GO_RESET)) {
                        Text(stringResource(R.string.mp_go_reset))
                    }
                }
                AuthMode.REGISTER, AuthMode.RESET -> TextButton(
                    onClick = { form.setMode(AuthMode.SIGN_IN) },
                    modifier = Modifier.testTag(LoginTags.GO_SIGN_IN),
                ) { Text(stringResource(R.string.mp_go_sign_in)) }
            }
        }
    }
}
