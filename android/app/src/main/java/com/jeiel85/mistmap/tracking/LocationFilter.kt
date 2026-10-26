package com.jeiel85.mistmap.tracking

import com.jeiel85.mistmap.reveal.Geo
import com.jeiel85.mistmap.reveal.LatLng

/** 위치 하나. [time]은 epoch ms, [speed]는 기기가 준 값(m/s)이 없으면 null. */
data class Fix(
  val time: Long,
  val lat: Double,
  val lng: Double,
  val accuracy: Float,
  val speed: Float?,
) {
  val latLng get() = LatLng(lat, lng)
}

sealed interface FilterResult {
  data class Rejected(val reason: Reason) : FilterResult

  /**
   * 저장할 위치. [reveals]가 참이면 칸을 밝힌다:
   * [revealFrom]이 있으면 그 점부터 이 점까지 걸은 선을, 없으면(새 출발) 이 점 하나만.
   */
  data class Accepted(
    val fix: Fix,
    val distanceM: Double,
    val reveals: Boolean,
    val revealFrom: LatLng?,
  ) : FilterResult

  enum class Reason { INACCURATE, JUMP, JITTER, OUT_OF_ORDER }
}

/**
 * 설계 문서 3장 "걸러내는 규칙":
 * 1. 정확도가 [maxAccuracyM]보다 나쁘면 버림
 * 2. 직전 점에서 계산한 속도가 [maxJumpSpeed]를 넘으면(튐) 버림
 * 3. 직전 점에서 [minStepM] 미만이면 저장하지 않음 (제자리 흔들림)
 * 4. [walkMaxSpeed]를 넘는 구간은 저장만 하고 칸은 밝히지 않으며 걸은 거리에도 넣지 않음
 *
 * 직전 점과 [maxGapMs] 넘게 떨어져 있으면(멈춤 모드의 긴 간격, 신호 끊김) 두 점을 잇지 않고 새로 출발한다.
 */
class LocationFilter(
  private val maxAccuracyM: Float = 35f,
  private val maxJumpSpeed: Double = 60.0,
  private val minStepM: Double = 8.0,
  private val walkMaxSpeed: Double = 7.0,
  private val maxGapMs: Long = 10 * 60_000L,
) {
  private var last: Fix? = null

  /** 기록을 다시 시작할 때 이어 붙일 직전 점. null이면 다음 점부터 새로 시작한다. */
  fun reset(lastAccepted: Fix?) {
    last = lastAccepted
  }

  fun offer(fix: Fix): FilterResult {
    if (fix.accuracy > maxAccuracyM) return FilterResult.Rejected(FilterResult.Reason.INACCURATE)
    val prev = last
    if (prev != null && fix.time <= prev.time) {
      return FilterResult.Rejected(FilterResult.Reason.OUT_OF_ORDER)
    }
    if (prev == null || fix.time - prev.time > maxGapMs) {
      last = fix
      return FilterResult.Accepted(fix, 0.0, reveals = true, revealFrom = null)
    }
    val dtSec = (fix.time - prev.time) / 1000.0
    val d = Geo.distanceM(prev.latLng, fix.latLng)
    val speed = d / dtSec
    if (speed > maxJumpSpeed) return FilterResult.Rejected(FilterResult.Reason.JUMP)
    if (d < minStepM) return FilterResult.Rejected(FilterResult.Reason.JITTER)
    last = fix
    val walking = speed <= walkMaxSpeed
    return FilterResult.Accepted(
      fix,
      distanceM = if (walking) d else 0.0,
      reveals = walking,
      revealFrom = if (walking) prev.latLng else null,
    )
  }
}
