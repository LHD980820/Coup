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

    /** games/{id} 문서 필드. */
    public object Game {
        public const val HOST_UID: String = "hostUid"
        public const val SEATS: String = "seats"
        public const val STATUS: String = "status"
        public const val VERSION: String = "version"
        public const val HOST_HEARTBEAT_AT: String = "hostHeartbeatAt"
        public const val RULE_SET_CONFIG_JSON: String = "ruleSetConfigJson"
        public const val RATED: String = "rated"
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
}
