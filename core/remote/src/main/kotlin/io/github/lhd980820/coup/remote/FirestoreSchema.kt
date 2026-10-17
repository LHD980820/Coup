package io.github.lhd980820.coup.remote

/**
 * Firestore 스키마 v2(설계 §8.2)의 경로와 필드 이름. 엔진 객체(뷰, 명령, 룰 설정)는 **JSON 문자열 필드**로 저장해서
 * 엔진 스키마가 바뀌어도 Firestore 문서 구조는 흔들리지 않는다.
 *
 * ```
 * games/{gameId}                        공개 메타(좌석 uid 목록, 방장, 상태, 버전, 방장 생존 신호)
 * games/{gameId}/views/{viewerId}       좌석별 뷰 — 해당 uid만 읽을 수 있다. viewerId = "spectator"는 관전용
 * games/{gameId}/authority/state        권한자 상태 백업 — 방장만 읽고 쓴다
 * games/{gameId}/commands/{commandId}   게스트 명령 — 보낸 사람과 방장만 읽는다
 * results/{gameId}                      종료 결과
 * users/{uid}                           공개 프로필(닉네임·레이팅·판수·승수) — 로그인한 누구나 읽는다
 * users/{uid}/appliedResults/{gameId}   내 프로필에 반영된 결과(1회 반영 표시 + 전적) — 본인만
 * legacyClaims/{email}                  옛 계정 이관 선점 표시(한 이메일은 한 번만 옮긴다) — 본인만
 * rooms/{roomId}                       대기실(구조화된 필드 — 규칙이 행위자별 변경을 검사한다). 공개 방만 목록 조회
 * joinCodes/{code}                      비공개 방 참가 코드 -> roomId
 * pendingRatings/{uid}                  아직 정산하지 않은 rated 게임(방장이 결과와 함께 쓰고 본인이 정산하며 지운다) — 로그인한 누구나 읽는다
 * user/{email}                          옛 앱의 사용자 문서(읽기 전용, 본인 이메일만)
 * ```
 */
public object FirestoreSchema {
    public const val SPECTATOR_VIEW: String = "spectator"

    public fun game(gameId: String): String = "games/$gameId"
    public fun view(gameId: String, viewerId: String): String = "games/$gameId/views/$viewerId"
    public fun authority(gameId: String): String = "games/$gameId/authority/state"
    public fun commands(gameId: String): String = "games/$gameId/commands"
    public fun command(gameId: String, commandId: String): String = "games/$gameId/commands/$commandId"
    public fun result(gameId: String): String = "results/$gameId"
    public const val USERS: String = "users"
    public fun user(uid: String): String = "users/$uid"
    public fun appliedResults(uid: String): String = "users/$uid/appliedResults"
    public fun appliedResult(uid: String, gameId: String): String = "users/$uid/appliedResults/$gameId"
    public fun legacyClaim(email: String): String = "legacyClaims/$email"
    public fun pendingRating(uid: String): String = "pendingRatings/$uid"
    public const val ROOMS: String = "rooms"
    public fun room(roomId: String): String = "rooms/$roomId"
    public fun joinCode(code: String): String = "joinCodes/$code"
    public fun legacyUser(email: String): String = "user/$email"

    /** games/{id} 문서 필드. */
    public object Game {
        public const val HOST_UID: String = "hostUid"
        public const val SEATS: String = "seats"
        public const val STATUS: String = "status"
        public const val VERSION: String = "version"
        public const val HOST_HEARTBEAT_AT: String = "hostHeartbeatAt"
        public const val RULE_SET_CONFIG_JSON: String = "ruleSetConfigJson"
        public const val RATED: String = "rated"
        public const val ROOM_ID: String = "roomId"
    }

    public object GameStatus {
        public const val PLAYING: String = "PLAYING"
        public const val FINISHED: String = "FINISHED"
    }

    /** views/{viewerId} 문서 필드. */
    public object View {
        public const val VERSION: String = "version"
        public const val ENVELOPE_JSON: String = "envelopeJson"
    }

    /** authority/state 문서 필드. */
    public object Authority {
        public const val VERSION: String = "version"
        public const val BACKUP_JSON: String = "backupJson"
    }

    /** commands/{id} 문서 필드. */
    public object Command {
        public const val SENDER_UID: String = "senderUid"
        public const val COMMAND_JSON: String = "commandJson"
        public const val EXPECTED_VERSION: String = "expectedVersion"
        public const val CREATED_AT: String = "createdAt"
        public const val STATUS: String = "status"
        public const val REJECTION: String = "rejection"
        public const val APPLIED_VERSION: String = "appliedVersion"
    }

    public object CommandStatus {
        public const val PENDING: String = "PENDING"
        public const val APPLIED: String = "APPLIED"
        public const val REJECTED: String = "REJECTED"
    }

    /** results/{id} 문서 필드. */
    public object Result {
        public const val RANKING: String = "ranking"
        public const val PLAYER_COUNT: String = "playerCount"
        public const val RULE_SET_CONFIG_JSON: String = "ruleSetConfigJson"
        public const val RATED: String = "rated"
        public const val RATING_DELTAS: String = "ratingDeltas"
        public const val FINISHED_AT: String = "finishedAt"
    }

    /** users/{uid} 문서 필드. [LAST_APPLIED_RESULT]/[LAST_APPLIED_RANK]는 보안 규칙이 레이팅 변경을 검증하는 데 쓴다. */
    public object User {
        public const val NICKNAME: String = "nickname"
        public const val RATING: String = "rating"
        public const val PLAYS: String = "plays"
        public const val WINS: String = "wins"
        public const val CREATED_AT: String = "createdAt"
        public const val MIGRATED_FROM_LEGACY: String = "migratedFromLegacy"
        public const val LAST_APPLIED_RESULT: String = "lastAppliedResult"
        public const val LAST_APPLIED_RANK: String = "lastAppliedRank"
    }

    /** users/{uid}/appliedResults/{gameId} 문서 필드. */
    public object Applied {
        public const val RANK: String = "rank"
        public const val DELTA: String = "delta"
        public const val PLAYER_COUNT: String = "playerCount"
        public const val FINISHED_AT: String = "finishedAt"
        public const val APPLIED_AT: String = "appliedAt"
    }

    /** legacyClaims/{email} 문서 필드. */
    public object LegacyClaim {
        public const val UID: String = "uid"
    }

    /** 옛 앱 user/{email} 문서 필드(옛 앱이 쓰던 이름 그대로). */
    public object Legacy {
        public const val NICKNAME: String = "nickname"
        public const val RATING: String = "rating"
        public const val PLAYS: String = "plays"
    }

    /** pendingRatings/{uid} 문서 필드. */
    public object Pending {
        public const val GAME_ID: String = "gameId"
    }

    /**
     * rooms/{id} 문서 필드. 사람 좌석은 [MEMBERS](uid -> {name, readyVersion, seq})와 [MEMBER_ORDER](입장 순서),
     * 봇은 [BOTS](목록). 게스트의 준비는 `readyVersion == configVersion`으로 판단한다 — 방장이 룰셋·봇을 바꾸면
     * [CONFIG_VERSION]이 올라가 게스트의 준비가 저절로 풀린다(방장이 게스트 항목을 고칠 필요가 없다).
     */
    public object Room {
        public const val TITLE: String = "title"
        public const val HOST_UID: String = "hostUid"
        public const val VISIBILITY: String = "visibility"
        public const val JOIN_CODE: String = "joinCode"
        public const val MAX_PLAYERS: String = "maxPlayers"
        public const val STATUS: String = "status"
        public const val RULE_SET: String = "ruleSet"
        public const val MIN_APP_VERSION: String = "minAppVersion"
        public const val CREATED_AT: String = "createdAt"
        public const val GAME_ID: String = "gameId"
        public const val BANNED: String = "banned"
        public const val BOTS: String = "bots"
        public const val MEMBERS: String = "members"
        public const val MEMBER_ORDER: String = "memberOrder"
        public const val CONFIG_VERSION: String = "configVersion"
        public const val VERSION: String = "version"
    }

    /** members.{uid} 항목. */
    public object Member {
        public const val NAME: String = "name"
        public const val READY_VERSION: String = "readyVersion"
        public const val SEQ: String = "seq"
    }

    /** bots[] 항목. */
    public object Bot {
        public const val ID: String = "id"
        public const val NAME: String = "name"
        public const val LEVEL: String = "level"
        public const val STYLE: String = "style"
        public const val SEQ: String = "seq"
    }

    /** ruleSet 맵(룰 설정을 규칙이 읽을 수 있게 JSON 문자열이 아닌 맵으로 둔다). */
    public object RuleSet {
        public const val BASE_ID: String = "baseId"
        public const val BASE_VERSION: String = "baseVersion"
        public const val HOUSE_RULES: String = "houseRules"
        public const val PARAM_OVERRIDES: String = "paramOverrides"
    }

    public object JoinCodeDoc {
        public const val ROOM_ID: String = "roomId"
    }
}
