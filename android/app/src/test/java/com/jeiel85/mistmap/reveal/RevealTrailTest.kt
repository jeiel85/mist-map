package com.jeiel85.mistmap.reveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealTrailTest {
  private val start = LatLng(37.5446, 127.0560)
  private fun east(m: Double) = LatLng(start.lat, start.lng + m / 88_300)

  @Test fun freshStartRevealsItsOwnCell() {
    assertEquals(listOf(CellGrid.cellId(start)), RevealTrail().advance(null, start))
  }

  @Test fun samplesEvery24mAcrossShortSteps() {
    val trail = RevealTrail()
    val cells = mutableListOf<Long>()
    cells += trail.advance(null, start)
    // 10m씩 25번 = 약 250m. 위치마다가 아니라 24m마다 표본을 찍어 시작점 포함 11개 (0, 24, …, 240m)
    for (i in 1..25) cells += trail.advance(east((i - 1) * 10.0), east(i * 10.0))
    assertEquals(11, cells.size)
    assertEquals(cells.size, cells.toSet().size)
  }

  @Test fun shortStepAloneRevealsNothingNew() {
    val trail = RevealTrail()
    trail.advance(null, start)
    assertTrue(trail.advance(start, east(10.0)).isEmpty())
  }

  @Test fun afterResetContinuesFromGivenPoint() {
    val trail = RevealTrail()
    trail.advance(null, start)
    trail.reset()
    // 차량으로 1km 이동한 뒤 다시 걷기 시작: 그 사이를 잇지 않는다
    val cells = trail.advance(east(1000.0), east(1030.0))
    assertEquals(listOf(CellGrid.cellId(east(1000.0)), CellGrid.cellId(east(1024.0))), cells)
  }
}
