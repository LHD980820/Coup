package io.github.lhd980820.coup.account

import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.RatingPolicy
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy

/** `results/{gameId}` 문서의 도메인 표현. [ranking]은 승자부터, 사람은 uid. */
public data class GameResult(
    val gameId: String,
    val ranking: List<String>,
    val playerCount: Int,
    val rated: Boolean,
    val ratingDeltas: Map<String, Int>,
    val finishedAt: Long,
)

/** 내 프로필에 한 번 반영된 결과(= 전적 한 줄, `users/{uid}/appliedResults/{gameId}`). [rank]는 0부터(0 = 1위). */
public data class AppliedResult(
    val gameId: String,
    val rank: Int,
    val delta: Int,
    val playerCount: Int,
    val finishedAt: Long,
    val appliedAt: Long,
)

public enum class SkipReason {
    /** 봇·하우스룰·파라미터 변경이 있는 게임(D5). */
    NOT_RATED,
    NOT_A_PARTICIPANT,
    ALREADY_APPLIED,

    /** 결과 문서가 레이팅 표와 맞지 않는다(방장 기기가 조작했거나 손상). 반영하지 않는다 — 보안 규칙도 거절한다. */
    INCONSISTENT,
}

public sealed interface RatingDecision {
    public data class Apply(val updated: UserProfile, val record: AppliedResult) : RatingDecision
    public data class Skip(val reason: SkipReason) : RatingDecision
}

/**
 * 결과를 **내 프로필에만** 반영하는 규칙(설계 §8.4, ADR 0007). 각 참가자 기기가 자기 결과만 1회 반영한다.
 * 변동량은 결과 문서에 적힌 값이 아니라 **레이팅 표(순위·인원수)에서 다시 계산**하고, 둘이 다르면 반영하지 않는다.
 * 그래서 방장 기기가 변동량을 부풀려도 효과가 없다(순위 자체의 조작은 서버 판정 — Phase 7 — 전까지 남는 위험).
 */
public object RatingLedger {
    public fun decide(
        profile: UserProfile,
        result: GameResult,
        alreadyApplied: Boolean,
        now: Long,
        policy: RatingPolicy = TableRatingPolicy,
    ): RatingDecision {
        if (alreadyApplied) return RatingDecision.Skip(SkipReason.ALREADY_APPLIED)
        if (!result.rated) return RatingDecision.Skip(SkipReason.NOT_RATED)
        val rank = result.ranking.indexOf(profile.uid)
        if (rank < 0) return RatingDecision.Skip(SkipReason.NOT_A_PARTICIPANT)
        val expected = expectedDeltas(result, policy) ?: return RatingDecision.Skip(SkipReason.INCONSISTENT)
        val delta = expected.getValue(profile.uid)
        if (result.ratingDeltas[profile.uid] != delta) return RatingDecision.Skip(SkipReason.INCONSISTENT)
        return RatingDecision.Apply(
            updated = profile.copy(
                rating = profile.rating + delta,
                plays = profile.plays + 1,
                wins = profile.wins + if (rank == 0) 1 else 0,
            ),
            record = AppliedResult(result.gameId, rank, delta, result.playerCount, result.finishedAt, now),
        )
    }

    /** 순위·인원수가 올바르면 표에서 계산한 변동량, 아니면 null. */
    private fun expectedDeltas(result: GameResult, policy: RatingPolicy): Map<String, Int>? {
        if (result.playerCount != result.ranking.size) return null
        if (result.ranking.toSet().size != result.ranking.size) return null
        return runCatching { policy.ratingDeltas(result.ranking.map(::PlayerId)) }.getOrNull()?.mapKeys { it.key.value }
    }
}
