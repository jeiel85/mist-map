package com.jeiel85.mistmap.data

import androidx.room.withTransaction
import com.jeiel85.mistmap.tracking.Fix
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/** 기록 서비스와 화면이 함께 쓰는 저장소. 두 DB(칸·원시 위치)를 감춘다. */
class TrackRepository(
  private val mist: MistDatabase,
  private val fixDb: FixDatabase,
) {
  private val cells = mist.cells()
  private val sessions = mist.sessions()
  private val origins = mist.origins()
  private val fixes = fixDb.fixes()

  data class CellSnapshot(val ids: List<Long>, val latestFirstAt: Long)

  suspend fun startSession(at: Long): Long = sessions.insert(SessionEntity(startedAt = at))

  suspend fun endSession(id: Long, at: Long) = sessions.end(id, at)

  /**
   * 걸러낸 위치 하나를 기록한다: 원시 위치 저장, 칸 밝히기(실시간 출처에 연결), 세션 거리 더하기.
   * @return 새로 밝힌 칸 수
   */
  suspend fun record(sessionId: Long, fix: Fix, distanceM: Double, cellIds: List<Long>): Int {
    fixes.insert(
      FixEntity(
        sessionId = sessionId,
        time = fix.time,
        lat = fix.lat,
        lng = fix.lng,
        accuracy = fix.accuracy,
        speed = fix.speed,
      ),
    )
    return mist.withTransaction {
      if (distanceM > 0) sessions.addDistance(sessionId, distanceM)
      if (cellIds.isEmpty()) return@withTransaction 0
      val originId = liveOriginId()
      var added = 0
      for (id in cellIds) {
        if (cells.insert(CellEntity(id, firstAt = fix.time, lastAt = fix.time, visits = 1)) == -1L) {
          cells.touch(id, fix.time)
        } else {
          added++
        }
        cells.link(CellOriginEntity(id, originId))
      }
      added
    }
  }

  private suspend fun liveOriginId(): Long =
    origins.firstOfKind(OriginEntity.KIND_LIVE)?.id
      ?: origins.insert(
        OriginEntity(kind = OriginEntity.KIND_LIVE, label = "실시간 기록", createdAt = System.currentTimeMillis()),
      )

  suspend fun cellSnapshot(): CellSnapshot = mist.withTransaction {
    val ids = cells.allIds()
    CellSnapshot(ids, cells.latestFirstAt() ?: 0L)
  }

  fun observeLatestFirstAt(): Flow<Long?> = cells.observeLatestFirstAt()

  suspend fun cellsFirstSeenAfter(after: Long): List<Long> = cells.idsFirstSeenAfter(after)

  fun observeDistance(from: Long, until: Long): Flow<Double> = sessions.observeDistance(from, until)

  suspend fun latestFix(): Fix? = fixes.latest()?.let { Fix(it.time, it.lat, it.lng, it.accuracy, it.speed) }

  /**
   * 강제 종료 등으로 끝나지 못한 세션을 마지막 위치 시각으로 닫는다.
   * @return 닫은 세션 수 (기록이 끊겼다고 알릴지 정하는 데 쓴다)
   */
  suspend fun closeDanglingSessions(): Int {
    val open = sessions.open()
    for (s in open) sessions.end(s.id, fixes.lastTimeOf(s.id) ?: s.startedAt)
    return open.size
  }

  /** 30일이 지난 원시 위치를 지운다 (D4). */
  suspend fun pruneOldFixes(now: Long = System.currentTimeMillis()): Int =
    fixes.deleteBefore(now - TimeUnit.DAYS.toMillis(FIX_RETENTION_DAYS))

  companion object {
    const val FIX_RETENTION_DAYS = 30L
  }
}
