package io.github.lhd980820.coup.remote

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.lhd980820.coup.account.NicknameError
import io.github.lhd980820.coup.account.SkipReason
import io.github.lhd980820.coup.account.UserProfile
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rating.TableRatingPolicy
import io.github.lhd980820.coup.engine.rules.builtin.BuiltinRules
import io.github.lhd980820.coup.runtime.Clock
import io.github.lhd980820.coup.runtime.transport.GameResultRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    /** 규칙을 우회해 손상된(표와 다른) 결과 문서를 만든다. 실제로는 규칙이 방장의 이런 쓰기를 거절한다. */
    private suspend fun corruptResult(gameId: String, ranking: List<String>, deltas: Map<String, Int>) {
        FirestoreGameTransport(fs.storeFor(ranking.first()), clock)
            .createGame(gameId, fs.seedStartedRoom(gameId, ranking.first(), ranking, rated = true), ranking.first(), ranking, BuiltinRules.classicConfig(), rated = true)
        fs.admin().write(
            listOf(
                WriteOp.Set(
                    "results/$gameId",
                    buildJsonObject {
                        put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.forEach { add(JsonPrimitive(it)) } })
                        put(FirestoreSchema.Result.PLAYER_COUNT, ranking.size)
                        put(FirestoreSchema.Result.RATED, true)
                        put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { deltas.forEach { (k, v) -> put(k, v) } })
                    },
                ),
            ),
        )
    }

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
    fun `규칙 - 전적 문서는 본인 것만, 순위·레이팅은 정확히`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        // 레이팅을 표보다 많이 올리기(전적 변동량은 정상)
        assertFailure { fs.storeFor("a").write(applyOps("a", "g", 2000, 1, 1, 0, 30)) }.isInstanceOf(PermissionDenied::class)
        // 전적의 순위를 다르게
        val wrongRank = applyOps("a", "g", 1030, 1, 1, 0, 30).let { ops ->
            listOf(ops[0], WriteOp.Set("users/a/appliedResults/g", JsonObject((ops[1] as WriteOp.Set).data + ("rank" to JsonPrimitive(1)))))
        }
        assertFailure { fs.storeFor("a").write(wrongRank) }.isInstanceOf(PermissionDenied::class)
        // b가 자기 반영과 함께 a의 전적 경로에 표시를 쓰기
        val bOps = applyOps("b", "g", 980, 1, 0, 1, -20)
        assertFailure { fs.storeFor("b").write(listOf(bOps[0], WriteOp.Set("users/a/appliedResults/g", (bOps[1] as WriteOp.Set).data))) }
            .isInstanceOf(PermissionDenied::class)
        repo("a").applyResult("g")
        assertFailure { fs.storeFor("b").observe("users/a/appliedResults/g").first() }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 비레이팅 결과에 변동량이 적혀 있어도 반영할 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        startGame("c", listOf("a", "b"), rated = false)
        fs.storeFor("a").write(
            listOf(
                WriteOp.Set(
                    "results/c",
                    buildJsonObject {
                        put(FirestoreSchema.Result.RANKING, buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
                        put(FirestoreSchema.Result.PLAYER_COUNT, 2)
                        put(FirestoreSchema.Result.RATED, false)
                        put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { put("a", 30); put("b", -20) })
                    },
                ),
            ),
        )
        assertThat(repo("a").applyResult("c")).isEqualTo(ApplyOutcome.Skipped(SkipReason.NOT_RATED))
        assertFailure { fs.storeFor("a").write(applyOps("a", "c", 1030, 1, 1, 0, 30)) }.isInstanceOf(PermissionDenied::class)
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

    // ---- 레이팅 반영 --------------------------------------------------------------------------

    @Test
    fun `참가자마다 자기 결과를 한 번 반영한다`(): Unit = runBlocking {
        signUp("a", "b", "c")
        finishGame("g1", listOf("b", "a", "c"))
        val outcomes = listOf("a", "b", "c").associateWith { repo(it).applyResult("g1") }
        assertThat(outcomes.values.all { it is ApplyOutcome.Applied }).isTrue()
        assertThat(profileOf("b")!!.let { Triple(it.rating, it.plays, it.wins) }).isEqualTo(Triple(1050, 1, 1))
        assertThat(profileOf("a")!!.rating).isEqualTo(1020)
        assertThat(profileOf("c")!!.rating).isEqualTo(960)
        assertThat(repo("a").applyResult("g1")).isEqualTo(ApplyOutcome.Skipped(SkipReason.ALREADY_APPLIED))
        assertThat(profileOf("a")!!.rating).isEqualTo(1020)
        assertThat(repo("a").observeHistory(10).first().map { it.gameId to it.delta }).containsExactly("g1" to 20)
    }

    @Test
    fun `반영하지 않는 경우 - 결과 없음, 프로필 없음, 비레이팅, 비참가`(): Unit = runBlocking {
        signUp("a", "b", "z")
        assertThat(repo("a").applyResult("nope")).isEqualTo(ApplyOutcome.NoResultYet)
        finishGame("casual", listOf("a", "b"), rated = false)
        assertThat(repo("a").applyResult("casual")).isEqualTo(ApplyOutcome.Skipped(SkipReason.NOT_RATED))
        finishGame("g", listOf("a", "b"))
        assertThat(repo("z").applyResult("g")).isEqualTo(ApplyOutcome.Skipped(SkipReason.NOT_A_PARTICIPANT))
        assertThat(repo("ghost").applyResult("g")).isEqualTo(ApplyOutcome.NoProfile)
        assertThat(profileOf("a")!!.plays).isEqualTo(0)
    }

    @Test
    fun `방장이 변동량을 부풀린 결과는 쓸 수 없고, 손상된 결과는 클라이언트도 규칙도 반영하지 않는다`(): Unit = runBlocking {
        signUp("a", "b")
        assertFailure { finishGame("forged", listOf("a", "b"), deltas = mapOf("a" to 500, "b" to -20)) }.isInstanceOf(PermissionDenied::class)
        corruptResult("g", listOf("a", "b"), mapOf("a" to 500, "b" to -20))
        assertThat(repo("a").applyResult("g")).isEqualTo(ApplyOutcome.Skipped(SkipReason.INCONSISTENT))
        // 클라이언트를 고쳐서 직접 써도 규칙이 거절한다
        assertFailure { fs.storeFor("a").write(applyOps("a", "g", rating = 1500, plays = 1, wins = 1, rank = 0, delta = 500)) }
            .isInstanceOf(PermissionDenied::class)
        // 표대로(+30) 올리더라도 결과 문서가 표와 어긋나면 반영 자체를 거절한다(전적에 엉뚱한 값이 남지 않게)
        assertFailure { fs.storeFor("a").write(applyOps("a", "g", rating = 1030, plays = 1, wins = 1, rank = 0, delta = 500)) }
            .isInstanceOf(PermissionDenied::class)
        // 다른 플레이어(b)의 정상 변동은 반영된다
        assertThat(repo("b").applyResult("g")).isInstanceOf(ApplyOutcome.Applied::class)
    }

    @Test
    fun `규칙 - 레이팅 반영을 위조할 수 없다`(): Unit = runBlocking {
        signUp("a", "b", "z")
        startGame("g", listOf("a", "b"))
        startGame("casual", listOf("a", "b"), rated = false)
        startGame("g3", listOf("a", "b"))
        finishGame("g", listOf("a", "b"), started = true)
        finishGame("casual", listOf("a", "b"), rated = false, started = true)
        val a = fs.storeFor("a")
        fun deny(ops: List<WriteOp>, store: DocumentStore = a) = assertFailure { runBlocking { store.write(ops) } }.isInstanceOf(PermissionDenied::class)
        // 표시 문서 없이
        deny(applyOps("a", "g", 1030, 1, 1, 0, 30).take(1))
        // 순위를 바꿔서(2위인 척 1위 주장은 ranking[0] != b)
        deny(applyOps("b", "g", 1030, 1, 1, 0, 30), fs.storeFor("b"))
        // 판수/승수 조작
        deny(applyOps("a", "g", 1030, 5, 1, 0, 30))
        deny(applyOps("a", "g", 1030, 1, 3, 0, 30))
        // 비레이팅 게임
        deny(applyOps("a", "casual", 1030, 1, 1, 0, 30))
        // 참가하지 않은 게임
        deny(applyOps("z", "g", 1030, 1, 1, 0, 30), fs.storeFor("z"))
        // 정상 반영 후 같은 게임 재반영
        a.write(applyOps("a", "g", 1030, 1, 1, 0, 30))
        deny(applyOps("a", "g", 1060, 2, 2, 0, 30))
        // 표시 문서에 다른 필드를 끼워 넣을 수 없다
        finishGame("g3", listOf("a", "b"), started = true)
        val padded = applyOps("a", "g3", 1060, 2, 2, 0, 30).let { ops ->
            listOf(ops[0], WriteOp.Set("users/a/appliedResults/g3", JsonObject((ops[1] as WriteOp.Set).data + ("note" to JsonPrimitive("x")))))
        }
        deny(padded)
        // 표시 문서 수정 불가
        deny(listOf(WriteOp.Merge("users/a/appliedResults/g", buildJsonObject { put("delta", 999) })))
        // 남의 전적은 볼 수 없다
        assertFailure { FirestoreUserRepository(fs.storeFor("b"), "a", null, clock).observeHistory(5).first() }
            .isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 진 게임의 반영 표시만 먼저 만들어 패배를 피할 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("win", listOf("a", "b"))
        repo("a").applyResult("win") // lastAppliedRank = 0
        repo("b").applyResult("win")
        finishGame("loss", listOf("b", "a"))
        val markerOnly = applyOps("a", "loss", 0, 0, 0, rank = 0, delta = -20).drop(1)
        assertFailure { fs.storeFor("a").write(markerOnly) }.isInstanceOf(PermissionDenied::class)
        assertThat(repo("a").applyResult("loss")).isInstanceOf(ApplyOutcome.Applied::class)
        assertThat(profileOf("a")!!.rating).isEqualTo(1010)
    }

    @Test
    fun `규칙 - 전적의 변동량과 남의 순위는 위조할 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        // 손상된 결과에서 b(2위)의 변동량이 1위 값이다 -> b가 1위 칸을 주장해도 거절
        corruptResult("g", listOf("a", "b"), mapOf("a" to 30, "b" to 30))
        assertFailure { fs.storeFor("b").write(applyOps("b", "g", 1030, 1, 1, rank = 0, delta = 30)) }.isInstanceOf(PermissionDenied::class)
        // 정상 결과에서 레이팅은 맞게 올리고 전적에만 큰 값을 남기는 것도 거절
        finishGame("h", listOf("a", "b"))
        assertFailure { fs.storeFor("a").write(applyOps("a", "h", 1030, 1, 1, rank = 0, delta = 999)) }.isInstanceOf(PermissionDenied::class)
        fs.storeFor("a").write(applyOps("a", "h", 1030, 1, 1, rank = 0, delta = 30))
    }

    @Test
    fun `같은 사용자의 두 기기가 동시에 반영해도 한 번만 반영된다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        // 기기 1이 읽은 뒤 쓰기 직전에 기기 2가 먼저 반영한다
        val device1 = repo("a", store = BeforeFirstWrite(fs.storeFor("a")) { repo("a").applyResult("g") })
        assertThat(device1.applyResult("g")).isEqualTo(ApplyOutcome.Skipped(SkipReason.ALREADY_APPLIED))
        assertThat(profileOf("a")!!.let { it.rating to it.plays }).isEqualTo(1030 to 1)
    }

    @Test
    fun `다른 게임을 동시에 반영하면 늦은 쪽이 다시 읽어 둘 다 반영된다`(): Unit = runBlocking {
        signUp("a", "b", "c")
        startGame("g1", listOf("a", "b"))
        startGame("g2", listOf("c", "b", "a"))
        finishGame("g1", listOf("a", "b"), started = true)
        finishGame("g2", listOf("c", "b", "a"), started = true)
        val device1 = repo("a", store = BeforeFirstWrite(fs.storeFor("a")) { repo("a").applyResult("g2") })
        assertThat(device1.applyResult("g1")).isInstanceOf(ApplyOutcome.Applied::class)
        assertThat(profileOf("a")!!.let { Triple(it.rating, it.plays, it.wins) }).isEqualTo(Triple(1000 + 30 - 40, 2, 1))
        assertThat(repo("a").observeHistory(10).first().map { it.gameId }.toSet()).isEqualTo(setOf("g1", "g2"))
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

    // ---- 정산 대기(다음 rated 게임 게이트) ---------------------------------------------------

    private suspend fun pendingOf(uid: String) = repo(uid).observePending().first()
    private suspend fun settled(uid: String) = AccessPolicy.isSettled(uid) { fs.docs[it] }

    @Test
    fun `rated 게임이 끝나면 참가자마다 정산 대기가 생기고 반영하면 사라진다`(): Unit = runBlocking {
        signUp("a", "b", "c")
        finishGame("g", listOf("b", "a", "c"))
        listOf("a", "b", "c").forEach { assertThat(pendingOf(it), it).isEqualTo("g") }
        assertThat(settled("a")).isEqualTo(false)
        repo("a").applyResult("g")
        assertThat(pendingOf("a")).isNull()
        assertThat(settled("a")).isTrue()
        assertThat(pendingOf("b")).isEqualTo("g") // 남의 정산은 각자
        finishGame("casual", listOf("x", "y"), rated = false)
        assertThat(pendingOf("x")).isNull()
    }

    @Test
    fun `앱이 꺼져 정산하지 못했으면 다음 실행에서 정산한다`(): Unit = runBlocking {
        signUp("a", "b")
        assertThat(repo("a").settlePending()).isNull()
        finishGame("g", listOf("b", "a"))
        val outcome = repo("a").settlePending()
        assertThat(outcome).isNotNull().isInstanceOf(ApplyOutcome.Applied::class)
        assertThat(profileOf("a")!!.rating).isEqualTo(980)
        assertThat(pendingOf("a")).isNull()
        assertThat(repo("a").settlePending()).isNull()
    }

    @Test
    fun `정산은 문서 읽기 몇 번과 배치 쓰기 한 번이다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        val counting = Counting(fs.storeFor("a"))
        repo("a", store = counting).settlePending()
        assertThat(counting.reads to counting.writes).isEqualTo(5 to 1)
    }

    @Test
    fun `규칙 - 정산하지 않고 대기 표시를 지울 수 없다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        assertFailure { fs.storeFor("a").write(listOf(WriteOp.Delete("pendingRatings/a"))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("b").write(listOf(WriteOp.Delete("pendingRatings/a"))) }.isInstanceOf(PermissionDenied::class)
        assertFailure { fs.storeFor("a").write(listOf(WriteOp.Merge("pendingRatings/a", buildJsonObject { put("gameId", "other") }))) }
            .isInstanceOf(PermissionDenied::class)
        assertThat(pendingOf("a")).isEqualTo("g")
    }

    @Test
    fun `규칙 - 방장은 결과와 함께, 참가자에게만 대기 표시를 만든다`(): Unit = runBlocking {
        signUp("a", "b", "victim")
        finishGame("g", listOf("a", "b"))
        fun pending(uid: String, gameId: String) = WriteOp.Set("pendingRatings/$uid", buildJsonObject { put("gameId", gameId) })
        // 결과 없이 단독으로
        assertFailure { fs.storeFor("a").write(listOf(pending("victim", "g"))) }.isInstanceOf(PermissionDenied::class)
        // 남의 게임으로
        assertFailure { fs.storeFor("victim").write(listOf(pending("a", "g"))) }.isInstanceOf(PermissionDenied::class)
        // 이미 있는 대기 표시 덮어쓰기
        assertFailure { fs.storeFor("a").write(listOf(pending("b", "g"))) }.isInstanceOf(PermissionDenied::class)
    }

    @Test
    fun `규칙 - 대기 표시 위조를 하나씩 막는다`(): Unit = runBlocking {
        signUp("a", "b", "victim")
        fun pending(uid: String, gameId: String, extra: Boolean = false) = WriteOp.Set(
            "pendingRatings/$uid",
            buildJsonObject {
                put("gameId", gameId)
                if (extra) put("note", "x")
            },
        )
        fun resultDoc(ranking: List<String>, rated: Boolean) = WriteOp.Set(
            "results/new",
            buildJsonObject {
                put(FirestoreSchema.Result.RANKING, buildJsonArray { ranking.forEach { add(JsonPrimitive(it)) } })
                put(FirestoreSchema.Result.PLAYER_COUNT, ranking.size)
                put(FirestoreSchema.Result.RATED, rated)
                put(FirestoreSchema.Result.RATING_DELTAS, buildJsonObject { if (rated) { put(ranking[0], 30); put(ranking[1], -20) } })
            },
        )
        val host = fs.storeFor("a")
        FirestoreGameTransport(host, clock)
            .createGame("new", fs.seedStartedRoom("new", "a", listOf("a", "b"), rated = true), "a", listOf("a", "b"), BuiltinRules.classicConfig(), rated = true)
        // 정산을 끝낸 b에게 옛 게임 대기를 다시 씌우기(결과 배치 밖)
        finishGame("old", listOf("a", "b"))
        repo("b").applyResult("old")
        assertFailure { host.write(listOf(pending("b", "old"))) }.isInstanceOf(PermissionDenied::class)
        // 비레이팅 결과와 함께
        assertFailure { host.write(listOf(resultDoc(listOf("a", "b"), rated = false), pending("b", "new"))) }.isInstanceOf(PermissionDenied::class)
        // 참가하지 않은 사람에게
        assertFailure { host.write(listOf(resultDoc(listOf("a", "b"), rated = true), pending("victim", "new"))) }.isInstanceOf(PermissionDenied::class)
        // 다른 필드를 끼워서
        assertFailure { host.write(listOf(resultDoc(listOf("a", "b"), rated = true), pending("b", "new", extra = true))) }.isInstanceOf(PermissionDenied::class)
        // 정상: 결과와 함께, 참가자에게
        host.write(listOf(resultDoc(listOf("a", "b"), rated = true), pending("b", "new")))
        assertThat(pendingOf("b")).isEqualTo("new")
        assertThat(pendingOf("victim")).isNull()
    }

    @Test
    fun `규칙 - 남의 대기 표시는 지울 수 없다(내가 그 게임을 정산했어도)`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        fs.storeFor("a").write(applyOps("a", "g", 1030, 1, 1, 0, 30)) // a는 반영만 하고 대기 표시는 남김
        repo("b").applyResult("g")
        assertFailure { fs.storeFor("b").write(listOf(WriteOp.Delete("pendingRatings/a"))) }.isInstanceOf(PermissionDenied::class)
        assertThat(pendingOf("a")).isEqualTo("g")
    }

    @Test
    fun `이미 대기 표시가 있는 참가자는 건너뛰고 결과는 기록된다`(): Unit = runBlocking {
        signUp("a", "b", "c")
        // b는 두 게임을 동시에 진행했다(둘 다 정산이 끝난 상태에서 시작) -> g1이 먼저 끝나 대기 중일 때 g2가 끝난다
        startGame("g1", listOf("a", "b"))
        startGame("g2", listOf("c", "b"))
        finishGame("g1", listOf("a", "b"), started = true)
        finishGame("g2", listOf("c", "b"), started = true)
        assertThat(pendingOf("b")).isEqualTo("g1")
        assertThat(pendingOf("c")).isEqualTo("g2")
        assertThat(repo("b").applyResult("g2")).isInstanceOf(ApplyOutcome.Applied::class)
        assertThat(pendingOf("b")).isEqualTo("g1") // g1 대기는 g1을 정산해야 사라진다
        repo("b").settlePending()
        assertThat(pendingOf("b")).isNull()
    }

    @Test
    fun `이미 반영한 게임의 대기 표시가 남아 있으면 지운다`(): Unit = runBlocking {
        signUp("a", "b")
        finishGame("g", listOf("a", "b"))
        // 대기 표시를 지우지 않는 옛 클라이언트처럼 반영만 한다
        fs.storeFor("a").write(applyOps("a", "g", 1030, 1, 1, 0, 30))
        assertThat(repo("a").applyResult("g")).isEqualTo(ApplyOutcome.Skipped(SkipReason.ALREADY_APPLIED))
        assertThat(pendingOf("a")).isNull()
    }

    // ---- 랭킹 -------------------------------------------------------------------------------

    @Test
    fun `랭킹은 레이팅순이며 아직 게임하지 않은 사용자는 빠진다`(): Unit = runBlocking {
        signUp("a", "b", "c", "idle")
        finishGame("g1", listOf("a", "b", "c"))
        listOf("a", "b", "c").forEach { repo(it).applyResult("g1") }
        finishGame("g2", listOf("c", "b"))
        listOf("c", "b").forEach { repo(it).applyResult("g2") }
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

    /** 읽기(문서 1회 조회)와 배치 쓰기 횟수를 센다. */
    private class Counting(private val inner: DocumentStore) : DocumentStore by inner {
        var reads = 0
        var writes = 0
        override fun observe(path: String): kotlinx.coroutines.flow.Flow<kotlinx.serialization.json.JsonObject?> {
            reads++
            return inner.observe(path)
        }
        override suspend fun write(ops: List<WriteOp>) {
            writes++
            inner.write(ops)
        }
    }

    /** 첫 쓰기 직전에 [action]을 끼워 넣는 저장소(경쟁 상황 재현). */
    private class BeforeFirstWrite(private val inner: DocumentStore, private val action: suspend () -> Unit) : DocumentStore by inner {
        private var fired = false
        override suspend fun write(ops: List<WriteOp>) {
            if (!fired) {
                fired = true
                action()
            }
            inner.write(ops)
        }
    }
}
