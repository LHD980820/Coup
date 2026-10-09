package io.github.lhd980820.coup.presentation.tutorial

import io.github.lhd980820.coup.engine.command.Command
import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.model.RoleId

/** 튜토리얼 상대(AI 선생)가 정해진 순서로 두는 수. 카드 공개/상실/교환처럼 정답이 뻔한 결정은 기본 동작을 따른다. */
public sealed interface TutorMove {
    /** [action]을 한다. 대상이 필요하면 지정 가능한 첫 대상(튜토리얼은 항상 2인). */
    public data class Act(val action: ActionId) : TutorMove

    public data object Pass : TutorMove
    public data object Challenge : TutorMove
    public data class Block(val role: RoleId) : TutorMove
}

/** 사용자가 이 단계에서 해야 하는 한 수. 이 외의 명령은 [GuidedSession]이 거절한다(기권은 항상 허용). */
public sealed interface TutorialExpect {
    public fun accepts(command: Command): Boolean

    public data class Action(val action: ActionId) : TutorialExpect {
        override fun accepts(command: Command): Boolean = command is Command.DeclareAction && command.actionId == action
    }

    public data object Pass : TutorialExpect {
        override fun accepts(command: Command): Boolean = command is Command.Pass
    }

    public data object Challenge : TutorialExpect {
        override fun accepts(command: Command): Boolean = command is Command.Challenge
    }

    public data class Block(val role: RoleId) : TutorialExpect {
        override fun accepts(command: Command): Boolean = command is Command.Block && command.asRole == role
    }
}

/** 한 단계: 화면에 보여줄 안내 문구의 키(번역은 앱이 한다)와 기대하는 수. */
public data class TutorialStep(val messageKey: String, val expect: TutorialExpect)

/**
 * 튜토리얼 한 과정. 정해진 [seed]와 [firstPlayer]로 항상 같은 패가 돌려지고(테스트가 손패를 검증한다),
 * 상대는 [tutorScript]대로 둔다. 사용자의 모든 결정은 [steps]에 있어야 한다.
 */
public data class TutorialLesson(
    val id: String,
    val titleKey: String,
    val seed: Long,
    val humanFirst: Boolean,
    val humanHand: Set<RoleId>,
    val tutorHand: Set<RoleId>,
    val tutorScript: List<TutorMove>,
    val steps: List<TutorialStep>,
    val completionKey: String,
) {
    val firstPlayer: PlayerId get() = if (humanFirst) HUMAN else TUTOR

    public companion object {
        public val HUMAN: PlayerId = PlayerId("human")
        public val TUTOR: PlayerId = PlayerId("tutor")
    }
}
