package io.github.lhd980820.coup.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.lhd980820.coup.engine.model.RoleId
import io.github.lhd980820.coup.ui.kit.CARD_ASPECT
import io.github.lhd980820.coup.ui.kit.CoupIcons
import io.github.lhd980820.coup.ui.kit.Hairline
import io.github.lhd980820.coup.ui.kit.PrimaryButton
import io.github.lhd980820.coup.ui.kit.SecondaryButton
import io.github.lhd980820.coup.ui.theme.Gold
import io.github.lhd980820.coup.ui.theme.GoldBright
import io.github.lhd980820.coup.ui.theme.GoldDeep
import io.github.lhd980820.coup.ui.theme.ParchmentDim
import io.github.lhd980820.coup.ui.kit.TableSurface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.lhd980820.coup.R
import io.github.lhd980820.coup.game.sound.SoundLevel
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.multiplayer.ProfileCard
import io.github.lhd980820.coup.presentation.GameController
import io.github.lhd980820.coup.presentation.tutorial.GuidedSession
import io.github.lhd980820.coup.presentation.tutorial.TutorialLesson
import io.github.lhd980820.coup.presentation.tutorial.TutorialLessons

internal object ShellTags {
    const val HOME_SINGLE = "home_single"
    const val HOME_TUTORIAL = "home_tutorial"
    const val HOME_MULTI = "home_multi"
    const val HOME_SETTINGS = "home_settings"
    const val SETTINGS_BACK = "settings_back"
    fun motion(speed: MotionSpeed) = "settings_motion_${speed.name.lowercase()}"
    fun sound(level: SoundLevel) = "settings_sound_${level.name.lowercase()}"
    const val TUTORIAL_BACK = "tutorial_list_back"
    const val COACH_DONE = "coach_done"
    const val COACH_NEXT = "coach_next"
    const val COACH_DONE_BACK = "coach_done_back"
    fun lesson(id: String) = "lesson_$id"
    fun lessonDone(id: String) = "lesson_done_$id"
}

@Composable
internal fun HomeScreen(
    onSingle: () -> Unit,
    onTutorial: () -> Unit,
    modifier: Modifier = Modifier,
    /** 멀티플레이 진입. null이면 버튼을 보이지 않는다(서버 연결이 없는 구성). */
    onMultiplayer: (() -> Unit)? = null,
    /** 로그인한 사용자의 프로필(없으면 카드를 보이지 않는다). */
    profile: UserProfile? = null,
    onSaveNickname: suspend (String) -> NicknameError? = { null },
    onSignOut: () -> Unit = {},
    onRanking: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    /** 설정 화면 진입. null이면 버튼을 보이지 않는다. */
    onSettings: (() -> Unit)? = null,
) {
    TableSurface(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 낮은 화면(작은 폰, 프로필이 있는 화면)에서는 장식을 줄여 버튼이 스크롤 없이 보이게 한다
            val roomy = maxHeight >= 640.dp && profile == null
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (roomy) CardFan()
                Text(
                    stringResource(R.string.game_home_title).uppercase(),
                    style = MaterialTheme.typography.displayLarge.copy(brush = Brush.verticalGradient(listOf(GoldBright, Gold, GoldDeep))),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldRule(Modifier.weight(1f))
                    Text(stringResource(R.string.game_home_tagline), style = MaterialTheme.typography.labelLarge, color = ParchmentDim)
                    GoldRule(Modifier.weight(1f))
                }
                Spacer(Modifier.height(if (roomy) 12.dp else 2.dp))
                profile?.let { ProfileCard(it, onSaveNickname = onSaveNickname, onSignOut = onSignOut, onRanking = onRanking, onHistory = onHistory) }
                PrimaryButton(stringResource(R.string.game_home_single), onSingle, Modifier.fillMaxWidth().testTag(ShellTags.HOME_SINGLE), icon = CoupIcons.Swords)
                onMultiplayer?.let {
                    SecondaryButton(stringResource(R.string.mp_home_multiplayer), it, Modifier.fillMaxWidth().testTag(ShellTags.HOME_MULTI), icon = CoupIcons.Group, line = Gold, content = Gold)
                }
                SecondaryButton(stringResource(R.string.game_home_tutorial), onTutorial, Modifier.fillMaxWidth().testTag(ShellTags.HOME_TUTORIAL), icon = CoupIcons.Scroll, line = Hairline)
                onSettings?.let {
                    TextButton(onClick = it, modifier = Modifier.testTag(ShellTags.HOME_SETTINGS)) {
                        Icon(CoupIcons.Sliders, contentDescription = null, modifier = Modifier.size(16.dp), tint = ParchmentDim)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.game_home_settings), color = ParchmentDim)
                    }
                }
            }
        }
    }
}

/** 홈 화면의 그림: 부채꼴로 펼친 카드 세 장(장식이라 스크린리더에는 내지 않는다). */
@Composable
private fun CardFan() {
    val width = 92.dp
    val height = width / CARD_ASPECT
    Box(Modifier.size(width = width * 2.5f, height = height + 26.dp).clearAndSetSemantics { }, contentAlignment = Alignment.BottomCenter) {
        listOf(Triple("assassin", -16f, -58.dp), Triple("contessa", 16f, 58.dp), Triple("duke", 0f, 0.dp)).forEach { (role, angle, shift) ->
            RoleCard(
                role = RoleId(role),
                faceUp = true,
                modifier = Modifier
                    .offset(x = shift, y = if (angle == 0f) (-14).dp else 0.dp)
                    .graphicsLayer { rotationZ = angle; transformOrigin = TransformOrigin(0.5f, 1f) }
                    .shadow(10.dp, RoundedCornerShape(8.dp))
                    .size(width, height),
            )
        }
    }
}

private val soundOptions = listOf(
    SoundLevel.NORMAL to R.string.settings_sound_normal,
    SoundLevel.LOW to R.string.settings_sound_low,
    SoundLevel.OFF to R.string.settings_sound_off,
)

/** 설정 화면: 애니메이션 속도. 고르면 바로 적용되고 저장된다. */
@Composable
internal fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        MotionSpeed.NORMAL to R.string.settings_motion_normal,
        MotionSpeed.FAST to R.string.settings_motion_fast,
        MotionSpeed.OFF to R.string.settings_motion_off,
    )
    TableSurface(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.settings_motion_title), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                options.forEach { (speed, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = settings.motion == speed, role = Role.RadioButton, onClick = { settings.chooseMotion(speed) })
                            .testTag(ShellTags.motion(speed)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.motion == speed, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text(stringResource(R.string.settings_motion_note), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.settings_sound_title), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                soundOptions.forEach { (level, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = settings.sound == level, role = Role.RadioButton, onClick = { settings.chooseSound(level) })
                            .testTag(ShellTags.sound(level)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.sound == level, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text(stringResource(R.string.settings_sound_note), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack, modifier = Modifier.testTag(ShellTags.SETTINGS_BACK)) { Text(stringResource(R.string.settings_back)) }
        }
    }
}

@Composable
internal fun TutorialListScreen(
    lessons: List<TutorialLesson>,
    completed: Set<String>,
    onStart: (TutorialLesson) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TableSurface(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tutorial_list_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.tutorial_list_hint), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lessons, key = { it.id }) { lesson ->
                    val done = lesson.id in completed
                    val doneLabel = stringResource(R.string.tutorial_done_badge)
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onStart(lesson) }
                            .semantics { if (done) stateDescription = doneLabel } // TalkBack이 "완료"를 함께 읽는다
                            .testTag(ShellTags.lesson(lesson.id)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tutorialText(lesson.titleKey), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (done) Text(stringResource(R.string.tutorial_done_badge), Modifier.testTag(ShellTags.lessonDone(lesson.id)), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.testTag(ShellTags.TUTORIAL_BACK)) { Text(stringResource(R.string.tutorial_back)) }
        }
    }
}

/** 키로 온 튜토리얼 문구를 번역한다. 모르는 키는 키를 그대로 보여준다. */
@Composable
internal fun tutorialText(key: String): String = TutorialTexts.res(key)?.let { stringResource(it) } ?: key

/** 튜토리얼 한 과정의 화면: 일반 게임 화면 + 안내 카드(지금 눌러야 할 것만 활성), 끝내면 완료 대화상자. */
@Composable
internal fun TutorialGame(
    controller: GameController,
    guided: GuidedSession,
    lesson: TutorialLesson,
    actions: GameActions,
    onComplete: (String) -> Unit,
    onBack: () -> Unit,
    onNext: ((TutorialLesson) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val progress by guided.progress.collectAsState()
    val current = state ?: return

    // 진행 중에 뒤로 가기는 한 번 묻고, 다 끝난 뒤(완료 대화상자)에는 대화상자가 받는다
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !progress.completed) { confirmLeave = true }
    if (confirmLeave && !progress.completed) {
        ConfirmLeaveDialog(
            text = R.string.back_leave_tutorial,
            confirm = R.string.back_confirm_quit,
            stay = R.string.back_stay_continue,
            onConfirm = { confirmLeave = false; onBack() },
            onStay = { confirmLeave = false },
        )
    }

    LaunchedEffect(progress.completed) {
        if (progress.completed) onComplete(lesson.id)
    }
    val step = progress.stepIndex.coerceAtMost(lesson.steps.size - 1)
    GameScreen(
        state = current,
        messages = controller.messages,
        actions = actions,
        onExit = onBack,
        onPlayAgain = onBack,
        modifier = modifier,
        guide = GuideUi(
            message = tutorialText(progress.messageKey),
            step = step + 1,
            total = progress.totalSteps,
            expect = if (progress.completed) null else lesson.steps[step].expect,
            offScript = progress.offScript,
        ),
    )
    if (progress.completed) {
        val nextLesson = TutorialLessons.all.getOrNull(TutorialLessons.all.indexOf(lesson) + 1)
        AlertDialog(
            // 뒤로 가기 = 목록으로. 바깥을 눌러서는 닫히지 않는다
            onDismissRequest = onBack,
            properties = DialogProperties(dismissOnClickOutside = false),
            modifier = Modifier.testTag(ShellTags.COACH_DONE),
            title = { Text(stringResource(R.string.tutorial_complete_title)) },
            text = { Text(tutorialText(lesson.completionKey)) },
            confirmButton = {
                if (nextLesson != null && onNext != null) {
                    Button(onClick = { onNext(nextLesson) }, modifier = Modifier.testTag(ShellTags.COACH_NEXT)) { Text(stringResource(R.string.tutorial_next)) }
                } else {
                    Button(onClick = onBack, modifier = Modifier.testTag(ShellTags.COACH_DONE_BACK)) { Text(stringResource(R.string.tutorial_back)) }
                }
            },
            dismissButton = if (nextLesson != null && onNext != null) {
                { TextButton(onClick = onBack, modifier = Modifier.testTag(ShellTags.COACH_DONE_BACK)) { Text(stringResource(R.string.tutorial_back)) } }
            } else {
                null
            },
        )
    }
}
