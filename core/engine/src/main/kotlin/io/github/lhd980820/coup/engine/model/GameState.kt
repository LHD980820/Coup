package io.github.lhd980820.coup.engine.model

import io.github.lhd980820.coup.engine.core.ResolutionStep
import io.github.lhd980820.coup.engine.rng.DeterministicRng
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import kotlinx.serialization.Serializable

/**
 * 게임의 진짜 상태(비공개 정보 포함). 권한자만 보유한다.
 *
 * 히든 정보(손패 역할, 덱, RNG)와 내부 진행 정보는 `internal`이라 `:engine` 밖에서는 읽을 수 없다.
 * 외부 모듈은 이 객체를 불투명 핸들로만 다룬다(보관, 엔진에 전달, [io.github.lhd980820.coup.engine.serialization.EngineJson]으로 백업).
 * 시점별 정보가 필요하면 엔진의 뷰 투영을 사용한다.
 */
@Serializable
@ConsistentCopyVisibility
public data class GameState internal constructor(
    public val schemaVersion: Int,
    public val gameId: String,
    /** 명령이 적용될 때마다 1씩 증가. 원격 명령의 동시성 제어 기준. */
    public val version: Long,
    public val ruleSetConfig: RuleSetConfig,
    /** 좌석 순서 = 턴 순서. */
    public val seats: List<PlayerId>,
    public val turn: TurnInfo,
    public val eliminationOrder: List<PlayerId>,
    internal val players: Map<PlayerId, PlayerState>,
    internal val deck: List<Card>,
    internal val phase: Phase,
    internal val currentAction: PendingAction?,
    internal val stack: List<ResolutionStep>,
    internal val rng: DeterministicRng,
) {
    public val isOver: Boolean get() = phase is Phase.GameOver

    internal fun player(id: PlayerId): PlayerState =
        checkNotNull(players[id]) { "unknown player ${id.value}" }

    internal val alivePlayers: List<PlayerId> get() = seats.filter { player(it).isAlive }
}
