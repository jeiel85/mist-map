package com.jeiel85.mistmap.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPolicyTest {
  @Test fun withoutActivityRecognitionFollowsSpeed() {
    val p = MotionPolicy()
    assertEquals(LocationPlan.STILL_GPS, p.onSpeedMode(MotionMode.STILL))
    assertEquals(LocationPlan.FAST, p.onSpeedMode(MotionMode.FAST))
    assertEquals(LocationPlan.WALKING, p.onSpeedMode(MotionMode.WALKING))
    assertNull(p.onSpeedMode(MotionMode.WALKING))
  }

  @Test fun stillFromActivityRecognitionTurnsGpsOff() {
    val p = MotionPolicy()
    assertEquals(LocationPlan.SLEEP, p.onActivity(SensedActivity.STILL, entered = true))
    // 쉬는 동안 속도 분류가 무엇이라 하든 그대로 잔다
    assertNull(p.onSpeedMode(MotionMode.STILL))
    assertEquals(LocationPlan.STILL_GPS, p.onActivity(SensedActivity.STILL, entered = false))
  }

  @Test fun startingToWalkEndsSleep() {
    val p = MotionPolicy()
    p.onActivity(SensedActivity.STILL, entered = true)
    assertEquals(LocationPlan.WALKING, p.onActivity(SensedActivity.ON_FOOT, entered = true))
  }

  @Test fun vehicleUsesFastPlanAndWalkingAfterwardReturns() {
    val p = MotionPolicy()
    assertEquals(LocationPlan.FAST, p.onActivity(SensedActivity.IN_VEHICLE, entered = true))
    assertEquals(LocationPlan.WALKING, p.onActivity(SensedActivity.ON_FOOT, entered = true))
  }

  @Test fun wakeOverridesMissedStillExit() {
    val p = MotionPolicy()
    p.onActivity(SensedActivity.STILL, entered = true)
    assertEquals(LocationPlan.WALKING, p.wake())
  }
}
