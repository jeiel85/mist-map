package com.jeiel85.mistmap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
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

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContent {
      MistTheme {
        MapScreen(
          vm = vm,
          onStart = ::requestRecording,
          onPause = { TrackingService.send(this, TrackingService.ACTION_PAUSE) },
          onResume = { TrackingService.send(this, TrackingService.ACTION_RESUME) },
          onStop = { TrackingService.send(this, TrackingService.ACTION_STOP) },
        )
      }
    }
  }

  private fun requestRecording() {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val notify = Build.VERSION.SDK_INT < 33 ||
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (fine && notify) {
      startRecording()
      return
    }
    // 앱 사용 중 위치 + 알림만 요청한다. 항상 허용(백그라운드 위치)은 요청하지 않는다 (D3)
    val wanted = buildList {
      add(Manifest.permission.ACCESS_FINE_LOCATION)
      add(Manifest.permission.ACCESS_COARSE_LOCATION)
      if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
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
