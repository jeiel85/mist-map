package com.jeiel85.mistmap.reveal

import kotlin.math.floor

/**
 * 걸은 길에 24m마다 표본을 찍고, 표본마다 칸 하나를 밝힌다.
 * 웹 프로토타입(`maybeReveal`)과 같은 간격이라 칸 중심을 블롭으로 그리면 웹과 같은 모양이 된다.
 * 위치 간격(약 10m)이 표본 간격보다 짧으므로 마지막 표본 위치를 기억해 거리를 이어 잰다.
 */
class RevealTrail {
  private var lastSample: LatLng? = null

  /** 빠른 이동·일시정지 뒤처럼 길이 끊겼을 때. 다음 [advance]는 새 출발이 된다. */
  fun reset() {
    lastSample = null
  }

  /**
   * [from]은 이어 걷는 직전 위치(새 출발이면 null), [to]는 새 위치.
   * @return 새로 찍은 표본의 칸 (순서대로, 중복 없음)
   */
  fun advance(from: LatLng?, to: LatLng): List<Long> {
    val cells = LinkedHashSet<Long>()
    var anchor = lastSample
    if (from == null || anchor == null) {
      anchor = from ?: to
      cells += CellGrid.cellId(anchor)
      lastSample = anchor
    }
    val d = Geo.distanceM(anchor, to)
    // 부동소수 오차로 정확히 24m 지점(23.9999…m)을 놓치지 않게 아주 작은 여유를 둔다
    val n = floor(d / SAMPLE_SPACING_M + 1e-6).toInt()
    for (i in 1..n) {
      val p = Geo.lerp(anchor, to, i * SAMPLE_SPACING_M / d)
      cells += CellGrid.cellId(p)
      lastSample = p
    }
    return cells.toList()
  }

  companion object {
    const val SAMPLE_SPACING_M = 24.0
  }
}
