package com.jeiel85.mistmap.tracking

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 기록 서비스와 화면이 같은 프로세스 안에서 나누는 상태. */
object TrackingState {
  enum class Status { OFF, RECORDING, PAUSED }

  val status = MutableStateFlow(Status.OFF)

  /** 마지막으로 받아들인 위치 (캐릭터 위치). */
  val lastFix = MutableStateFlow<Fix?>(null)

  /** 지난 기록이 앱 종료로 끊겼는지. 화면이 한 번 안내하고 내린다. */
  val interrupted = MutableStateFlow(false)

  /** 활동 인식 전환(활동, 들어감 여부). 수신기 → 기록 서비스 */
  val activity = MutableSharedFlow<Pair<SensedActivity, Boolean>>(
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /** 기록 중 문제(위치를 못 받음 등). 알림에 함께 보인다 */
  val warning = MutableStateFlow<String?>(null)

  private const val PREFS = "tracking"
  private const val KEY_STATUS = "status"

  fun saveStatus(context: Context, status: Status) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, status.name).apply()
  }

  /** 지난 프로세스가 남긴 상태. 기록 중이었다면 강제 종료로 끊긴 것이다 */
  fun savedStatus(context: Context): Status =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, null)
      ?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: Status.OFF
}
