package com.jeiel85.mistmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.jeiel85.mistmap.MistApp
import com.jeiel85.mistmap.data.todayDistance
import com.jeiel85.mistmap.renderer.RendererBridge
import com.jeiel85.mistmap.tracking.Fix
import com.jeiel85.mistmap.tracking.TrackingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MapViewModel(app: Application) : AndroidViewModel(app) {
  private val repo = (app as MistApp).repository

  val status: StateFlow<TrackingState.Status> = TrackingState.status
  val todayM: StateFlow<Double> = repo.todayDistance()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

  private val _notice = MutableStateFlow<Notice?>(null)
  val notice = _notice.asStateFlow()

  private val _rendererError = MutableStateFlow<String?>(null)
  val rendererError = _rendererError.asStateFlow()

  private var bridgeJob: Job? = null
  private var insets = 0 to 0
  private var bridge: RendererBridge? = null

  init {
    viewModelScope.launch {
      (app as MistApp).startup.await()
      if (TrackingState.interrupted.value && status.value == TrackingState.Status.OFF) {
        TrackingState.interrupted.value = false
        show(Notice("지난 기록이 중간에 끊겼어요. 휴대폰 절전 설정이 앱을 닫았을 수 있어요."))
      }
    }
  }

  fun show(notice: Notice) {
    _notice.value = notice
  }

  fun dismissNotice(notice: Notice) {
    if (_notice.value == notice) _notice.value = null
  }

  /** 렌더러가 (다시) 연결되면 칸 전체와 위치를 보내고, 이후 새로 밝힌 칸과 이동을 이어서 보낸다. */
  fun attach(next: RendererBridge?) {
    bridgeJob?.cancel()
    bridge?.close()
    bridge = next
    if (next == null) return
    _rendererError.value = null
    bridgeJob = viewModelScope.launch {
      (getApplication<MistApp>()).startup.await()
      next.sendInsets(insets.first, insets.second)
      initialPlayer()?.let(next::sendPlayer)

      val snapshot = repo.cellSnapshot()
      val sent = HashSet<Long>(snapshot.ids)
      next.sendCells(RendererBridge.CELLS_SET, snapshot.ids)
      var after = snapshot.latestFirstAt
      launch {
        repo.observeLatestFirstAt().filterNotNull().collect { latest ->
          if (latest <= after) return@collect
          val fresh = repo.cellsFirstSeenAfter(after).filter(sent::add)
          after = latest
          if (fresh.isNotEmpty()) next.sendCells(RendererBridge.CELLS_ADD, fresh)
        }
      }
      launch { TrackingState.lastFix.filterNotNull().collect(next::sendPlayer) }
    }
  }

  fun updateInsets(topDp: Int, bottomDp: Int) {
    if (insets == topDp to bottomDp) return
    insets = topDp to bottomDp
    bridge?.sendInsets(topDp, bottomDp)
  }

  fun onRendererMessage(type: String, message: String?) {
    when (type) {
      "ready" -> Log.i(MistApp.TAG, "렌더러 준비 완료")
      "error" -> {
        Log.e(MistApp.TAG, "렌더러 오류: $message")
        _rendererError.value = message ?: "알 수 없는 오류"
      }
      else -> Log.w(MistApp.TAG, "알 수 없는 렌더러 메시지: $type")
    }
  }

  /** 캐릭터를 처음 둘 곳: 기록 중 위치 → 저장된 마지막 위치 → 기기의 마지막 위치. */
  @SuppressLint("MissingPermission") // 권한을 확인한 뒤에만 부른다
  private suspend fun initialPlayer(): Fix? {
    TrackingState.lastFix.value?.let { return it }
    repo.latestFix()?.let { return it }
    val context = getApplication<Application>()
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    return try {
      LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        ?.let { Fix(it.time, it.latitude, it.longitude, it.accuracy, null) }
    } catch (e: Exception) {
      Log.w(MistApp.TAG, "마지막 위치를 가져오지 못함", e)
      null
    }
  }

  override fun onCleared() {
    bridge?.close()
  }
}

/** 화면 위쪽에 잠깐 뜨는 안내. [action]이 있으면 눌렀을 때 실행한다. */
data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)
