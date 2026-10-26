package com.jeiel85.mistmap.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {
  private val lat0 = 37.5446
  private val lng0 = 127.0560

  /** 시작점에서 북쪽으로 [northM] m, [sec]초 뒤의 위치 */
  private fun fix(sec: Int, northM: Double, accuracy: Float = 8f) =
    Fix(1_000_000L + sec * 1000L, lat0 + northM / 110_540, lng0, accuracy, null)

  @Test fun firstFixStartsFreshAndReveals() {
    val r = LocationFilter().offer(fix(0, 0.0)) as FilterResult.Accepted
    assertTrue(r.reveals)
    assertNull(r.revealFrom)
    assertEquals(0.0, r.distanceM, 0.0)
  }

  @Test fun rejectsInaccurate() {
    assertEquals(
      FilterResult.Rejected(FilterResult.Reason.INACCURATE),
      LocationFilter().offer(fix(0, 0.0, accuracy = 50f)),
    )
  }

  @Test fun walkingStepCountsDistanceAndRevealsFromPrevious() {
    val f = LocationFilter()
    f.offer(fix(0, 0.0))
    val r = f.offer(fix(10, 14.0)) as FilterResult.Accepted
    assertTrue(r.reveals)
    assertEquals(14.0, r.distanceM, 0.1)
    assertEquals(lat0, r.revealFrom!!.lat, 1e-9)
  }

  @Test fun jitterUnder8mIsDropped() {
    val f = LocationFilter()
    f.offer(fix(0, 0.0))
    assertEquals(FilterResult.Rejected(FilterResult.Reason.JITTER), f.offer(fix(5, 5.0)))
  }

  @Test fun jumpOver60mpsIsDroppedAndNotUsedAsAnchor() {
    val f = LocationFilter()
    f.offer(fix(0, 0.0))
    assertEquals(FilterResult.Rejected(FilterResult.Reason.JUMP), f.offer(fix(5, 400.0)))
    val next = f.offer(fix(10, 12.0)) as FilterResult.Accepted
    assertEquals(12.0, next.distanceM, 0.1)
  }

  @Test fun fastSegmentIsStoredButNotRevealed() {
    val f = LocationFilter()
    f.offer(fix(0, 0.0))
    val r = f.offer(fix(10, 150.0)) as FilterResult.Accepted // 15 m/s, 차량
    assertFalse(r.reveals)
    assertEquals(0.0, r.distanceM, 0.0)
  }

  @Test fun longGapStartsFresh() {
    val f = LocationFilter()
    f.offer(fix(0, 0.0))
    val r = f.offer(fix(11 * 60, 500.0)) as FilterResult.Accepted
    assertTrue(r.reveals)
    assertNull(r.revealFrom)
    assertEquals(0.0, r.distanceM, 0.0)
  }

  @Test fun outOfOrderIsDropped() {
    val f = LocationFilter()
    f.offer(fix(10, 0.0))
    assertEquals(FilterResult.Rejected(FilterResult.Reason.OUT_OF_ORDER), f.offer(fix(5, 20.0)))
  }
}
