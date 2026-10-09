package io.github.lhd980820.coup.presentation.tutorial

import io.github.lhd980820.coup.engine.model.ActionId
import io.github.lhd980820.coup.engine.model.RoleId

/**
 * 내장 튜토리얼 과정(설계 §10.5). 문구는 키만 갖고 있고 번역은 앱 리소스가 한다.
 * `seed`는 손패를 맞추려고 찾아둔 값이며, 테스트가 손패와 끝까지 진행되는지를 검증한다.
 */
public object TutorialLessons {
    private fun roles(vararg ids: String) = ids.map(::RoleId).toSet()

    /** 1. 기본 행동: 수입과 해외 원조. */
    public val BASICS: TutorialLesson = TutorialLesson(
        id = "basics",
        titleKey = "tutorial_basics_title",
        seed = 0,
        humanFirst = true,
        humanHand = emptySet(),
        tutorHand = emptySet(),
        tutorScript = listOf(TutorMove.Act(ActionId("income")), TutorMove.Pass, TutorMove.Act(ActionId("income"))),
        steps = listOf(
            TutorialStep("tutorial_basics_income", TutorialExpect.Action(ActionId("income"))),
            TutorialStep("tutorial_basics_foreign_aid", TutorialExpect.Action(ActionId("foreign_aid"))),
        ),
        completionKey = "tutorial_basics_done",
    )

    /** 2. 역할 주장: 공작 카드를 들고 세금. */
    public val CLAIM: TutorialLesson = TutorialLesson(
        id = "claim",
        titleKey = "tutorial_claim_title",
        seed = 10,
        humanFirst = true,
        humanHand = roles("duke", "contessa"),
        tutorHand = roles("assassin", "ambassador"),
        tutorScript = listOf(TutorMove.Pass, TutorMove.Act(ActionId("income"))),
        steps = listOf(TutorialStep("tutorial_claim_tax", TutorialExpect.Action(ActionId("tax")))),
        completionKey = "tutorial_claim_done",
    )

    /** 3. 도전: 상대가 공작이 없으면서 세금을 주장한다. */
    public val CHALLENGE: TutorialLesson = TutorialLesson(
        id = "challenge",
        titleKey = "tutorial_challenge_title",
        seed = 41,
        humanFirst = false,
        humanHand = roles("ambassador", "assassin"),
        tutorHand = roles("captain", "contessa"),
        tutorScript = listOf(TutorMove.Act(ActionId("tax"))),
        steps = listOf(TutorialStep("tutorial_challenge_challenge", TutorialExpect.Challenge)),
        completionKey = "tutorial_challenge_done",
    )

    /** 4. 막기: 상대의 갈취를 대사로 막는다. */
    public val BLOCK: TutorialLesson = TutorialLesson(
        id = "block",
        titleKey = "tutorial_block_title",
        seed = 120,
        humanFirst = false,
        humanHand = roles("ambassador", "contessa"),
        tutorHand = roles("captain", "duke"),
        tutorScript = listOf(TutorMove.Act(ActionId("steal")), TutorMove.Pass),
        steps = listOf(TutorialStep("tutorial_block_block", TutorialExpect.Block(RoleId("ambassador")))),
        completionKey = "tutorial_block_done",
    )

    public val all: List<TutorialLesson> = listOf(BASICS, CLAIM, CHALLENGE, BLOCK)
}
