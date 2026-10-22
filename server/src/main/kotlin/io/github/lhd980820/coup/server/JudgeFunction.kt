package io.github.lhd980820.coup.server

import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.functions.CloudEventsFunction
import io.cloudevents.CloudEvent
import io.github.lhd980820.coup.judge.JudgeOutcome
import io.github.lhd980820.coup.judge.ServerJudge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

/**
 * 서버 판정 Cloud Function(2세대, Java 런타임, ADR 0015). Firestore 이벤트 `google.cloud.firestore.document.v1.created`를
 * `document=results/{gameId}`로 받아 그 게임을 판정한다. 이벤트 본문(protobuf)은 읽지 않는다 — 주제(subject)의 경로만 쓰고,
 * 필요한 문서는 판정이 서버에서 직접 읽는다.
 *
 * 같은 이벤트가 두 번 와도 안전하다(판정 표시로 한 번만 반영). 저장소 오류는 예외로 올려 재시도(`--retry`)에 맡긴다.
 */
public class JudgeFunction internal constructor(private val judge: () -> ServerJudge) : CloudEventsFunction {
    /** 함수 프레임워크가 부르는 생성자: 기본 프로젝트의 Firestore(실행 환경의 서비스 계정 = 관리자 권한). */
    public constructor() : this({ defaultJudge })

    override fun accept(event: CloudEvent) {
        val gameId = ResultEvents.gameIdOf(event.subject)
        if (gameId == null) {
            log.warning("ignoring an event for ${event.subject}")
            return
        }
        val outcome = runBlocking(Dispatchers.IO) { judge().judge(gameId) }
        log.info("game $gameId: ${describe(outcome)}")
        // 결과 문서가 생겨서 불렸는데 결과나 게임이 안 보이면 잠시 뒤 다시 시도한다
        if (outcome is JudgeOutcome.NotReady) throw IllegalStateException("game $gameId is not ready: ${outcome.why}")
    }

    private fun describe(outcome: JudgeOutcome): String = when (outcome) {
        is JudgeOutcome.Verified -> "verified, ratings applied to ${outcome.applied}"
        is JudgeOutcome.Rejected -> "rejected (${outcome.verdict.reason}: ${outcome.verdict.detail})"
        JudgeOutcome.Unrated -> "not rated"
        JudgeOutcome.AlreadyJudged -> "already judged"
        is JudgeOutcome.NotReady -> "not ready (${outcome.why})"
    }

    private companion object {
        val log: Logger = Logger.getLogger(JudgeFunction::class.java.name)

        /** 인스턴스가 살아 있는 동안 재사용한다(연결을 매번 새로 맺지 않는다). */
        val defaultJudge: ServerJudge by lazy { ServerJudge(AdminDocumentStore(FirestoreOptions.getDefaultInstance().service)) }
    }
}

/** Firestore 이벤트의 주제(`documents/results/{gameId}`)에서 게임 ID를 꺼낸다. */
internal object ResultEvents {
    private val SUBJECT = Regex("^documents/results/([^/]+)$")

    fun gameIdOf(subject: String?): String? = subject?.let { SUBJECT.matchEntire(it)?.groupValues?.get(1) }
}
