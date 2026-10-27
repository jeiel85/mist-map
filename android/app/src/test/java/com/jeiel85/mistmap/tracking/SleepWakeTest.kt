package com.jeiel85.mistmap.tracking

import com.jeiel85.mistmap.reveal.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepWakeTest {
  private val home = LatLng(37.5446, 127.0560)
  /** 북쪽으로 [m]미터 */
  private fun north(m: Double) = LatLng(home.lat + m / 111_320.0, home.lng)

  @Test fun wakesOnlyWhenFarFromWhereItFellAsleep() {
    val wake = SleepWake()
    assertFalse(wake.offer(north(60.0), 10f, home))
    assertTrue(wake.offer(north(150.0), 10f, home))
  }

  @Test fun coarseLocationsDoNotWake() {
    // 다른 앱이 받은 와이파이·기지국 위치는 수백 m 틀릴 수 있다
    assertFalse(SleepWake().offer(north(400.0), 300f, home))
    assertTrue(SleepWake().offer(north(400.0), 40f, home))
  }

  @Test fun withoutAnAcceptedFixTheFirstLocationBecomesTheAnchor() {
    val wake = SleepWake()
    assertFalse(wake.offer(home, 10f, null))
    assertFalse(wake.offer(north(80.0), 10f, null))
    assertTrue(wake.offer(north(130.0), 10f, null))
    wake.reset() // 다시 잠들면 기준점도 새로
    assertFalse(wake.offer(north(130.0), 10f, null))
  }
}
