package com.jeiel85.mistmap.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionClassifierTest {
  private fun fix(sec: Int, northM: Double, speed: Float? = null, accuracy: Float = 8f) =
    Fix(sec * 1000L, 37.5446 + northM / 110_540, 127.0560, accuracy, speed)

  @Test fun becomesStillAfterThreeMinutesInPlace() {
    val m = MotionClassifier()
    var changed: MotionMode? = null
    for (s in 0..180 step 5) changed = m.offer(fix(s, (s % 10).toDouble(), 0f)) ?: changed
    assertEquals(MotionMode.STILL, changed)
  }

  @Test fun leavesStillOnlyBeyondRadiusOrAccuracy() {
    val m = MotionClassifier()
    for (s in 0..185 step 5) m.offer(fix(s, 0.0, 0f))
    assertEquals(MotionMode.STILL, m.mode)
    // 저전력 위치(정확도 60m): 60m 안쪽은 흔들림으로 보고, 넘으면 걷기로 돌아온다
    assertNull(m.offer(fix(300, 50.0, accuracy = 60f)))
    assertEquals(MotionMode.WALKING, m.offer(fix(420, 90.0, accuracy = 60f)))
  }

  @Test fun becomesFastAfterAMinuteOverSixMpsAndBack() {
    val m = MotionClassifier()
    var north = 0.0
    var changed: MotionMode? = null
    for (s in 0..60 step 5) {
      north += 50.0
      changed = m.offer(fix(s, north, 10f)) ?: changed
    }
    assertEquals(MotionMode.FAST, changed)
    for (s in 65..130 step 5) {
      north += 7.0
      changed = m.offer(fix(s, north, 1.4f)) ?: changed
    }
    assertEquals(MotionMode.WALKING, changed)
  }

  @Test fun briefBurstDoesNotSwitch() {
    val m = MotionClassifier()
    for (s in 0..30 step 5) m.offer(fix(s, s * 10.0, 10f))
    for (s in 35..90 step 5) m.offer(fix(s, 300.0 + s, 1.2f))
    assertEquals(MotionMode.WALKING, m.mode)
  }
}
