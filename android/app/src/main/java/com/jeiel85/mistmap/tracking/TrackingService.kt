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
import com.jeiel85.mistmap.reveal.Geo
import com.jeiel85.mistmap.reveal.LatLng
import com.jeiel85.mistmap.reveal.RevealTrail
import kotlinx.coroutines.Job
import kotlinx.coroutines.tasks.await
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
    data class Sensed(val activity: SensedActivity, val entered: Boolean) : Event
    /** 쉬는 동안 확인한 위치가 멀리 옮겨졌다 */
    data object Wake : Event
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
  private val policy = MotionPolicy()
  private val diagnostics by lazy { Diagnostics(this) }
  private var planSince = 0L
  private var lastRawAt = 0L
  private var gapping = false
  private var sleepWatch: Job? = null
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
    // 꺼진 서비스로 알림 버튼(일시정지·끄기 등)이 늦게 도착하면 할 일이 없으니 바로 내린다.
    // 그냥 두면 포그라운드도 아닌 서비스가 백그라운드에 붙잡혀 있게 된다.
    if (intent?.action != ACTION_START && TrackingState.status.value == TrackingState.Status.OFF) {
      stopSelf(startId)
      return START_NOT_STICKY
    }
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
      // start()가 권한을 먼저 확인하므로, 그 사이 권한이 취소된 경우에만 온다
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

    TrackingState.warning.value = null

    lifecycleScope.launch {
      app.startup.await()
      filter.reset(null)
      motion.reset()
      trail.reset()
      policy.reset()
      val now = System.currentTimeMillis()
      planSince = now
      lastRawAt = now
      requestUpdates(LocationPlan.WALKING)
      if (ActivityTransitions.start(this@TrackingService)) Log.i(MistApp.TAG, "활동 인식 켬")
      for (event in events) {
        when (event) {
          is Event.Located -> handle(event.fix)
          is Event.Sensed -> if (isRecording()) decide { policy.onActivity(event.activity, event.entered) }
          Event.Wake -> if (isRecording()) {
            Log.i(MistApp.TAG, "쉬는 중 위치가 옮겨져 깨움")
            motion.reset()
            decide { policy.wake() }
          }
          Event.Stop -> {
            finish()
            break
          }
        }
      }
    }
    lifecycleScope.launch {
      TrackingState.activity.collect { (activity, entered) -> events.trySend(Event.Sensed(activity, entered)) }
    }
    lifecycleScope.launch {
      // 알림은 0.1km 단위로 보이므로 그 값이 바뀔 때만 다시 게시한다 (위치마다 게시하면 30분에 120번 넘게 깨움)
      combine(TrackingState.status, repo.todayDistance(), TrackingState.warning) { status, m, warning ->
        Triple(status, (m / 100).toLong(), warning)
      }
        .distinctUntilChanged()
        .collect { (status, hm, warning) ->
          if (status != TrackingState.Status.OFF) notifications.show(status, hm * 100.0, warning)
        }
    }
    lifecycleScope.launch { watchForAutoStop() }
    lifecycleScope.launch { watchHealth() }
  }

  private suspend fun handle(fix: Fix) {
    if (!isRecording()) return
    lastRawAt = System.currentTimeMillis()
    if (gapping) {
      gapping = false
      TrackingState.warning.value = null
    }
    motion.offer(fix)?.let { mode ->
      Log.i(MistApp.TAG, "이동 상태: $mode")
      decide { policy.onSpeedMode(mode) }
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
    if (!isRecording()) return
    account()
    fused.removeLocationUpdates(callback)
    sleepWatch?.cancel()
    TrackingState.status.value = TrackingState.Status.PAUSED
    TrackingState.warning.value = null
  }

  private fun resume() {
    if (TrackingState.status.value != TrackingState.Status.PAUSED) return
    TrackingState.status.value = TrackingState.Status.RECORDING
    // 멈춘 동안 이동한 곳을 직선으로 잇지 않도록 새로 출발한다
    filter.reset(null)
    motion.reset()
    trail.reset()
    policy.reset()
    planSince = System.currentTimeMillis()
    lastRawAt = planSince
    requestUpdates(LocationPlan.WALKING)
  }

  private fun isRecording() = TrackingState.status.value == TrackingState.Status.RECORDING

  /** 지금 방식으로 보낸 시간을 오늘 기록 상태에 더한다 */
  private fun account(now: Long = System.currentTimeMillis()) {
    if (isRecording()) diagnostics.addTime(policy.plan, now - planSince, now)
    planSince = now
  }

  /** 앞 방식의 시간을 먼저 센 뒤 [MotionPolicy]에 묻고, 방식이 바뀌었으면 위치 요청을 바꾼다 */
  private fun decide(change: () -> LocationPlan?) {
    account()
    val plan = change() ?: return
    Log.i(MistApp.TAG, "위치 요청 방식: $plan")
    requestUpdates(plan)
  }

  /** 30초마다: 오늘 기록 상태를 쌓고, 위치가 2분 넘게 안 오면 알린다 */
  private suspend fun watchHealth() {
    while (true) {
      delay(30_000)
      if (!isRecording()) continue
      val now = System.currentTimeMillis()
      account(now)
      val limit = if (policy.plan == LocationPlan.STILL_GPS) STILL_GAP_MS else GAP_MS
      if (policy.plan != LocationPlan.SLEEP && !gapping && now - lastRawAt > limit) {
        gapping = true
        diagnostics.addGap(now)
        TrackingState.warning.value = "위치를 받지 못하고 있어요"
        Log.w(MistApp.TAG, "위치가 ${(now - lastRawAt) / 1000}초째 안 옴")
      }
    }
  }

  /** 쉬는 동안(GPS 끔) 10분마다 위치를 한 번만 확인해, 활동 인식이 놓친 이동을 잡는다 */
  @SuppressLint("MissingPermission") // hasLocationPermission()으로 확인
  private fun watchWhileSleeping() {
    sleepWatch?.cancel()
    sleepWatch = lifecycleScope.launch {
      while (true) {
        delay(SLEEP_CHECK_MS)
        if (!hasLocationPermission() || !isRecording()) continue
        val here = try {
          fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: Exception) {
          Log.w(MistApp.TAG, "쉬는 중 위치 확인 실패", e)
          null
        } ?: continue
        val last = TrackingState.lastFix.value ?: continue
        if (Geo.distanceM(last.latLng, LatLng(here.latitude, here.longitude)) > WAKE_DISTANCE_M) {
          events.trySend(Event.Wake)
        }
      }
    }
  }

  private suspend fun finish() {
    account()
    sleepWatch?.cancel()
    ActivityTransitions.stop(this)
    TrackingState.warning.value = null
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
  private fun requestUpdates(plan: LocationPlan) {
    if (!hasLocationPermission()) {
      events.trySend(Event.Stop)
      return
    }
    val request = when (plan) {
      LocationPlan.WALKING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
        .setMinUpdateIntervalMillis(2_000)
        .build()
      // 저전력 위치는 정확도 필터(35m)에 걸러지므로 빠른 이동에도 고정밀을 쓰되 간격을 늘린다
      LocationPlan.FAST -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000).build()
      // 활동 인식이 없을 때의 멈춤. 저전력(와이파이·기지국) 위치는 그것이 없는 곳에서 아예 오지 않아
      // 다시 걷기 시작해도 멈춤에서 빠져나오지 못한다. GPS를 쓰되 1분 간격으로 띄엄띄엄 받는다.
      LocationPlan.STILL_GPS -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000).build()
      // 활동 인식이 정지라고 할 때: GPS를 끄고 가끔 한 번만 확인한다
      LocationPlan.SLEEP -> null
    }
    fused.removeLocationUpdates(callback)
    if (request == null) {
      // 일부러 GPS를 끈 것이니 "위치를 못 받음" 경고는 내린다
      gapping = false
      TrackingState.warning.value = null
      watchWhileSleeping()
      return
    }
    sleepWatch?.cancel()
    lastRawAt = System.currentTimeMillis() // 쉬다 깨어나면 끊김 시간을 새로 잰다
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
    ActivityTransitions.stop(this)
    TrackingState.status.value = TrackingState.Status.OFF
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.jeiel85.mistmap.START"
    const val ACTION_PAUSE = "com.jeiel85.mistmap.PAUSE"
    const val ACTION_RESUME = "com.jeiel85.mistmap.RESUME"
    const val ACTION_STOP = "com.jeiel85.mistmap.STOP"

    private const val GAP_MS = 2 * 60_000L
    private const val STILL_GAP_MS = 3 * 60_000L
    private const val SLEEP_CHECK_MS = 10 * 60_000L
    private const val WAKE_DISTANCE_M = 100.0

    fun intent(context: Context, action: String) =
      Intent(context, TrackingService::class.java).setAction(action)

    /**
     * 위치 권한이 있을 때만 포그라운드 서비스로 시작한다. 권한 없이 시작하면 서비스가 startForeground를
     * 부를 수 없어(위치 유형) Android 12+에서 ForegroundServiceDidNotStartInTimeException으로 앱이 죽는다.
     * @return 시작했으면 true
     */
    fun start(context: Context): Boolean {
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        Log.w(MistApp.TAG, "위치 권한이 없어 기록을 시작하지 않음")
        return false
      }
      ContextCompat.startForegroundService(context, intent(context, ACTION_START))
      return true
    }

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
