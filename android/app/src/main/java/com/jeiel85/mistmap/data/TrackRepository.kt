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
  /** 새 칸의 firstAt. 화면은 "이 값보다 큰 칸"을 새 칸으로 보내므로 기기 시계가 뒤로 가도 줄지 않게 한다 */
  private var lastFirstAt = 0L

  private suspend fun nextFirstAt(): Long {
    if (lastFirstAt == 0L) lastFirstAt = cells.latestFirstAt() ?: 0L
    lastFirstAt = maxOf(System.currentTimeMillis(), lastFirstAt + 1)
    return lastFirstAt
  }

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
      // firstAt은 저장한 시각(늘 커짐), lastAt은 실제로 지난 시각. 위치 시각이 기기 시계보다 앞서거나 뒤처져도
      // 화면이 열려 있는 동안 새 칸을 놓치지 않게 한다
      val firstAt = nextFirstAt()
      var added = 0
      for (id in cellIds) {
        if (cells.insert(CellEntity(id, firstAt = firstAt, lastAt = fix.time, visits = 1)) == -1L) {
          cells.touch(id, fix.time)
        } else {
          added++
        }
        cells.link(CellOriginEntity(id, originId))
      }
      added
    }
  }

  /**
   * 가져온 기록을 새 출처로 저장한다. 새 칸의 firstAt은 가져온 시각이라 화면이 새 칸으로 알아보고 걷어 낸다.
   * [label]에는 형식만 넣는다(원본 파일 이름은 자동 백업에 들어가지 않게 화면 쪽이 따로 둔다).
   * @return (출처 id, 새로 밝힌 칸 수)
   */
  suspend fun importCells(label: String, cellIds: List<Long>, at: Long = System.currentTimeMillis()): Pair<Long, Int> =
    mist.withTransaction {
      val originId = origins.insert(OriginEntity(kind = OriginEntity.KIND_IMPORT, label = label, createdAt = at))
      var added = 0
      for (chunk in cellIds.chunked(IMPORT_CHUNK)) {
        added += cells.insertAll(chunk.map { CellEntity(it, firstAt = at, lastAt = at, visits = 1) }).count { it != -1L }
        cells.linkAll(chunk.map { CellOriginEntity(it, originId) })
      }
      originId to added
    }

  fun observeImports(): Flow<List<ImportSummary>> = origins.observeImports()

  /**
   * 가져온 파일 하나를 되돌린다. 그 파일에서만 온 칸이 사라지고, 걸어서 밝힌 칸이나 다른 파일과 겹친 칸은 남는다.
   * 되살릴 수 있게 그 파일이 밝혔던 칸 목록을 함께 돌려준다.
   */
  suspend fun undoImport(originId: Long): UndoneImport = mist.withTransaction {
    val label = origins.labelOf(originId) ?: ""
    val cellIds = cells.idsOf(originId)
    val removed = cells.deleteOnlyFrom(originId)
    // 외래 키 CASCADE에 기대지 않고 연결도 직접 지운다
    origins.deleteLinks(originId)
    origins.delete(originId)
    UndoneImport(label, cellIds, removed)
  }

  /** 가져온 기록이 밝힌 칸 (GPX 내보내기의 웨이포인트) */
  suspend fun importedCellIds(): List<Long> = cells.idsOfKind(OriginEntity.KIND_IMPORT)

  /** GPX 내보내기용 원시 위치 (최근 30일, D4). */
  suspend fun allFixes(): List<FixEntity> = fixes.all()

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
    private const val IMPORT_CHUNK = 500
  }
}

/** 되돌린 가져오기. 되살리기에 쓴다 */
data class UndoneImport(val label: String, val cellIds: List<Long>, val removed: Int)
