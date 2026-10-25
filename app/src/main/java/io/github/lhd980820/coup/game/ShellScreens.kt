package io.github.lhd980820.coup.game

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
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.game_home_title), style = MaterialTheme.typography.displayMedium)
            profile?.let { ProfileCard(it, onSaveNickname = onSaveNickname, onSignOut = onSignOut, onRanking = onRanking, onHistory = onHistory) }
            Button(onClick = onSingle, modifier = Modifier.fillMaxWidth().testTag(ShellTags.HOME_SINGLE)) { Text(stringResource(R.string.game_home_single)) }
            onMultiplayer?.let {
                Button(onClick = it, modifier = Modifier.fillMaxWidth().testTag(ShellTags.HOME_MULTI)) { Text(stringResource(R.string.mp_home_multiplayer)) }
            }
            OutlinedButton(onClick = onTutorial, modifier = Modifier.fillMaxWidth().testTag(ShellTags.HOME_TUTORIAL)) {
                Text(stringResource(R.string.game_home_tutorial))
            }
            onSettings?.let {
                TextButton(onClick = it, modifier = Modifier.testTag(ShellTags.HOME_SETTINGS)) { Text(stringResource(R.string.game_home_settings)) }
            }
        }
    }
}

/** 설정 화면: 애니메이션 속도. 고르면 바로 적용되고 저장된다. */
@Composable
internal fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        MotionSpeed.NORMAL to R.string.settings_motion_normal,
        MotionSpeed.FAST to R.string.settings_motion_fast,
        MotionSpeed.OFF to R.string.settings_motion_off,
    )
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            onDismissRequest = {},
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
