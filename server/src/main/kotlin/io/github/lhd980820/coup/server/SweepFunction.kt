package io.github.lhd980820.coup.server

import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.functions.CloudEventsFunction
import io.cloudevents.CloudEvent
import io.github.lhd980820.coup.judge.GameSweep
import io.github.lhd980820.coup.judge.GameSweeper
import io.github.lhd980820.coup.judge.RoomSweep
import io.github.lhd980820.coup.judge.RoomSweeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

/**
 * 정리 Cloud Function(2세대, Java 런타임, ADR 0016). Cloud Scheduler가 몇 분마다 Pub/Sub 주제(`coup-sweep`)에 메시지를 보내면 불린다.
 * 먼저 버려진 판을 끝내고(방장만 패배로 반영), 그다음 버려진 방을 닫는다 — 같은 실행에서 끝낸 판의 방은 다음 실행들에서 닫힌다.
 *
 * 한 판·한 방의 오류는 그것만 건너뛰고 기록한다. 함수 자체는 실패로 끝내지 않는다(몇 분 뒤 다음 실행이 다시 본다 — 재시도 폭주 방지).
 */
public class SweepFunction internal constructor(private val sweepers: () -> Sweepers) : CloudEventsFunction {
    /** 함수 프레임워크가 부르는 생성자: 기본 프로젝트의 Firestore(관리자 권한). */
    public constructor() : this({ defaultSweepers })

    internal class Sweepers(val games: GameSweeper, val rooms: RoomSweeper)

    override fun accept(event: CloudEvent) {
        val s = sweepers()
        val (games, rooms) = runBlocking(Dispatchers.IO) { s.games.sweepGames() to s.rooms.sweepRooms() }
        games.forEach { (id, outcome) ->
            when (outcome) {
                is GameSweep.Abandoned -> log.info("game $id abandoned: $outcome")
                is GameSweep.Failed -> log.warning("game $id not swept: ${outcome.error}")
                GameSweep.Active, GameSweep.NotPlaying -> Unit
            }
        }
        rooms.forEach { (id, outcome) ->
            when (outcome) {
                is RoomSweep.Closed -> log.info("room $id closed: ${outcome.reason}")
                is RoomSweep.Failed -> log.warning("room $id not swept: ${outcome.error}")
                RoomSweep.Active, RoomSweep.NotOpen -> Unit
            }
        }
        log.info(
            "swept ${games.size} games (${games.values.count { it is GameSweep.Abandoned }} abandoned), " +
                "${rooms.size} rooms (${rooms.values.count { it is RoomSweep.Closed }} closed)",
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(SweepFunction::class.java.name)

        val defaultSweepers: Sweepers by lazy {
            val store = AdminDocumentStore(FirestoreOptions.getDefaultInstance().service)
            Sweepers(GameSweeper(store), RoomSweeper(store))
        }
    }
}
