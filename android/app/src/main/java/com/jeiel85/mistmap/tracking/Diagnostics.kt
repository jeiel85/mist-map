package com.jeiel85.mistmap.tracking

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 오늘 기록 상태. 실기기에서 하루 종일 기록이 끊기지 않았는지, GPS를 얼마나 켰는지 스스로 볼 수 있게 한다 (M3). */
data class DayDiagnostics(
  val recordingMs: Long,
  /** 위치를 받은(GPS를 켠) 시간 */
  val gpsMs: Long,
  /** 활동 인식이 "정지"라 GPS를 끈 시간 */
  val sleepMs: Long,
  /** 위치를 2분 넘게 받지 못한 횟수 */
  val gaps: Int,
)

/**
 * 하루 단위 누적값을 기기 안(SharedPreferences)에만 둔다. 위치 자체는 담지 않는다.
 * 오늘과 어제 것만 남기고 그 전 날짜 키는 쓸 때 지운다.
 */
class Diagnostics(context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
  private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

  /** [plan] 상태로 [ms]만큼 기록했다. 일시정지 중에는 부르지 않는다. */
  fun addTime(plan: LocationPlan, ms: Long, at: Long = System.currentTimeMillis()) {
    if (ms <= 0) return
    val day = dayOf(at)
    prefs.edit {
      putLong("$day.rec", prefs.getLong("$day.rec", 0) + ms)
      val key = if (plan == LocationPlan.SLEEP) "$day.sleep" else "$day.gps"
      putLong(key, prefs.getLong(key, 0) + ms)
      dropOld(day)
    }
  }

  fun addGap(at: Long = System.currentTimeMillis()) {
    val day = dayOf(at)
    prefs.edit {
      putInt("$day.gaps", prefs.getInt("$day.gaps", 0) + 1)
      dropOld(day)
    }
  }

  fun today(): DayDiagnostics {
    val day = LocalDate.now(zone).toString()
    return DayDiagnostics(
      recordingMs = prefs.getLong("$day.rec", 0),
      gpsMs = prefs.getLong("$day.gps", 0),
      sleepMs = prefs.getLong("$day.sleep", 0),
      gaps = prefs.getInt("$day.gaps", 0),
    )
  }

  private fun dayOf(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

  private fun android.content.SharedPreferences.Editor.dropOld(day: String) {
    val keep = setOf(day, LocalDate.parse(day).minusDays(1).toString())
    for (key in prefs.all.keys) if (key.substringBefore('.') !in keep) remove(key)
  }
}
