package com.jeiel85.mistmap.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 밝힌 칸 (DECISIONS D2). */
@Entity(tableName = "cell")
data class CellEntity(
  @PrimaryKey val cellId: Long,
  val firstAt: Long,
  val lastAt: Long,
  val visits: Int,
)

/** 기록을 켠 한 구간. 자정을 넘기면 나눠서 날짜별 거리를 바로 합칠 수 있게 한다. */
@Entity(tableName = "session", indices = [Index("startedAt")])
data class SessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long,
  val endedAt: Long? = null,
  val distanceM: Double = 0.0,
)

/** 칸이 어디서 왔는지. 가져온 파일을 되돌릴 때 그 출처에만 속한 칸을 지운다. */
@Entity(tableName = "origin")
data class OriginEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val kind: String,
  val label: String,
  val createdAt: Long,
) {
  companion object {
    const val KIND_LIVE = "LIVE"
    const val KIND_IMPORT = "IMPORT"
  }
}

@Entity(
  tableName = "cell_origin",
  primaryKeys = ["cellId", "originId"],
  foreignKeys = [
    ForeignKey(entity = CellEntity::class, parentColumns = ["cellId"], childColumns = ["cellId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = OriginEntity::class, parentColumns = ["id"], childColumns = ["originId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("originId")],
)
data class CellOriginEntity(val cellId: Long, val originId: Long)

/**
 * 원시 위치. 가장 민감한 데이터라 별도 DB 파일(fixes.db)에 두어 자동 백업에서 빼고, 30일 뒤 지운다 (D4).
 * 다른 DB에 있으므로 [sessionId]는 외래 키 없이 값만 가진다.
 */
@Entity(tableName = "fix", indices = [Index("time")])
data class FixEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val time: Long,
  val lat: Double,
  val lng: Double,
  val accuracy: Float,
  val speed: Float?,
)
