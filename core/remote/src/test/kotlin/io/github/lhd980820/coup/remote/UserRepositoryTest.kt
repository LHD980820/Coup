package io.github.lhd980820.coup.remote

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.RatingDecision
import io.github.lhd980820.coup.account.RatingLedger
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.GameResultRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class UserRepositoryTest {
    private val fs = FakeFirestore()
    private val clock = Clock { 1_000 }
    private fun repo(uid: String, email: String? = null, store: DocumentStore = fs.storeFor(uid, email)) =
        FirestoreUserRepository(store, uid, email, clock)

    private suspend fun profileOf(uid: String) = repo(uid).observeProfile().first()

    /** 방이 시작되어 게임 문서가 생긴 상태로 만든다(방장 = 순위 첫 사람). */
    private suspend fun startGame(gameId: String, ranking: List<String>, rated: Boolean = true) {
        FirestoreGameTransport(fs.storeFor(ranking.first()), clock)
            .createGame(gameId, fs.seedStartedRoom(gameId, ranking.first(), ranking, rated), ranking.first(), ranking, BuiltinRules.classicConfig(), rated)
    }

    /** 방장 기기가 FirestoreGameTransport로 게임을 만들고 끝낸 것처럼 결과 문서를 쓴다. [started]면 게임 문서는 이미 있다. */
    private suspend fun finishGame(
        gameId: String,
        ranking: List<String>,
        rated: Boolean = true,
        deltas: Map<String, Int>? = null,
        started: Boolean = false,
    ) {
        val host = FirestoreGameTransport(fs.storeFor(ranking.first()), clock)
        if (!started) startGame(gameId, ranking, rated)
        val computed = TableRatingPolicy.ratingDeltas(ranking.map(::PlayerId))
        host.host.finish(
            gameId,
            GameResultRecord(
                ranking = ranking.map(::PlayerId),
                ruleSetConfig = BuiltinRules.classicConfig(),
                rated = rated,
                ratingDeltas = deltas?.mapKeys { PlayerId(it.key) } ?: if (rated) computed else emptyMap(),
            ),
        )
    }

    /** 서버 판정이 검증을 통과시키고 참가자 전원의 레이팅을 반영한 것처럼 쓴다(관리자 권한, ADR 0015). */
    private suspend fun serverApplies(gameId: String) {
        val result = checkNotNull(UserDocuments.result(gameId, fs.docs.getValue("results/$gameId")))
        val ops = result.ranking.flatMap { uid ->
            val profile = checkNotNull(UserDocuments.profile(uid, fs.docs.getValue("users/$uid")))
            UserDocuments.ratingWrites(uid, RatingLedger.decide(profile, result, alreadyApplied = false, now = 1_000) as RatingDecision.Apply)
        }
        fs.admin().write(ops + verdict(gameId, FirestoreSchema.Verdict.VERIFIED))
    }

    private fun verdict(gameId: String, verdict: String) = WriteOp.Merge("games/$gameId", buildJsonObject { put(FirestoreSchema.Game.VERDICT, verdict) })

    private suspend fun signUp(vararg uids: String) = uids.forEach { repo(it).ensureProfile("닉-$it") }

    // ---- 프로필 -------------------------------------------------------------------------------

    @Test
    fun `처음 로그인하면 시작 레이팅으로 프로필이 생기고 두 번째는 그대로`(): Unit = runBlocking {
        val first = repo("a").ensureProfile("에이")
        assertThat(first).isEqualTo(UserProfile.fresh("a", "에이", 1_000))
        repo("a").updateNickname("바뀐닉")
        assertThat(repo("a").ensureProfile("다른닉").nickname).isEqualTo("바뀐닉")
    }

    @Test
    fun `옛 계정은 한 번만 옮겨진다`(): Unit = runBlocking {
        fs.admin().write(listOf(WriteOp.Set("user/me@x.com", buildJsonObject { put("nickname", "옛닉"); put("rating", 1234); put("plays", 20) })))
        val migrated = repo("u1", "me@x.com").ensureProfile("기본")
        assertThat(migrated).isEqualTo(UserProfile("u1", "옛닉", 1234, 20, 0, 1_000, migratedFromLegacy = true))
        // 같은 이메일의 다른 계정(다른 로그인 수단)은 새 프로필
        assertThat(repo("u2", "me@x.com").ensureProfile("기본").rating).isEqualTo(1000)
    }

    @Test
    fun `옛 문서는 본인 이메일로만 읽힌다`(): Unit = runBlocking {
        fs.admin().write(listOf(WriteOp.Set("user/me@x.com", buildJsonObject { put("rating", 1234) })))
        assertFailure { fs.storeFor("x", "other@x.com").observe("user/me@x.com").first() }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("x").observe("user/me@x.com").first() }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 프로필을 조작해서 만들 수 없다`(): Unit = runBlocking {
        val store = fs.storeFor("a", "a@x.com")
        fun profile(rating: Int, migrated: Boolean, plays: Int = 0) =
            UserProfile("a", "에이", rating, plays, 0, 0, migratedFromLegacy = migrated).toJson()
        // 시작 레이팅이 아님
        assertFailure { store.write(listOf(WriteOp.Set("users/a", profile(5000, migrated = false)))) }.isInstanceOf(PermissionDenied::class)
        // 옛 문서 없이 이관 주장
        assertFailure { store.write(listOf(WriteOp.Set("users/a", profile(5000, migrated = true)))) }.isInstanceOf(PermissionDenied::class)
        fs.admin().write(listOf(WriteOp.Set("user/a@x.com", buildJsonObject { put("rating", 1100); put("plays", 3) })))
        // 옛 값과 다름
        val claim = WriteOp.Set("legacyClaims/a@x.com", buildJsonObject { put("uid", "a") })
        assertFailure { store.write(listOf(claim, WriteOp.Set("users/a", profile(9999, migrated = true, plays = 3)))) }.isInstanceOf(PermissionDenied::class)
        // 레이팅은 같지만 판수가 다름
        assertFailure { store.write(listOf(claim, WriteOp.Set("users/a", profile(1100, migrated = true, plays = 99)))) }.isInstanceOf(PermissionDenied::class)
        // 다른 사람 uid로 선점 표시
        assertFailure {
            store.write(listOf(WriteOp.Set("legacyClaims/a@x.com", buildJsonObject { put("uid", "someone-else") }), WriteOp.Set("users/a", profile(1100, migrated = true, plays = 3))))
        }.isInstanceOf(PermissionDenied::class)
        // 선점 표시 없이
        assertFailure { store.write(listOf(WriteOp.Set("users/a", profile(1100, migrated = true, plays = 3)))) }.isInstanceOf(PermissionDenied::class)
        // 남의 이메일 선점
        assertFailure { store.write(listOf(WriteOp.Set("legacyClaims/b@x.com", buildJsonObject { put("uid", "a") }))) }.isInstanceOf(PermissionDenied::class)
        // 남의 프로필
        assertFailure { store.write(listOf(WriteOp.Set("users/b", UserProfile.fresh("b", "비이", 0).toJson()))) }.isInstanceOf(PermissionDenied::class)
        // 올바른 이관은 통과
        store.write(listOf(claim, WriteOp.Set("users/a", profile(1100, migrated = true, plays = 3))))
        assertThat(profileOf("a")!!.rating).isEqualTo(1100)
    }

    @Test
    fun `규칙 - 새 프로필의 모양과 옛 문서 쓰기`(): Unit = runBlocking {
        val store = fs.storeFor("a", "a@x.com")
        fun deny(data: JsonObject) = assertFailure { runBlocking { store.write(listOf(WriteOp.Set("users/a", data))) } }.isInstanceOf(PermissionDenied::class)
        val fresh = UserProfile.fresh("a", "에이", 0).toJson()
        deny(JsonObject(fresh + ("admin" to JsonPrimitive(true))))
        deny(JsonObject(fresh + ("nickname" to JsonPrimitive("x"))))
        deny(JsonObject(fresh + ("wins" to JsonPrimitive(5))))
        deny(JsonObject(fresh + ("migratedFromLegacy" to JsonPrimitive("no"))))
        fs.admin().write(listOf(WriteOp.Set("user/a@x.com", buildJsonObject { put("rating", 1000) })))
        assertFailure { store.write(listOf(WriteOp.Merge("user/a@x.com", buildJsonObject { put("rating", 9999) }))) }.isInstanceOf(PermissionDenied::class)
        store.write(listOf(WriteOp.Set("users/a", fresh)))
    }

    @Test
    fun `규칙 - 클라이언트는 레이팅과 반영 표시를 쓸 수 없다(표대로인 정상 값이어도)`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        val a = fs.storeFor("a")
        fun deny(ops: List<WriteOp>, store: DocumentStore = a) = assertFailure { runBlocking { store.write(ops) } }.isInstanceOf(PermissionDenied::class)
        // 예전 규칙이 허용하던 정확한 반영(레이팅 + 표시를 한 배치로)도 이제는 거절된다 — 레이팅은 서버 판정만 바꾼다
        deny(applyOps("a", "g", 1030, 1, 1, 0, 30))
        deny(applyOps("a", "g", 1030, 1, 1, 0, 30).take(1))
        deny(applyOps("a", "g", 1030, 1, 1, 0, 30).drop(1))
        // 진 게임의 표시만 먼저 만들어 반영을 피하는 것도
        deny(applyOps("b", "g", 980, 1, 0, 1, -20).drop(1), fs.storeFor("b"))
        // 서버가 반영한 뒤 표시를 고치거나 지울 수도 없다
        serverApplies("g")
        deny(listOf(WriteOp.Merge("users/a/appliedResults/g", buildJsonObject { put("delta", 999) })))
        deny(listOf(WriteOp.Delete("users/a/appliedResults/g")))
        // 남의 전적은 볼 수 없다
        assertFailure { FirestoreUserRepository(fs.storeFor("b"), "a", null, clock).observeHistory(5).first() }
            .isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").observe("users/a/appliedResults/g").first() }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 옛 정산 대기 표시는 만들어지지 않고 클라이언트가 읽거나 쓸 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        assertThat(fs.docs.keys.filter { it.startsWith("pendingRatings/") }).isEqualTo(emptyList())
        val pending = WriteOp.Set("pendingRatings/b", buildJsonObject { put("gameId", "g") })
        assertFailure { fs.storeFor("a").write(listOf(pending)) }.isInstanceOf(PermissionDenied::class)
        fs.admin().write(listOf(pending))
        assertFailure { fs.storeFor("b").observe("pendingRatings/b").first() }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").write(listOf(WriteOp.Delete("pendingRatings/b"))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 방장은 판정을 쓸 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        assertFailure { fs.storeFor("a").write(listOf(verdict("g", FirestoreSchema.Verdict.VERIFIED))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").write(listOf(verdict("g", FirestoreSchema.Verdict.REJECTED))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `닉네임 변경 - 규칙 위반은 쓰지 않고, 레이팅은 직접 바꿀 수 없다`(): Unit = runBlocking {
        signUp("a")
        assertThat(repo("a").updateNickname("x")).isEqualTo(NicknameError.TOO_SHORT)
        assertThat(repo("a").updateNickname("  새이름  ")).isNull()
        assertThat(profileOf("a")!!.nickname).isEqualTo("새이름")
        val store = fs.storeFor("a")
        assertFailure { store.write(listOf(WriteOp.Merge("users/a", buildJsonObject { put("rating", 3000) }))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { store.write(listOf(WriteOp.Merge("users/a", buildJsonObject { put("nickname", "x".repeat(13)) }))) }
            .isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").write(listOf(WriteOp.Merge("users/a", buildJsonObject { put("nickname", "해킹") }))) }
            .isInstanceOf(PermissionDenied::class)
    }

    // ---- 레이팅 반영 상태(서버 판정을 지켜본다) -------------------------------------------------

    private suspend fun settlementOf(uid: String, gameId: String) = repo(uid).observeSettlement(gameId).first()

    @Test
    fun `판정 전에는 확인 중, 서버가 반영하면 내 전적과 새 레이팅`(): Unit = runBlocking {
        signUp("a", "b", "c")
        finishGame("g1", listOf("b", "a", "c"))
        listOf("a", "b", "c").forEach { assertThat(settlementOf(it, "g1"), it).isEqualTo(Settlement.Verifying) }
        serverApplies("g1")
        val a = settlementOf("a", "g1") as Settlement.Applied
        assertThat(a.record.delta to a.record.rank).isEqualTo(20 to 1)
        assertThat(a.rating).isEqualTo(1020)
        assertThat((settlementOf("b", "g1") as Settlement.Applied).rating).isEqualTo(1050)
        assertThat(profileOf("b")!!.let { Triple(it.rating, it.plays, it.wins) }).isEqualTo(Triple(1050, 1, 1))
        assertThat(repo("a").observeHistory(10).first().map { it.gameId to it.delta }).containsExactly("g1" to 20)
    }

    @Test
    fun `구독 중에 판정이 나면 따라온다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        val seen = async { repo("a").observeSettlement("g").first { it.isFinal } }
        yield()
        serverApplies("g")
        assertThat(withTimeout(5_000) { seen.await() }).isInstanceOf(Settlement.Applied::class)
    }

    @Test
    fun `무효 판정, 비레이팅 판, 검증됐지만 내 표시가 없는 경우`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("bad", listOf("a", "b"))
        fs.admin().write(listOf(verdict("bad", FirestoreSchema.Verdict.REJECTED)))
        assertThat(settlementOf("a", "bad")).isEqualTo(Settlement.Rejected)
        assertThat(profileOf("a")!!.rating).isEqualTo(1000)

        // 비레이팅 판은 서버를 기다리지 않는다
        finishGame("casual", listOf("a", "b"), rated = false)
        assertThat(settlementOf("a", "casual")).isEqualTo(Settlement.Unrated)
        fs.admin().write(listOf(verdict("casual", FirestoreSchema.Verdict.UNRATED)))
        assertThat(settlementOf("a", "casual")).isEqualTo(Settlement.Unrated)

        finishGame("g", listOf("a", "b"))
        fs.admin().write(listOf(verdict("g", FirestoreSchema.Verdict.VERIFIED)))
        assertThat(settlementOf("a", "g")).isEqualTo(Settlement.NotApplied)
        assertThat(Settlement.Verifying.isFinal).isEqualTo(false)
    }

    @Test
    fun `방장이 변동량을 부풀린 결과는 쓸 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        assertFailure { finishGame("forged", listOf("a", "b"), deltas = mapOf("a" to 500, "b" to -20)) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 레이팅 게임의 순위는 사람 좌석 전원이어야 한다`(): Unit = runBlocking {
        val host = FirestoreGameTransport(fs.storeFor("a"), clock)
        host.createGame("g", fs.seedStartedRoom("g", "a", listOf("a", "b"), rated = true), "a", listOf("a", "b"), BuiltinRules.classicConfig(), rated = true)
        fun result(ranking: List<String>, count: Int = ranking.size) = WriteOp.Set(
            "results/g",
            buildJsonObject {
                put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.forEach { add(JsonPrimitive(it)) } })
                put(FirestoreSchema.Result.PLAYER_COUNT, count)
                put(FirestoreSchema.Result.RATED, true)
                val table = AccessPolicy.RATING_TABLE[ranking.size]
                put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { ranking.forEachIndexed { i, u -> table?.getOrNull(i)?.let { put(u, it) } } })
            },
        )
        val store = fs.storeFor("a")
        assertFailure { store.write(listOf(result(listOf("a", "sock")))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { store.write(listOf(result(listOf("a")))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { store.write(listOf(result(listOf("a", "b"), count = 3))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").write(listOf(result(listOf("b", "a")))) }.isInstanceOf(PermissionDenied::class)
        store.write(listOf(result(listOf("b", "a"))))
    }

    @Test
    fun `규칙 - 결과의 변동량은 4~6인에서도 모든 순위가 표와 같아야 한다`(): Unit = runBlocking {
        for (n in 4..6) {
            val ids = List(n) { "p$it" }
            val gameId = "big$n"
            startGame(gameId, ids)
            val table = AccessPolicy.RATING_TABLE.getValue(n)
            fun result(wrongAt: Int?) = WriteOp.Set(
                "results/$gameId",
                buildJsonObject {
                    put(FirestoreSchema.Result.RANKING, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                    put(FirestoreSchema.Result.PLAYER_COUNT, n)
                    put(FirestoreSchema.Result.RATED, true)
                    put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { ids.forEachIndexed { i, u -> put(u, table[i] + if (i == wrongAt) 1 else 0) } })
                },
            )
            val host = fs.storeFor(ids.first())
            for (wrongAt in 0 until n) assertFailure { host.write(listOf(result(wrongAt))) }.isInstanceOf(PermissionDenied::class)
            host.write(listOf(result(null)))
        }
    }

    @Test
    fun `규칙 - 게임 문서의 시작 시각은 서버 시각이어야 만들 수 있다`(): Unit = runBlocking {
        val seats = listOf("a", "b")
        val roomId = fs.seedStartedRoom("gt", "a", seats, rated = true)
        fun game(createdAt: kotlinx.serialization.json.JsonElement) = WriteOp.Set(
            "games/gt",
            buildJsonObject {
                put(FirestoreSchema.Game.HOST_UID, "a")
                put(FirestoreSchema.Game.ROOM_ID, roomId)
                put(FirestoreSchema.Game.SEATS, buildJsonArray { seats.forEach { add(JsonPrimitive(it)) } })
                put(FirestoreSchema.Game.RATED, true)
                put(FirestoreSchema.Game.CREATED_AT, createdAt)
                put(FirestoreSchema.Game.HOST_HEARTBEAT_AT, ServerValues.TIMESTAMP)
            },
        )
        val host = fs.storeFor("a")
        assertFailure { host.write(listOf(game(JsonPrimitive(5L)))) }.isInstanceOf(PermissionDenied::class)
        // 생존 신호도 서버 시각이어야 한다(옛 시각으로 만들어 곧바로 이어받게 하지 못하게, ADR 0017)
        assertFailure {
            host.write(listOf(WriteOp.Set("games/gt", JsonObject(game(ServerValues.TIMESTAMP).data + (FirestoreSchema.Game.HOST_HEARTBEAT_AT to JsonPrimitive(1L))))))
        }.isInstanceOf(PermissionDenied::class)
        host.write(listOf(game(ServerValues.TIMESTAMP)))
    }

    // ---- 랭킹 -------------------------------------------------------------------------------

    @Test
    fun `랭킹은 레이팅순이며 아직 게임하지 않은 사용자는 빠진다`(): Unit = runBlocking {
        signUp("a", "b", "c", "idle")
        finishGame("g1", listOf("a", "b", "c"))
        serverApplies("g1")
        finishGame("g2", listOf("c", "b"))
        serverApplies("g2")
        val top = repo("idle").observeTop(10).first()
        // a 1050, b 1020-20=1000, c 960+30=990
        assertThat(top.map { it.uid to it.rating }).containsExactly("a" to 1050, "b" to 1000, "c" to 990)
        assertThat(top.map { it.rank }).containsExactly(1, 2, 3)
        assertThat(repo("idle").observeTop(2).first().size).isEqualTo(2)
    }

    // ---- 도우미 -------------------------------------------------------------------------------

    private fun applyOps(uid: String, gameId: String, rating: Int, plays: Int, wins: Int, rank: Int, delta: Int): List<WriteOp> = listOf(
        WriteOp.Merge(
            "users/$uid",
            buildJsonObject {
                put("rating", rating); put("plays", plays); put("wins", wins)
                put("lastAppliedResult", gameId); put("lastAppliedRank", rank)
            },
        ),
        WriteOp.Set(
            "users/$uid/appliedResults/$gameId",
            buildJsonObject { put("rank", rank); put("delta", delta); put("playerCount", 2); put("finishedAt", 0); put("appliedAt", 0) },
        ),
    )
}
