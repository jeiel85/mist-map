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
}

@Dao
interface FixDao {
  @Insert
  suspend fun insert(fix: FixEntity): Long

  @Query("SELECT * FROM fix ORDER BY time DESC LIMIT 1")
  suspend fun latest(): FixEntity?

  @Query("SELECT MAX(time) FROM fix WHERE sessionId = :sessionId")
  suspend fun lastTimeOf(sessionId: Long): Long?

  @Query("DELETE FROM fix WHERE time < :before")
  suspend fun deleteBefore(before: Long): Int
}
