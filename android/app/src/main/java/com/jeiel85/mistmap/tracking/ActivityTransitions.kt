package com.jeiel85.mistmap.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.jeiel85.mistmap.MistApp

/**
 * 활동 인식 전환(정지·걷기·자전거·차량에 들어감/나옴)을 받는다.
 * 권한(신체 활동)을 거부했으면 등록하지 않고, 기록은 속도 분류만으로 돈다.
 */
object ActivityTransitions {
  private val watched = listOf(
    DetectedActivity.STILL,
    DetectedActivity.WALKING,
    DetectedActivity.RUNNING,
    DetectedActivity.ON_BICYCLE,
    DetectedActivity.IN_VEHICLE,
  )

  fun hasPermission(context: Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
      PackageManager.PERMISSION_GRANTED

  /** @return 등록했으면 true */
  @SuppressLint("MissingPermission") // hasPermission으로 확인
  fun start(context: Context): Boolean {
    if (!hasPermission(context)) return false
    val transitions = watched.flatMap { type ->
      listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
        ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
      }
    }
    ActivityRecognition.getClient(context)
      .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
      .addOnFailureListener { Log.w(MistApp.TAG, "활동 인식 등록 실패, 속도 분류만 씀", it) }
    return true
  }

  @SuppressLint("MissingPermission")
  fun stop(context: Context) {
    if (!hasPermission(context)) return
    ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
  }

  // 활동 인식 결과를 extras로 채워 넣으므로 MUTABLE이어야 한다
  private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
    context, 0, Intent(context, ActivityTransitionReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )

  fun sensed(type: Int): SensedActivity? = when (type) {
    DetectedActivity.STILL -> SensedActivity.STILL
    DetectedActivity.WALKING, DetectedActivity.RUNNING -> SensedActivity.ON_FOOT
    DetectedActivity.ON_BICYCLE -> SensedActivity.ON_BICYCLE
    DetectedActivity.IN_VEHICLE -> SensedActivity.IN_VEHICLE
    else -> null
  }
}

/** 기록 서비스가 같은 프로세스에서 돌고 있을 때만 의미가 있으므로 공유 흐름으로 넘긴다. */
class ActivityTransitionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    for (event in result.transitionEvents) {
      val activity = ActivityTransitions.sensed(event.activityType) ?: continue
      val entered = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
      Log.i(MistApp.TAG, "활동 인식: $activity ${if (entered) "시작" else "끝"}")
      TrackingState.activity.tryEmit(activity to entered)
    }
  }
}
