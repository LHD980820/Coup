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
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.runtime.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** 끝난 게임이 내 레이팅에 어떻게 반영됐는가(서버 판정, ADR 0015). */
public sealed interface Settlement {
    /** 서버가 아직 판정하지 않았다(결과를 기다리거나 다시 돌려 보는 중). */
    public data object Verifying : Settlement

    /** 반영됐다. [rating]은 반영 직후 내 레이팅(읽지 못하면 null). */
    public data class Applied(val record: AppliedResult, val rating: Int?) : Settlement

    /** 레이팅 대상이 아닌 판(봇·하우스룰). */
    public data object Unrated : Settlement

    /** 기록이 맞지 않아 서버가 판을 무효로 했다. 아무의 레이팅도 바뀌지 않았다. */
    public data object Rejected : Settlement

    /** 검증됐지만 내 레이팅은 이번에 바뀌지 않았다(이미 반영됨, 프로필 없음). */
    public data object NotApplied : Settlement

    /** 방장이 판을 버려 서버가 끝냈다. 방장만 패배로 반영되고 나머지는 그대로다(ADR 0016). */
    public data object Abandoned : Settlement

    public val isFinal: Boolean get() = this != Verifying
}

/**
 * 사용자·랭킹 저장소(설계 §8.5 `UserRepository`/`RankingRepository`)의 Firestore 구현. 규칙은 `:account`의 순수 함수가 정하고,
 * 이 클래스는 문서를 읽고 쓰기만 한다. 한 인스턴스는 로그인한 한 사용자([uid], 인증 토큰의 [email])로서 동작한다.
 *
 * 레이팅은 쓰지 않는다: 서버 판정이 끝난 게임을 다시 돌려 검증하고 참가자 전원의 레이팅을 한 번에 반영한다(ADR 0015).
 * 클라이언트는 그 판정과 반영 표시를 지켜볼 뿐이다([observeSettlement]).
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
        val existing = store.get(FirestoreSchema.user(uid))?.toProfile(uid)
        if (existing != null) return existing
        val legacyEmail = email?.takeIf { it.isNotBlank() && '/' !in it }
        val legacy = legacyEmail?.let { e -> store.get(FirestoreSchema.legacyUser(e))?.toLegacy(e) }
        val claimed = legacy != null && store.get(FirestoreSchema.legacyClaim(legacy.email)) != null
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

    /**
     * 게임 [gameId]의 레이팅 반영 상태. 판정 표시(게임 문서)와 내 반영 표시를 함께 본다 — 서버가 둘을 한 트랜잭션으로 쓰지만
     * 구독은 따로 도착하므로, "검증됨인데 표시가 없다"면 서버에서 다시 읽어 확인한다.
     */
    public fun observeSettlement(gameId: String): Flow<Settlement> =
        combine(store.observe(FirestoreSchema.game(gameId)), store.observe(FirestoreSchema.appliedResult(uid, gameId))) { game, applied ->
            val marker = applied ?: if (game?.str(FirestoreSchema.Game.VERDICT) == FirestoreSchema.Verdict.VERIFIED) {
                store.get(FirestoreSchema.appliedResult(uid, gameId))
            } else {
                null
            }
            marker?.toApplied(gameId)?.let { record ->
                return@combine Settlement.Applied(record, store.get(FirestoreSchema.user(uid))?.toProfile(uid)?.rating)
            }
            when (game?.str(FirestoreSchema.Game.VERDICT)) {
                FirestoreSchema.Verdict.VERIFIED -> Settlement.NotApplied
                FirestoreSchema.Verdict.REJECTED -> Settlement.Rejected
                FirestoreSchema.Verdict.UNRATED -> Settlement.Unrated
                FirestoreSchema.Verdict.ABANDONED -> Settlement.Abandoned
                else -> if (game?.bool(FirestoreSchema.Game.RATED) == false) Settlement.Unrated else Settlement.Verifying
            }
        }.distinctUntilChanged()

    /** 레이팅 상위 [limit]명. 아직 rated 게임을 하지 않은 사용자는 뺀다. */
    public fun observeTop(limit: Int): Flow<List<RankingEntry>> =
        store.observeOrdered(FirestoreSchema.USERS, FirestoreSchema.User.RATING, descending = true, limit = limit)
            .map { docs -> Rankings.rank(docs.mapNotNull { it.data.toProfile(it.id) }) }

    /** 내 전적(rated 게임), 최근 것부터. */
    public fun observeHistory(limit: Int): Flow<List<AppliedResult>> =
        store.observeOrdered(FirestoreSchema.appliedResults(uid), FirestoreSchema.Applied.APPLIED_AT, descending = true, limit = limit)
            .map { docs -> docs.mapNotNull { it.data.toApplied(it.id) } }

}

/** 레이팅 반영 한 건의 쓰기: 프로필(레이팅·판수·승수) + 반영 표시 문서. 서버 판정(ADR 0015)만 쓴다. */
private fun ratingOps(uid: String, decision: RatingDecision.Apply): List<WriteOp> {
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

/** 사용자·결과 문서 <-> 도메인. 서버 판정(`:judge`)이 같은 형식을 읽고 쓰도록 공개한다. */
public object UserDocuments {
    public fun profile(uid: String, doc: JsonObject): UserProfile? = doc.toProfile(uid)
    public fun result(gameId: String, doc: JsonObject): GameResult? = doc.toResult(gameId)
    public fun ratingWrites(uid: String, decision: RatingDecision.Apply): List<WriteOp> = ratingOps(uid, decision)
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
