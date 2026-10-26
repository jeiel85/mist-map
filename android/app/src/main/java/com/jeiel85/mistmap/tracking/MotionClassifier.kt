package com.jeiel85.mistmap.tracking

import com.jeiel85.mistmap.reveal.Geo

/** 위치 요청 주기를 정하는 이동 상태 (설계 문서 3장 "배터리를 아끼는 갱신 주기"). */
enum class MotionMode { WALKING, FAST, STILL }

/**
 * 속도와 머문 반경만으로 이동 상태를 고른다.
 * - 6 m/s 넘는 이동이 1분 이어지면 FAST, 6 m/s 이하가 1분 이어지면 다시 WALKING
 * - 3분 동안 반경 30m 안에 있으면 STILL, 그 반경(또는 정확도)을 벗어나면 WALKING
 *
 * 걸러내기 전의 위치를 모두 넣는다. STILL에서는 저전력 위치(정확도 100m 안쪽)로만 벗어남을 판단한다.
 */
class MotionClassifier(
  private val fastSpeed: Double = 6.0,
  private val fastHoldMs: Long = 60_000L,
  private val stillRadiusM: Double = 30.0,
  private val stillHoldMs: Long = 3 * 60_000L,
  private val preciseAccuracyM: Float = 35f,
  private val coarseAccuracyM: Float = 100f,
) {
  var mode = MotionMode.WALKING
    private set

  private var prev: Fix? = null
  private var speedSince: Long? = null // 현재 모드와 반대 방향의 속도가 시작된 시각
  private var anchor: Fix? = null      // 머무는지 재는 기준점

  fun reset() {
    mode = MotionMode.WALKING
    prev = null
    speedSince = null
    anchor = null
  }

  /** 상태가 바뀌었으면 새 상태를, 아니면 null을 돌려준다. */
  fun offer(fix: Fix): MotionMode? {
    val before = mode
    if (mode == MotionMode.STILL) {
      val a = anchor
      if (fix.accuracy <= coarseAccuracyM && a != null &&
        Geo.distanceM(a.latLng, fix.latLng) > maxOf(stillRadiusM, fix.accuracy.toDouble())
      ) {
        mode = MotionMode.WALKING
        anchor = fix
        speedSince = null
        prev = fix
      }
      return mode.takeIf { it != before }
    }
    if (fix.accuracy > preciseAccuracyM) return null

    val p = prev
    val speed = fix.speed?.toDouble()
      ?: if (p != null && fix.time > p.time) {
        Geo.distanceM(p.latLng, fix.latLng) / ((fix.time - p.time) / 1000.0)
      } else 0.0
    prev = fix

    val wantsFast = speed > fastSpeed
    val opposite = if (mode == MotionMode.FAST) !wantsFast else wantsFast
    if (opposite) {
      val since = speedSince ?: fix.time.also { speedSince = it }
      if (fix.time - since >= fastHoldMs) {
        mode = if (wantsFast) MotionMode.FAST else MotionMode.WALKING
        speedSince = null
        anchor = fix
      }
    } else {
      speedSince = null
    }

    val a = anchor
    if (a == null || Geo.distanceM(a.latLng, fix.latLng) > stillRadiusM) {
      anchor = fix
    } else if (mode == MotionMode.WALKING && fix.time - a.time >= stillHoldMs) {
      mode = MotionMode.STILL
    }
    return mode.takeIf { it != before }
  }
}
