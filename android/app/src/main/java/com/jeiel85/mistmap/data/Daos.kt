package com.jeiel85.mistmap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CellDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(cell: CellEntity): Long

  @Query("UPDATE cell SET lastAt = :at, visits = visits + 1 WHERE cellId = :cellId")
  suspend fun touch(cellId: Long, at: Long)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun link(link: CellOriginEntity)

  /** @return 칸마다 새로 넣은 행 ID, 이미 있던 칸은 -1 */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(cells: List<CellEntity>): List<Long>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun linkAll(links: List<CellOriginEntity>)

  /** [originId]에만 속한 칸을 지운다. 다른 출처(실시간 기록·다른 파일)도 밝힌 칸은 남는다. */
  @Query(
    "DELETE FROM cell WHERE cellId IN (SELECT cellId FROM cell_origin WHERE originId = :originId) " +
      "AND cellId NOT IN (SELECT cellId FROM cell_origin WHERE originId != :originId)",
  )
  suspend fun deleteOnlyFrom(originId: Long): Int

  @Query("SELECT cellId FROM cell")
  suspend fun allIds(): List<Long>

  /** 칸이 늘거나 바뀔 때마다 알린다. 값은 최근 밝힌 시각(새 칸만 골라 보내는 기준). */
  @Query("SELECT MAX(firstAt) FROM cell")
  fun observeLatestFirstAt(): Flow<Long?>

  @Query("SELECT MAX(firstAt) FROM cell")
  suspend fun latestFirstAt(): Long?

  @Query("SELECT cellId FROM cell WHERE firstAt > :after")
  suspend fun idsFirstSeenAfter(after: Long): List<Long>
}

@Dao
interface SessionDao {
  @Insert
  suspend fun insert(session: SessionEntity): Long

  @Query("UPDATE session SET distanceM = distanceM + :addM WHERE id = :id")
  suspend fun addDistance(id: Long, addM: Double)

  @Query("UPDATE session SET endedAt = :at WHERE id = :id")
  suspend fun end(id: Long, at: Long)

  /** 앱이 강제 종료되어 끝나지 못한 구간. */
  @Query("SELECT * FROM session WHERE endedAt IS NULL")
  suspend fun open(): List<SessionEntity>

  @Query("SELECT COALESCE(SUM(distanceM), 0) FROM session WHERE startedAt >= :from AND startedAt < :until")
  fun observeDistance(from: Long, until: Long): Flow<Double>
}

@Dao
interface OriginDao {
  @Query("SELECT * FROM origin WHERE kind = :kind ORDER BY id LIMIT 1")
  suspend fun firstOfKind(kind: String): OriginEntity?

  @Insert
  suspend fun insert(origin: OriginEntity): Long

  @Query("DELETE FROM origin WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM cell_origin WHERE originId = :id")
  suspend fun deleteLinks(id: Long)

  @Query(
    "SELECT o.id AS id, o.label AS label, o.createdAt AS createdAt, COUNT(co.cellId) AS cells " +
      "FROM origin o LEFT JOIN cell_origin co ON co.originId = o.id " +
      "WHERE o.kind = 'IMPORT' GROUP BY o.id ORDER BY o.createdAt DESC",
  )
  fun observeImports(): Flow<List<ImportSummary>>
}

/** 가져온 파일 하나. [cells]는 이 파일이 밝힌 칸 수(다른 출처와 겹치는 칸 포함). */
data class ImportSummary(val id: Long, val label: String, val createdAt: Long, val cells: Int)

@Dao
interface FixDao {
  @Insert
  suspend fun insert(fix: FixEntity): Long

  @Query("SELECT * FROM fix ORDER BY time DESC LIMIT 1")
  suspend fun latest(): FixEntity?

  @Query("SELECT * FROM fix ORDER BY sessionId, time")
  suspend fun all(): List<FixEntity>

  @Query("SELECT MAX(time) FROM fix WHERE sessionId = :sessionId")
  suspend fun lastTimeOf(sessionId: Long): Long?

  @Query("DELETE FROM fix WHERE time < :before")
  suspend fun deleteBefore(before: Long): Int
}
