package com.jeiel85.mistmap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.view.WindowManager
import java.time.LocalDate
import androidx.core.location.LocationManagerCompat
import com.jeiel85.mistmap.tracking.TrackingService
import com.jeiel85.mistmap.tracking.TrackingState

class MainActivity : ComponentActivity() {
  private val vm: MapViewModel by viewModels()

  private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    result ->
    when {
      result[Manifest.permission.ACCESS_FINE_LOCATION] == true -> startRecording()
      result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
        vm.show(Notice("걸은 길을 밝히려면 '정확한 위치'를 허용해 주세요.", "설정 열기", ::openAppSettings))
      shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
        vm.show(Notice("위치 권한이 있어야 걸은 길을 기록할 수 있어요."))
      else -> vm.show(Notice("설정에서 위치 권한을 허용해 주세요.", "설정 열기", ::openAppSettings))
    }
  }

  // 기록 파일은 MIME 형식이 제각각이라(GPX가 octet-stream으로 오기도 함) 모두 받고 내용으로 판별한다
  private val openRecord = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let(vm::importFrom)
  }

  private val createGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
    uri?.let(vm::exportGpx)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // 화면이 늘 밤 배경이라 시스템 막대 아이콘은 밝게 둔다
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)
    setContent {
      MistTheme {
        MapScreen(
          vm = vm,
          onStart = ::requestRecording,
          onPause = { TrackingService.send(this, TrackingService.ACTION_PAUSE) },
          onResume = { TrackingService.send(this, TrackingService.ACTION_RESUME) },
          onStop = { TrackingService.send(this, TrackingService.ACTION_STOP) },
          onImport = { openRecord.launch(arrayOf("*/*")) },
          onExport = { createGpx.launch("mistmap-${LocalDate.now()}.gpx") },
          onDim = ::setDimmed,
          onOpenBatterySettings = ::openAppSettings,
        )
      }
    }
    if (savedInstanceState == null) handleShared(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShared(intent)
  }

  /** 다른 앱의 "공유 → Mist Map" 또는 파일 앱에서 "열기"로 받은 기록 파일. */
  private fun handleShared(intent: Intent?) {
    val uri = when (intent?.action) {
      Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
      Intent.ACTION_VIEW -> intent.data
      else -> null
    } ?: return
    vm.importFrom(uri)
  }

  /** 간단히 보기 동안 화면 밝기를 낮춘다. 나오면 시스템 밝기로 돌려놓는다. */
  private fun setDimmed(dim: Boolean) {
    window.attributes = window.attributes.apply {
      screenBrightness = if (dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
  }

  private fun requestRecording() {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val notify = Build.VERSION.SDK_INT < 33 ||
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    val activity = Build.VERSION.SDK_INT < 29 ||
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
      PackageManager.PERMISSION_GRANTED
    if (fine && notify && activity) {
      startRecording()
      return
    }
    // 앱 사용 중 위치 + 알림만 요청한다. 항상 허용(백그라운드 위치)은 요청하지 않는다 (D3)
    val wanted = buildList {
      add(Manifest.permission.ACCESS_FINE_LOCATION)
      add(Manifest.permission.ACCESS_COARSE_LOCATION)
      if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
      // 가만히 있을 때 GPS를 끄는 데만 쓴다. 거부해도 기록은 된다 (M3)
      if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    permissionRequest.launch(wanted.toTypedArray())
  }

  private fun startRecording() {
    if (TrackingState.status.value != TrackingState.Status.OFF) return
    val locationManager = getSystemService(LocationManager::class.java)
    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
      vm.show(Notice("휴대폰의 위치 서비스가 꺼져 있어요.", "켜러 가기") {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
      })
      return
    }
    TrackingService.start(this)
  }

  private fun openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
  }
}

private const val DIM_BRIGHTNESS = 0.08f
