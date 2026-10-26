package com.jeiel85.mistmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 칸·세션·출처. 자동 백업에 들어간다. */
@Database(
  entities = [CellEntity::class, SessionEntity::class, OriginEntity::class, CellOriginEntity::class],
  version = 1,
  exportSchema = true,
)
abstract class MistDatabase : RoomDatabase() {
  abstract fun cells(): CellDao
  abstract fun sessions(): SessionDao
  abstract fun origins(): OriginDao

  companion object {
    const val FILE = "mist.db"
    fun open(context: Context) = Room.databaseBuilder(context, MistDatabase::class.java, FILE).build()
  }
}

/** 원시 위치만 담는 DB. 백업 규칙에서 이 파일을 뺀다 (res/xml/backup_rules.xml). */
@Database(entities = [FixEntity::class], version = 1, exportSchema = true)
abstract class FixDatabase : RoomDatabase() {
  abstract fun fixes(): FixDao

  companion object {
    const val FILE = "fixes.db"
    fun open(context: Context) = Room.databaseBuilder(context, FixDatabase::class.java, FILE).build()
  }
}
