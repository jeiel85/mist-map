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
  private val gaps = GapMonitor()
  /** 활동 인식이 켜져 있으면 멈춤(STILL_GPS)은 곧 쉬기로 넘어가므로 GPS 간격을 더 늘린다 */
  private var activityRecognition = false
  private var sleepWatch: Job? = null
  private val sleepWake = SleepWake(WAKE_DISTANCE_M)
  private var sleeping = false
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

  /** 쉬는 동안 다른 앱(지도·날씨 등)이 받은 위치를 곁에서 듣는다. GPS를 따로 켜지 않는다 */
  private val passive = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      val here = result.lastLocation ?: return
      if (sleeping && isRecording() && wakeIfMoved(here)) Log.i(MistApp.TAG, "다른 앱이 받은 위치로 깨움")
    }
  }

  /** 쉬는 동안 받은 위치가 잠들기 전 자리에서 멀면 깨운다 */
  private fun wakeIfMoved(here: Location): Boolean {
    val accuracy = if (here.hasAccuracy()) here.accuracy else Float.MAX_VALUE
    val moved = sleepWake.offer(LatLng(here.latitude, here.longitude), accuracy, TrackingState.lastFix.value?.latLng)
    if (moved) events.trySend(Event.Wake)
    return moved
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
    setStatus(TrackingState.Status.RECORDING)
    TrackingState.interrupted.value = false
    lastMovementAt = System.currentTimeMillis()

    TrackingState.warning.value = null

    lifecycleScope.launch {
      app.startup.await()
      filter.reset(null)
      motion.reset()
      trail.reset()
      policy.reset()
      planSince = System.currentTimeMillis()
      activityRecognition = ActivityTransitions.start(this@TrackingService)
      if (activityRecognition) Log.i(MistApp.TAG, "활동 인식 켬")
      requestUpdates(LocationPlan.WALKING)
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
      // 알림에 보이는 거리 단위가 바뀔 때만 다시 게시한다 (위치마다 게시하면 30분에 120번 넘게 깨움)
      combine(TrackingState.status, repo.todayDistance(), TrackingState.warning) { status, m, warning ->
        Triple(status, TrackingNotifications.distanceStep(m), warning)
      }
        .distinctUntilChanged()
        .collect { (status, step, warning) ->
          if (status != TrackingState.Status.OFF) notifications.show(status, TrackingNotifications.stepToMeters(step), warning)
        }
    }
    lifecycleScope.launch { watchForAutoStop() }
    lifecycleScope.launch { watchHealth() }
  }

  private suspend fun handle(fix: Fix) {
    if (!isRecording()) return
    if (gaps.onFix(System.currentTimeMillis())) TrackingState.warning.value = null
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
    stopSleepWatch()
    setStatus(TrackingState.Status.PAUSED)
    gaps.pause()
    TrackingState.warning.value = null
  }

  private fun resume() {
    if (TrackingState.status.value != TrackingState.Status.PAUSED) return
    setStatus(TrackingState.Status.RECORDING)
    // 멈춘 동안 이동한 곳을 직선으로 잇지 않도록 새로 출발한다
    filter.reset(null)
    motion.reset()
    trail.reset()
    policy.reset()
    planSince = System.currentTimeMillis()
    requestUpdates(LocationPlan.WALKING)
  }

  /**
   * 상태를 기기 안에도 남긴다. 프로세스가 죽은 뒤 다음 실행이 "기록 중에 끊겼는지"(안내함)와
   * "일시정지해 둔 채였는지"(안내하지 않음)를 구별할 수 있게.
   */
  private fun setStatus(status: TrackingState.Status) {
    TrackingState.status.value = status
    TrackingState.saveStatus(this, status)
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
    val before = policy.plan
    val plan = change() ?: return
    Log.i(MistApp.TAG, "위치 요청 방식: $plan")
    // 멈춤에서는 위치를 1~2분 간격으로만 받아, 다시 걷기 시작한 직후의 첫 위치는 꺾인 길을 건너뛴 직선일 수 있다.
    // 거리는 그대로 세되 그 직선은 밝히지 않는다
    if (before == LocationPlan.STILL_GPS && plan != LocationPlan.SLEEP) trail.reset()
    requestUpdates(plan)
  }

  /** 30초마다: 오늘 기록 상태를 쌓고, 위치가 2분 넘게 안 오면 알린다 */
  private suspend fun watchHealth() {
    while (true) {
      delay(30_000)
      if (!isRecording()) continue
      val now = System.currentTimeMillis()
      account(now)
      if (gaps.check(now, policy.plan)) {
        // 끊기기 직전 위치: 이번 기록에서 받아들인 마지막 위치 (지난 기록의 위치는 쓰지 않는다)
        diagnostics.addGap(now, TrackingState.lastFix.value?.takeIf { lastFixAt > 0 }?.latLng)
        TrackingState.warning.value = "위치를 받지 못하고 있어요"
        Log.w(MistApp.TAG, "위치가 ${gaps.silentForMs(now) / 1000}초째 안 옴")
      }
    }
  }

  /**
   * 쉬는 동안(GPS 끔) 활동 인식이 놓친 이동을 잡는다. 다른 앱이 받은 위치를 곁에서 듣고,
   * 그런 위치가 없을 때를 위해 5분마다 한 번은 직접 확인한다.
   */
  @SuppressLint("MissingPermission") // hasLocationPermission()으로 확인
  private fun watchWhileSleeping() {
    stopSleepWatch()
    sleepWake.reset()
    fused.requestLocationUpdates(
      LocationRequest.Builder(Priority.PRIORITY_PASSIVE, PASSIVE_INTERVAL_MS).setMinUpdateIntervalMillis(PASSIVE_INTERVAL_MS).build(),
      passive,
      Looper.getMainLooper(),
    ).addOnFailureListener { e -> Log.w(MistApp.TAG, "쉬는 중 곁에서 듣기 실패, 5분 확인만 씀", e) }
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
        wakeIfMoved(here)
      }
    }
  }

  private fun stopSleepWatch() {
    sleepWatch?.cancel()
    fused.removeLocationUpdates(passive)
  }

  private suspend fun finish() {
    account()
    stopSleepWatch()
    ActivityTransitions.stop(this)
    TrackingState.warning.value = null
    fused.removeLocationUpdates(callback)
    sessionId?.let { repo.endSession(it, lastFixAt.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) }
    sessionId = null
    setStatus(TrackingState.Status.OFF)
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
      // 활동 인식이 있으면 곧 쉬기로 넘어가므로 2분 간격으로 더 아낀다
      LocationPlan.STILL_GPS ->
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (activityRecognition) 120_000 else 60_000).build()
      // 활동 인식이 정지라고 할 때: GPS를 끄고 가끔 한 번만 확인한다
      LocationPlan.SLEEP -> null
    }
    fused.removeLocationUpdates(callback)
    if (request == null) {
      // 일부러 GPS를 끈 것이니 "위치를 못 받음" 경고는 내린다
      gaps.pause()
      TrackingState.warning.value = null
      sleeping = true
      watchWhileSleeping()
      return
    }
    stopSleepWatch()
    if (sleeping) {
      // 쉬는 동안 어떻게 움직였는지 모르니(차로 몇 백 m 갔을 수도) 깨어난 첫 위치를 잠들기 전 점과 잇지 않는다
      sleeping = false
      filter.reset(null)
      trail.reset()
      motion.reset()
    }
    gaps.restart(System.currentTimeMillis()) // 시작·다시 시작·쉬다 깨어날 때 끊김 시간을 새로 잰다
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
    fused.removeLocationUpdates(passive)
    ActivityTransitions.stop(this)
    // 시스템이 서비스를 내리는 경우: 상태 기록은 남겨 두어 다음 실행이 끊김을 알 수 있게 한다
    TrackingState.status.value = TrackingState.Status.OFF
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.jeiel85.mistmap.START"
    const val ACTION_PAUSE = "com.jeiel85.mistmap.PAUSE"
    const val ACTION_RESUME = "com.jeiel85.mistmap.RESUME"
    const val ACTION_STOP = "com.jeiel85.mistmap.STOP"

    private const val SLEEP_CHECK_MS = 5 * 60_000L
    private const val WAKE_DISTANCE_M = 100.0
    /** 곁에서 듣는 위치를 이보다 자주 받지 않는다 (깨우는 데는 충분하다) */
    private const val PASSIVE_INTERVAL_MS = 30_000L

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
