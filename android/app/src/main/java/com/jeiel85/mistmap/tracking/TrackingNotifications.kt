package com.jeiel85.mistmap.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jeiel85.mistmap.R
import com.jeiel85.mistmap.ui.MainActivity
import java.util.Locale

/** "안개를 걷는 중 · 오늘 2.4km" 상시 알림과 일시정지·끄기 버튼. */
class TrackingNotifications(private val context: Context) {
  init {
    val channel = NotificationChannel(CHANNEL, "기록 중 알림", NotificationManager.IMPORTANCE_LOW).apply {
      description = "걸은 길을 기록하는 동안 떠 있는 알림"
      setShowBadge(false)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun build(status: TrackingState.Status, todayM: Double): Notification {
    val km = String.format(Locale.KOREA, "%.1f", todayM / 1000)
    val paused = status == TrackingState.Status.PAUSED
    val open = PendingIntent.getActivity(
      context, 0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(if (paused) "잠시 멈춤" else "안개를 걷는 중")
      .setContentText("오늘 ${km}km")
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(
        0,
        if (paused) "다시 걷기" else "일시정지",
        action(if (paused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE, 1),
      )
      .addAction(0, "끄기", action(TrackingService.ACTION_STOP, 2))
      .build()
  }

  fun show(status: TrackingState.Status, todayM: Double) {
    // 알림 권한이 없어도 서비스는 돈다. 알림만 보이지 않을 뿐이다.
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
    ) return
    NotificationManagerCompat.from(context).notify(ID, build(status, todayM))
  }

  private fun action(action: String, code: Int) = PendingIntent.getService(
    context, code, TrackingService.intent(context, action),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )

  companion object {
    const val ID = 1
    private const val CHANNEL = "tracking"
  }
}
