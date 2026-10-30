package io.github.lhd980820.coup.server

import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.functions.CloudEventsFunction
import io.cloudevents.CloudEvent
import io.github.lhd980820.coup.judge.GameSweep
import io.github.lhd980820.coup.judge.GameSweeper
import io.github.lhd980820.coup.judge.Purge
import io.github.lhd980820.coup.judge.RecordPurger
import io.github.lhd980820.coup.judge.RoomSweep
import io.github.lhd980820.coup.judge.RoomSweeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.logging.Logger

/**
 * 정리 Cloud Function(2세대, Java 런타임, ADR 0016, 0021). Cloud Scheduler가 Pub/Sub 주제(`coup-sweep`)에 메시지를 보내면 불린다.
 * - 메시지 본문이 `purge`(하루 한 번 작업): 판정 뒤 보존 기간이 지난 판의 기록과 오래 닫힌 방을 지운다([RecordPurger]).
 * - 그 밖(몇 분마다 작업, 본문 `sweep`): 먼저 버려진 판을 끝내고(방장만 패배로 반영), 그다음 버려진 방을 닫는다 —
 *   같은 실행에서 끝낸 판의 방은 다음 실행들에서 닫힌다.
 *
 * 한 판·한 방의 오류는 그것만 건너뛰고 기록한다. 함수 자체는 실패로 끝내지 않는다(몇 분 뒤 다음 실행이 다시 본다 — 재시도 폭주 방지).
 */
public class SweepFunction internal constructor(private val sweepers: () -> Sweepers) : CloudEventsFunction {
    /** 함수 프레임워크가 부르는 생성자: 기본 프로젝트의 Firestore(관리자 권한). */
    public constructor() : this({ defaultSweepers })

    internal class Sweepers(val games: GameSweeper, val rooms: RoomSweeper, val purger: RecordPurger)

    override fun accept(event: CloudEvent) {
        val s = sweepers()
        if (task(event) == Task.PURGE) return purge(s.purger)
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

    private fun purge(purger: RecordPurger) {
        val (games, rooms) = runBlocking(Dispatchers.IO) { purger.purgeGames() to purger.purgeRooms() }
        (games + rooms).forEach { (id, outcome) ->
            if (outcome is Purge.Failed) log.warning("$id not purged: ${outcome.error}")
        }
        log.info(
            "purged ${games.values.count { it is Purge.Purged }} of ${games.size} ended games " +
                "(${games.values.sumOf { (it as? Purge.Purged)?.deletes ?: 0 }} deletes), " +
                "${rooms.values.count { it is Purge.Purged }} of ${rooms.size} closed rooms",
        )
    }

    internal enum class Task { SWEEP, PURGE }

    internal companion object {
        private val log: Logger = Logger.getLogger(SweepFunction::class.java.name)

        private val defaultSweepers: Sweepers by lazy {
            val store = AdminDocumentStore(FirestoreOptions.getDefaultInstance().service)
            Sweepers(GameSweeper(store), RoomSweeper(store), RecordPurger(store))
        }

        /**
         * 할 일: Pub/Sub 메시지 본문(base64, `message.data`)이 `purge`면 기록 정리, 그 밖(본문 없음·`sweep`·알 수 없음)은 판·방 정리.
         * 알 수 없는 메시지로 기록이 지워지는 일은 없다.
         */
        internal fun task(event: CloudEvent): Task {
            val body = runCatching {
                val payload = event.data?.toBytes()?.decodeToString() ?: return Task.SWEEP
                val data = Json.parseToJsonElement(payload).jsonObject["message"]?.jsonObject?.get("data")?.jsonPrimitive?.content ?: return Task.SWEEP
                Base64.getDecoder().decode(data).decodeToString().trim()
            }.getOrNull()
            return if (body == "purge") Task.PURGE else Task.SWEEP
        }
    }
}
