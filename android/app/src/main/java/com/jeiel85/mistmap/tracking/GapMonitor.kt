package com.jeiel85.mistmap.tracking

/**
 * 위치가 한동안 안 오는 것(실내·지하·위치 서비스 꺼짐)을 감지한다. 한 번 끊기면 위치가 다시 오기 전까지 한 번만 알린다.
 * 기록 서비스에서 떼어 내 시간만 넣어 시험할 수 있게 했다.
 */
class GapMonitor(
  private val gapMs: Long = 2 * 60_000L,
  private val stillGapMs: Long = 3 * 60_000L,
) {
  private var lastFixAt = 0L
  var gapping = false
    private set

  /** 기록 시작·다시 시작·쉬기에서 깨어날 때: 지금부터 다시 잰다 */
  fun restart(now: Long) {
    lastFixAt = now
    gapping = false
  }

  /** 위치가 왔다. @return 끊겨 있다가 다시 이어졌으면 true */
  fun onFix(now: Long): Boolean {
    lastFixAt = now
    val wasGapping = gapping
    gapping = false
    return wasGapping
  }

  /** 일부러 위치를 끈 쉬기·일시정지: 끊김으로 보지 않는다 */
  fun pause() {
    gapping = false
  }

  /** @return 이번에 새로 끊김으로 판단했으면 true (알림·기록은 한 번만) */
  fun check(now: Long, plan: LocationPlan): Boolean {
    if (plan == LocationPlan.SLEEP || gapping) return false
    val limit = if (plan == LocationPlan.STILL_GPS) stillGapMs else gapMs
    if (now - lastFixAt <= limit) return false
    gapping = true
    return true
  }

  fun silentForMs(now: Long) = now - lastFixAt
}
