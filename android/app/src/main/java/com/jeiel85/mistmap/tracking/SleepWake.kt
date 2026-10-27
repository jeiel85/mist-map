package com.jeiel85.mistmap.tracking

import com.jeiel85.mistmap.reveal.Geo
import com.jeiel85.mistmap.reveal.LatLng

/**
 * 쉬는 동안(GPS 끔) 들어온 위치로 깨울지 정한다. 5분마다 직접 확인한 위치와,
 * 다른 앱이 받은 위치를 곁에서 듣는 것(PASSIVE, 배터리를 더 쓰지 않음) 둘 다 여기로 온다.
 * 기록 서비스에서 떼어 내 위치만 넣어 시험할 수 있게 했다.
 */
class SleepWake(
  private val wakeDistanceM: Double = 100.0,
  /** 와이파이·기지국 위치는 수백 m 틀릴 수 있어, 그것만으로 깨우면 GPS를 헛되이 켠다 */
  private val maxAccuracyM: Float = 50f,
) {
  /** 쉬기에 들어간 뒤 처음 받은 위치. 기록 시작 직후라 받아들인 위치가 없을 때 비교 기준이 된다 */
  private var anchor: LatLng? = null

  fun reset() {
    anchor = null
  }

  /**
   * @param lastAccepted 잠들기 전 마지막으로 받아들인 위치
   * @return 멀리 옮겨졌으면 true (깨운다)
   */
  fun offer(point: LatLng, accuracyM: Float, lastAccepted: LatLng?): Boolean {
    if (accuracyM > maxAccuracyM) return false
    val base = lastAccepted ?: anchor
    if (base == null) {
      anchor = point
      return false
    }
    return Geo.distanceM(base, point) > wakeDistanceM
  }
}
