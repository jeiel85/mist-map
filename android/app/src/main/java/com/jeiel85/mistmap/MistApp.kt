package com.jeiel85.mistmap

import android.app.Application
import android.util.Log
import com.jeiel85.mistmap.data.FixDatabase
import com.jeiel85.mistmap.data.MistDatabase
import com.jeiel85.mistmap.data.TrackRepository
import com.jeiel85.mistmap.tracking.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class MistApp : Application() {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val repository by lazy { TrackRepository(MistDatabase.open(this), FixDatabase.open(this)) }

  /** 프로세스 시작 정리. 기록 서비스는 이것이 끝난 뒤 새 세션을 연다. */
  lateinit var startup: Deferred<Unit>
    private set

  override fun onCreate() {
    super.onCreate()
    startup = appScope.async {
      // 프로세스가 새로 떴다면 열린 세션은 모두 닫는다 (재시작하지 않음, D3).
      // 기록 중이던 채 죽었으면 끊긴 것이라 알리고, 일시정지해 둔 채였다면 알리지 않는다
      val dangling = repository.closeDanglingSessions()
      if (dangling > 0 && TrackingState.savedStatus(this@MistApp) == TrackingState.Status.RECORDING) {
        TrackingState.interrupted.value = true
      }
      TrackingState.saveStatus(this@MistApp, TrackingState.Status.OFF)
      val pruned = repository.pruneOldFixes()
      if (pruned > 0) Log.i(TAG, "30일 지난 원시 위치 $pruned 건 삭제")
    }
  }

  companion object {
    const val TAG = "MistMap"
  }
}
