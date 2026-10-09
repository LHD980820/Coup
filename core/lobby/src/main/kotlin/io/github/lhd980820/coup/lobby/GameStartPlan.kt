package io.github.lhd980820.coup.lobby

import io.github.lhd980820.coup.ai.AiDifficulty
import io.github.lhd980820.coup.ai.AiFactory
import io.github.lhd980820.coup.ai.Personality
import io.github.lhd980820.coup.engine.core.GameSetup
import io.github.lhd980820.coup.engine.model.PlayerId
import io.github.lhd980820.coup.engine.rules.RuleSetConfig
import io.github.lhd980820.coup.runtime.session.HostSeat
import kotlin.random.Random

/** 게임에 앉을 한 자리. 사람이면 [PlayerId]가 uid, 봇이면 방 안의 봇 ID. */
public data class PlannedSeat(
    val playerId: PlayerId,
    val displayName: String,
    val kind: SeatKind,
    val botLevel: BotLevel? = null,
    val botStyle: BotStyle? = null,
)

/**
 * 방이 시작될 때 만들어지는 게임 계획: 섞인 좌석 순서, 룰셋, seed, 레이팅 여부.
 * 방장 기기가 이것으로 [GameSetup]과 [HostSeat]들을 만들어 `HostGameSession`을 연다.
 * 같은 방 + 같은 seed면 항상 같은 계획이다.
 */
public data class GameStartPlan(
    val roomId: String,
    val gameId: String,
    val hostUid: String,
    val seats: List<PlannedSeat>,
    val ruleSetConfig: RuleSetConfig,
    val seed: Long,
    val rated: Boolean,
) {
    public fun setup(): GameSetup = GameSetup(gameId, seats.map { it.playerId }, ruleSetConfig, seed)

    /** 방장 본인은 Local, 다른 사람은 Remote, 봇은 AI 에이전트로 만든다. */
    public fun hostSeats(): List<HostSeat> = seats.mapIndexed { i, s ->
        when (s.kind) {
            SeatKind.HUMAN ->
                if (s.playerId.value == hostUid) HostSeat.Local(s.playerId, s.displayName) else HostSeat.Remote(s.playerId, s.displayName)
            SeatKind.BOT -> HostSeat.Bot(
                s.playerId,
                s.displayName,
                AiFactory.create(
                    difficulty = when (s.botLevel ?: BotLevel.NORMAL) {
                        BotLevel.EASY -> AiDifficulty.EASY
                        BotLevel.NORMAL -> AiDifficulty.NORMAL
                    },
                    personality = when (s.botStyle ?: BotStyle.BALANCED) {
                        BotStyle.BALANCED -> Personality.BALANCED
                        BotStyle.CAUTIOUS -> Personality.CAUTIOUS
                        BotStyle.AGGRESSIVE -> Personality.AGGRESSIVE
                    },
                    seed = seed * 31 + i,
                ),
            )
        }
    }

    public companion object {
        public fun of(room: Room, gameId: String, seed: Long): GameStartPlan {
            val shuffled = room.seats.shuffled(Random(seed))
            return GameStartPlan(
                roomId = room.id,
                gameId = gameId,
                hostUid = room.hostUid,
                seats = shuffled.map { PlannedSeat(PlayerId(it.id), it.displayName, it.kind, it.botLevel, it.botStyle) },
                ruleSetConfig = room.ruleSetConfig,
                seed = seed,
                rated = room.isRated,
            )
        }
    }
}
