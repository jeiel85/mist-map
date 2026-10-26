package com.jeiel85.mistmap.tracking

/** 위치를 어떻게 받을지. [SLEEP]은 GPS를 끄고 가끔 한 번만 확인한다. */
enum class LocationPlan { WALKING, FAST, STILL_GPS, SLEEP }

/** 활동 인식(Activity Recognition)이 알려 주는 상태 중 우리가 쓰는 것 */
enum class SensedActivity { STILL, ON_FOOT, ON_BICYCLE, IN_VEHICLE }

/**
 * 속도 분류([MotionClassifier])와 활동 인식을 합쳐 위치 요청 방식을 정한다 (설계 문서 3장, M3).
 * - 활동 인식이 "정지"라고 하면 [LocationPlan.SLEEP]: GPS를 끈다. 가만히 있을 때 배터리 목표(시간당 1%)를 위한 핵심
 * - "차량"이면 [LocationPlan.FAST]
 * - 그 밖에는 속도 분류를 따른다. 활동 인식 권한이 없으면 늘 이 경우라 M2까지와 똑같이 동작한다
 * 활동 인식이 정지를 끝내지 못해도 [wake]로 깨울 수 있다 (가끔 확인한 위치가 멀리 옮겨졌을 때).
 */
class MotionPolicy {
  private var speedMode = MotionMode.WALKING
  private var still = false
  private var vehicle = false

  var plan = LocationPlan.WALKING
    private set

  fun reset() {
    speedMode = MotionMode.WALKING
    still = false
    vehicle = false
    plan = LocationPlan.WALKING
  }

  /** @return 요청 방식이 바뀌었으면 새 방식, 아니면 null */
  fun onSpeedMode(mode: MotionMode): LocationPlan? {
    speedMode = mode
    return update()
  }

  fun onActivity(activity: SensedActivity, entered: Boolean): LocationPlan? {
    when (activity) {
      SensedActivity.STILL -> still = entered
      SensedActivity.IN_VEHICLE -> {
        vehicle = entered
        if (entered) still = false
      }
      SensedActivity.ON_FOOT, SensedActivity.ON_BICYCLE -> if (entered) {
        still = false
        vehicle = false
      }
    }
    return update()
  }

  /** 쉬는 동안 확인한 위치가 멀리 옮겨졌다: 활동 인식이 놓쳤더라도 다시 걷기로 */
  fun wake(): LocationPlan? {
    still = false
    speedMode = MotionMode.WALKING
    return update()
  }

  private fun update(): LocationPlan? {
    val next = when {
      still -> LocationPlan.SLEEP
      vehicle -> LocationPlan.FAST
      speedMode == MotionMode.FAST -> LocationPlan.FAST
      speedMode == MotionMode.STILL -> LocationPlan.STILL_GPS
      else -> LocationPlan.WALKING
    }
    if (next == plan) return null
    plan = next
    return next
  }
}
