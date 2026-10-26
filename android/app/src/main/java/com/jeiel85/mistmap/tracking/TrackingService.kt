package com.jeiel85.mistmap.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jeiel85.mistmap.MistApp
import com.jeiel85.mistmap.data.todayDistance
import com.jeiel85.mistmap.reveal.RevealTrail
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 사용자가 켠 동안만 도는 위치 포그라운드 서비스 (DECISIONS D3).
 * 위치 → 이동 상태 판단 → 걸러내기 → 칸 밝히기·저장을 한 줄로 차례차례 처리한다.
 */
class TrackingService : LifecycleService() {
  private sealed interface Event {
    data class Located(val fix: Fix) : Event
    data object Stop : Event
  }

  private val app get() = application as MistApp
  private val repo get() = app.repository
  private lateinit var fused: FusedLocationProviderClient
  private lateinit var notifications: TrackingNotifications

  private val events = Channel<Event>(Channel.UNLIMITED)
  private val filter = LocationFilter()
  private val motion = MotionClassifier()
  private val trail = RevealTrail()
  private var sessionId: Long? = null
  private var sessionDay: LocalDate? = null
  private var lastFixAt = 0L
  private var lastMovementAt = 0L
  private val zone get() = ZoneId.systemDefault()

  private val callback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      for (location in result.locations) events.trySend(Event.Located(location.toFix()))
    }
  }

  override fun onCreate() {
    super.onCreate()
    fused = LocationServices.getFusedLocationProviderClient(this)
    notifications = TrackingNotifications(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    when (intent?.action) {
      ACTION_START -> begin()
      ACTION_PAUSE -> pause()
      ACTION_RESUME -> resume()
      ACTION_STOP -> events.trySend(Event.Stop)
      else -> stopSelf(startId)
    }
    // 시스템이 서비스를 죽이면 다시 살리지 않는다. 다음에 앱을 열 때 끊겼다고 알린다.
    return START_NOT_STICKY
  }

  private fun begin() {
    if (TrackingState.status.value != TrackingState.Status.OFF) return
    if (!hasLocationPermission()) {
      Log.w(MistApp.TAG, "위치 권한 없이 기록을 시작하려 함")
      stopSelf()
      return
    }
    // startForegroundService 뒤 몇 초 안에 반드시 포그라운드로 올라가야 한다
    ServiceCompat.startForeground(
      this,
      TrackingNotifications.ID,
      notifications.build(TrackingState.Status.RECORDING, 0.0),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
    )
    TrackingState.status.value = TrackingState.Status.RECORDING
    TrackingState.interrupted.value = false
    lastMovementAt = System.currentTimeMillis()

    lifecycleScope.launch {
      app.startup.await()
      filter.reset(null)
      motion.reset()
      trail.reset()
      requestUpdates(MotionMode.WALKING)
      for (event in events) {
        when (event) {
          is Event.Located -> handle(event.fix)
          Event.Stop -> {
            finish()
            break
          }
        }
      }
    }
    lifecycleScope.launch {
      combine(TrackingState.status, repo.todayDistance()) { status, m -> status to m }
        .distinctUntilChanged()
        .collect { (status, m) -> if (status != TrackingState.Status.OFF) notifications.show(status, m) }
    }
    lifecycleScope.launch { watchForAutoStop() }
  }

  private suspend fun handle(fix: Fix) {
    if (TrackingState.status.value != TrackingState.Status.RECORDING) return
    motion.offer(fix)?.let { mode ->
      Log.i(MistApp.TAG, "이동 상태: $mode")
      requestUpdates(mode)
    }
    val result = filter.offer(fix)
    if (result !is FilterResult.Accepted) return

    val at = result.fix.time
    val sid = sessionFor(at)
    val cells = if (result.reveals) {
      trail.advance(result.revealFrom, result.fix.latLng)
    } else {
      trail.reset() // 빠른 구간은 밝히지 않고, 다시 걸을 때 그 끝에서 새로 잇는다
      emptyList()
    }
    repo.record(sid, result.fix, result.distanceM, cells)
    lastFixAt = at
    if (result.distanceM > 0) lastMovementAt = at
    TrackingState.lastFix.value = result.fix
  }

  /** 자정을 넘기면 세션을 나눠, 날짜별 거리가 세션 시작일로 바로 모이게 한다. */
  private suspend fun sessionFor(at: Long): Long {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val current = sessionId
    if (current != null && day == sessionDay) return current
    if (current != null) repo.endSession(current, lastFixAt.takeIf { it > 0 } ?: at)
    return repo.startSession(at).also {
      sessionId = it
      sessionDay = day
    }
  }

  private fun pause() {
    if (TrackingState.status.value != TrackingState.Status.RECORDING) return
    fused.removeLocationUpdates(callback)
    TrackingState.status.value = TrackingState.Status.PAUSED
  }

  private fun resume() {
    if (TrackingState.status.value != TrackingState.Status.PAUSED) return
    TrackingState.status.value = TrackingState.Status.RECORDING
    // 멈춘 동안 이동한 곳을 직선으로 잇지 않도록 새로 출발한다
    filter.reset(null)
    motion.reset()
    trail.reset()
    requestUpdates(MotionMode.WALKING)
  }

  private suspend fun finish() {
    fused.removeLocationUpdates(callback)
    sessionId?.let { repo.endSession(it, lastFixAt.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) }
    sessionId = null
    TrackingState.status.value = TrackingState.Status.OFF
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** 자정을 넘겨 6시간 넘게 움직임이 없으면 켜 둔 걸 잊은 것으로 보고 끈다. */
  private suspend fun watchForAutoStop() {
    while (true) {
      delay(TimeUnit.MINUTES.toMillis(10))
      val now = System.currentTimeMillis()
      val idle = now - lastMovementAt >= TimeUnit.HOURS.toMillis(6)
      val crossedMidnight = Instant.ofEpochMilli(lastMovementAt).atZone(zone).toLocalDate() != LocalDate.now(zone)
      if (TrackingState.status.value != TrackingState.Status.OFF && idle && crossedMidnight) {
        Log.i(MistApp.TAG, "움직임이 없어 기록을 자동으로 끔")
        events.trySend(Event.Stop)
        return
      }
    }
  }

  @SuppressLint("MissingPermission") // hasLocationPermission()으로 확인
  private fun requestUpdates(mode: MotionMode) {
    if (!hasLocationPermission()) {
      events.trySend(Event.Stop)
      return
    }
    val request = when (mode) {
      MotionMode.WALKING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
        .setMinUpdateIntervalMillis(2_000)
        .build()
      // 저전력 위치는 정확도 필터(35m)에 걸러지므로 빠른 이동에도 고정밀을 쓰되 간격을 늘린다
      MotionMode.FAST -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000).build()
      MotionMode.STILL -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000).build()
    }
    fused.removeLocationUpdates(callback)
    fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
      .addOnFailureListener { e ->
        Log.e(MistApp.TAG, "위치 요청 실패", e)
        events.trySend(Event.Stop)
      }
  }

  private fun hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  override fun onDestroy() {
    fused.removeLocationUpdates(callback)
    TrackingState.status.value = TrackingState.Status.OFF
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.jeiel85.mistmap.START"
    const val ACTION_PAUSE = "com.jeiel85.mistmap.PAUSE"
    const val ACTION_RESUME = "com.jeiel85.mistmap.RESUME"
    const val ACTION_STOP = "com.jeiel85.mistmap.STOP"

    fun intent(context: Context, action: String) =
      Intent(context, TrackingService::class.java).setAction(action)

    fun start(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_START))

    fun send(context: Context, action: String) {
      context.startService(intent(context, action))
    }
  }
}

private fun Location.toFix() = Fix(
  time = time,
  lat = latitude,
  lng = longitude,
  accuracy = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
  speed = if (hasSpeed()) speed else null,
)
