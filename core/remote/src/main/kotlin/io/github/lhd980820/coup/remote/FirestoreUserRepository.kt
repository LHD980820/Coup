package io.github.lhd980820.coup.remote

import io.github.lhd980820.coup.account.AppliedResult
import io.github.lhd980820.coup.account.GameResult
import io.github.lhd980820.coup.account.LegacyMigration
import io.github.lhd980820.coup.account.LegacyUser
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.Nicknames
import io.github.lhd980820.coup.account.ProfileSetup
import io.github.lhd980820.coup.account.RankingEntry
import io.github.lhd980820.coup.account.Rankings
import io.github.lhd980820.coup.account.RatingDecision
import io.github.lhd980820.coup.account.RatingLedger
import io.github.lhd980820.coup.account.SkipReason
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public sealed interface ApplyOutcome {
    public data class Applied(val profile: UserProfile, val record: AppliedResult) : ApplyOutcome
    public data class Skipped(val reason: SkipReason) : ApplyOutcome

    /** 결과 문서가 아직 없다(방장이 아직 쓰지 않음). 결과 화면이 잠시 뒤 다시 부른다. */
    public data object NoResultYet : ApplyOutcome
    public data object NoProfile : ApplyOutcome
}

/**
 * 사용자·랭킹 저장소(설계 §8.5 `UserRepository`/`RankingRepository`)의 Firestore 구현. 규칙은 `:account`의 순수 함수가 정하고,
 * 이 클래스는 문서를 읽고 쓰기만 한다. 한 인스턴스는 로그인한 한 사용자([uid], 인증 토큰의 [email])로서 동작한다.
 *
 * 레이팅 반영은 **사용자 문서 갱신 + `appliedResults/{gameId}` 생성을 한 배치**로 쓴다. 보안 규칙은 표시 문서가 전에는 없었고
 * 배치 뒤에는 있으며, 변동량이 레이팅 표와 같을 때만 허용한다(ADR 0007). 다른 기기와 동시에 반영해서 거절되면 다시 읽고 판단한다.
 */
public class FirestoreUserRepository(
    private val store: DocumentStore,
    private val uid: String,
    private val email: String?,
    private val clock: Clock = Clock.System,
) {
    public fun observeProfile(userId: String = uid): Flow<UserProfile?> = store.observe(FirestoreSchema.user(userId)).map { it?.toProfile(userId) }

    /** 로그인 직후 한 번: 프로필이 없으면 옛 계정을 옮기거나 새로 만든다. */
    public suspend fun ensureProfile(defaultNickname: String): UserProfile {
        val existing = store.observe(FirestoreSchema.user(uid)).first()?.toProfile(uid)
        if (existing != null) return existing
        val legacyEmail = email?.takeIf { it.isNotBlank() && '/' !in it }
        val legacy = legacyEmail?.let { e -> store.observe(FirestoreSchema.legacyUser(e)).first()?.toLegacy(e) }
        val claimed = legacy != null && store.observe(FirestoreSchema.legacyClaim(legacy.email)).first() != null
        return when (val setup = LegacyMigration.plan(uid, legacyEmail, null, legacy, claimed, defaultNickname, clock.nowMillis())) {
            is ProfileSetup.Existing -> setup.profile
            is ProfileSetup.Fresh -> setup.profile.also { store.write(listOf(WriteOp.Set(FirestoreSchema.user(uid), it.toJson()))) }
            is ProfileSetup.Migrate -> setup.profile.also {
                store.write(
                    listOf(
                        WriteOp.Set(FirestoreSchema.legacyClaim(setup.email), buildJsonObject { put(FirestoreSchema.LegacyClaim.UID, uid) }),
                        WriteOp.Set(FirestoreSchema.user(uid), it.toJson()),
                    ),
                )
            }
        }
    }

    /** 닉네임을 바꾼다. 규칙 위반이면 쓰지 않고 오류를 돌려준다. */
    public suspend fun updateNickname(nickname: String): NicknameError? {
        Nicknames.validate(nickname)?.let { return it }
        store.write(listOf(WriteOp.Merge(FirestoreSchema.user(uid), buildJsonObject { put(FirestoreSchema.User.NICKNAME, Nicknames.normalize(nickname)) })))
        return null
    }

    /** 아직 정산하지 않은 rated 게임의 ID(없으면 null). 있으면 다음 rated 게임에 들어갈 수 없다. */
    public fun observePending(userId: String = uid): Flow<String?> =
        store.observe(FirestoreSchema.pendingRating(userId)).map { it?.str(FirestoreSchema.Pending.GAME_ID) }

    /** 앱 시작·로비 진입 때 부른다: 정산 대기 중인 게임이 있으면 반영한다. 없으면 null. */
    public suspend fun settlePending(): ApplyOutcome? {
        val gameId = observePending().first() ?: return null
        return applyResult(gameId)
    }

    /**
     * 게임 결과를 내 프로필에 1회 반영한다. 이미 반영했거나 대상이 아니면 레이팅은 쓰지 않는다.
     * 이 게임의 정산 대기 표시가 있으면 같은 배치로 지운다(이미 반영된 게임이면 표시만 지운다).
     */
    public suspend fun applyResult(gameId: String): ApplyOutcome {
        repeat(APPLY_ATTEMPTS) { attempt ->
            val result = store.observe(FirestoreSchema.result(gameId)).first()?.toResult(gameId) ?: return ApplyOutcome.NoResultYet
            val profile = store.observe(FirestoreSchema.user(uid)).first()?.toProfile(uid) ?: return ApplyOutcome.NoProfile
            val applied = store.observe(FirestoreSchema.appliedResult(uid, gameId)).first() != null
            val clearPending = observePending().first() == gameId
            when (val decision = RatingLedger.decide(profile, result, applied, clock.nowMillis())) {
                is RatingDecision.Skip -> {
                    if (applied && clearPending) store.write(listOf(WriteOp.Delete(FirestoreSchema.pendingRating(uid))))
                    return ApplyOutcome.Skipped(decision.reason)
                }
                is RatingDecision.Apply -> try {
                    store.write(applyOps(decision) + if (clearPending) listOf(WriteOp.Delete(FirestoreSchema.pendingRating(uid))) else emptyList())
                    return ApplyOutcome.Applied(decision.updated, decision.record)
                } catch (e: TransportException) {
                    // 다른 기기가 먼저 반영했거나(표시 문서 존재) 그사이 레이팅이 바뀌었다 -> 다시 읽고 판단한다
                    if (attempt == APPLY_ATTEMPTS - 1) throw e
                }
            }
        }
        error("unreachable")
    }

    /** 레이팅 상위 [limit]명. 아직 rated 게임을 하지 않은 사용자는 뺀다. */
    public fun observeTop(limit: Int): Flow<List<RankingEntry>> =
        store.observeOrdered(FirestoreSchema.USERS, FirestoreSchema.User.RATING, descending = true, limit = limit)
            .map { docs -> Rankings.rank(docs.mapNotNull { it.data.toProfile(it.id) }) }

    /** 내 전적(rated 게임), 최근 것부터. */
    public fun observeHistory(limit: Int): Flow<List<AppliedResult>> =
        store.observeOrdered(FirestoreSchema.appliedResults(uid), FirestoreSchema.Applied.APPLIED_AT, descending = true, limit = limit)
            .map { docs -> docs.mapNotNull { it.data.toApplied(it.id) } }

    private fun applyOps(decision: RatingDecision.Apply): List<WriteOp> {
        val p = decision.updated
        val r = decision.record
        return listOf(
            WriteOp.Merge(
                FirestoreSchema.user(uid),
                buildJsonObject {
                    put(FirestoreSchema.User.RATING, p.rating)
                    put(FirestoreSchema.User.PLAYS, p.plays)
                    put(FirestoreSchema.User.WINS, p.wins)
                    put(FirestoreSchema.User.LAST_APPLIED_RESULT, r.gameId)
                    put(FirestoreSchema.User.LAST_APPLIED_RANK, r.rank)
                },
            ),
            WriteOp.Set(
                FirestoreSchema.appliedResult(uid, r.gameId),
                buildJsonObject {
                    put(FirestoreSchema.Applied.RANK, r.rank)
                    put(FirestoreSchema.Applied.DELTA, r.delta)
                    put(FirestoreSchema.Applied.PLAYER_COUNT, r.playerCount)
                    put(FirestoreSchema.Applied.FINISHED_AT, r.finishedAt)
                    put(FirestoreSchema.Applied.APPLIED_AT, r.appliedAt)
                },
            ),
        )
    }

    private companion object {
        const val APPLY_ATTEMPTS = 3
    }
}

// ---- 문서 <-> 도메인 (형식이 틀린 문서는 null로 건너뛴다) -------------------------------------------

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun UserProfile.toJson(): JsonObject = buildJsonObject {
    put(FirestoreSchema.User.NICKNAME, nickname)
    put(FirestoreSchema.User.RATING, rating)
    put(FirestoreSchema.User.PLAYS, plays)
    put(FirestoreSchema.User.WINS, wins)
    put(FirestoreSchema.User.CREATED_AT, createdAt)
    put(FirestoreSchema.User.MIGRATED_FROM_LEGACY, migratedFromLegacy)
}

internal fun JsonObject.toProfile(uid: String): UserProfile? = UserProfile(
    uid = uid,
    nickname = str(FirestoreSchema.User.NICKNAME) ?: return null,
    rating = int(FirestoreSchema.User.RATING) ?: return null,
    plays = int(FirestoreSchema.User.PLAYS) ?: return null,
    wins = int(FirestoreSchema.User.WINS) ?: 0,
    createdAt = long(FirestoreSchema.User.CREATED_AT) ?: 0,
    migratedFromLegacy = bool(FirestoreSchema.User.MIGRATED_FROM_LEGACY) ?: false,
)

private fun JsonObject.toLegacy(email: String): LegacyUser? = LegacyUser(
    email = email,
    nickname = str(FirestoreSchema.Legacy.NICKNAME),
    rating = int(FirestoreSchema.Legacy.RATING) ?: return null,
    plays = int(FirestoreSchema.Legacy.PLAYS) ?: 0,
)

private fun JsonObject.toResult(gameId: String): GameResult? {
    val ranking = (this[FirestoreSchema.Result.RANKING] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null } ?: return null
    val deltas = (this[FirestoreSchema.Result.RATING_DELTAS] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.intOrNull ?: return null }
        ?: emptyMap()
    return GameResult(
        gameId = gameId,
        ranking = ranking,
        playerCount = int(FirestoreSchema.Result.PLAYER_COUNT) ?: return null,
        rated = bool(FirestoreSchema.Result.RATED) ?: false,
        ratingDeltas = deltas,
        finishedAt = long(FirestoreSchema.Result.FINISHED_AT) ?: 0,
    )
}

private fun JsonObject.toApplied(gameId: String): AppliedResult? = AppliedResult(
    gameId = gameId,
    rank = int(FirestoreSchema.Applied.RANK) ?: return null,
    delta = int(FirestoreSchema.Applied.DELTA) ?: return null,
    playerCount = int(FirestoreSchema.Applied.PLAYER_COUNT) ?: return null,
    finishedAt = long(FirestoreSchema.Applied.FINISHED_AT) ?: 0,
    appliedAt = long(FirestoreSchema.Applied.APPLIED_AT) ?: 0,
)
