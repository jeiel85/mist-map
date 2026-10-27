package com.jeiel85.mistmap.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapMonitorTest {
  private val min = 60_000L

  @Test fun warnsOnceAfterTwoMinutesWithoutFixes() {
    val g = GapMonitor()
    g.restart(0)
    assertFalse(g.check(2 * min, LocationPlan.WALKING))
    assertTrue(g.check(2 * min + 1, LocationPlan.WALKING))
    // 계속 끊겨 있어도 다시 알리지 않는다
    assertFalse(g.check(5 * min, LocationPlan.WALKING))
    // 위치가 다시 오면 이어졌다고 알리고, 다음 끊김은 또 알린다
    assertTrue(g.onFix(6 * min))
    assertTrue(g.check(8 * min + 1, LocationPlan.WALKING))
  }

  @Test fun stillPlanWaitsLonger() {
    val g = GapMonitor()
    g.restart(0)
    assertFalse(g.check(2 * min + 1, LocationPlan.STILL_GPS))
    assertTrue(g.check(3 * min + 1, LocationPlan.STILL_GPS))
  }

  @Test fun sleepIsNeverAGap() {
    val g = GapMonitor()
    g.restart(0)
    assertFalse(g.check(60 * min, LocationPlan.SLEEP))
  }

  // PR #9 리뷰: 일시정지로 경고를 내린 뒤 다시 걸었는데 계속 끊겨 있으면 다시 알려야 한다
  @Test fun pauseAndRestartAllowANewWarning() {
    val g = GapMonitor()
    g.restart(0)
    assertTrue(g.check(3 * min, LocationPlan.WALKING))
    g.pause()
    g.restart(4 * min)
    assertFalse(g.check(5 * min, LocationPlan.WALKING))
    assertTrue(g.check(6 * min + 1, LocationPlan.WALKING))
  }
}
