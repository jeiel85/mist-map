package com.jeiel85.mistmap.tracking

import kotlinx.coroutines.flow.MutableStateFlow

/** 기록 서비스와 화면이 같은 프로세스 안에서 나누는 상태. */
object TrackingState {
  enum class Status { OFF, RECORDING, PAUSED }

  val status = MutableStateFlow(Status.OFF)

  /** 마지막으로 받아들인 위치 (캐릭터 위치). */
  val lastFix = MutableStateFlow<Fix?>(null)

  /** 지난 기록이 앱 종료로 끊겼는지. 화면이 한 번 안내하고 내린다. */
  val interrupted = MutableStateFlow(false)
}
