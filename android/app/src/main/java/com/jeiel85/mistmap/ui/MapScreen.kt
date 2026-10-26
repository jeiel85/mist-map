package com.jeiel85.mistmap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.mistmap.renderer.RendererView
import com.jeiel85.mistmap.tracking.TrackingState.Status
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MapScreen(
  vm: MapViewModel,
  onStart: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onStop: () -> Unit,
) {
  val status by vm.status.collectAsStateWithLifecycle()
  val todayM by vm.todayM.collectAsStateWithLifecycle()
  val notice by vm.notice.collectAsStateWithLifecycle()
  val rendererError by vm.rendererError.collectAsStateWithLifecycle()
  val density = LocalDensity.current
  val statusBarDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }

  Box(Modifier.fillMaxSize().background(Mist.Paper)) {
    RendererView(
      modifier = Modifier.fillMaxSize(),
      onConnected = vm::attach,
      onMessage = { vm.onRendererMessage(it.optString("type"), it.optString("message").ifEmpty { null }) },
    )

    rendererError?.let { RendererErrorCard(it, Modifier.align(Alignment.Center).padding(24.dp)) }

    AnimatedVisibility(
      visible = notice != null,
      enter = fadeIn() + slideInVertically { -it / 2 },
      exit = fadeOut() + slideOutVertically { -it / 2 },
      modifier = Modifier
        .align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
    ) {
      notice?.let { NoticePill(it, onDismiss = { vm.dismissNotice(it) }) }
    }

    Row(
      Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .onSizeChanged { size ->
          with(density) { vm.updateInsets(statusBarDp.value.toInt(), size.height.toDp().value.toInt()) }
        }
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      TodayCard(todayM, status, Modifier.weight(1f))
      RecordControls(status, onStart, onPause, onResume, onStop)
    }
  }
}

@Composable
private fun TodayCard(todayM: Double, status: Status, modifier: Modifier = Modifier) {
  InkBox(modifier) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
      StatusLine(status)
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
          String.format(Locale.KOREA, "%.2f", todayM / 1000),
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 40.sp, lineHeight = 40.sp, color = Mist.Ink),
        )
        Spacer(Modifier.width(4.dp))
        Text(
          "km",
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 20.sp, color = Mist.InkSoft),
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      Text("오늘 걸은 거리", fontSize = 12.sp, color = Mist.InkSoft)
    }
  }
}

@Composable
private fun StatusLine(status: Status) {
  val (label, color) = when (status) {
    Status.RECORDING -> "기록 중" to Mist.Pink
    Status.PAUSED -> "잠시 멈춤" to Mist.Yellow
    Status.OFF -> "기록 꺼짐" to Mist.InkSoft.copy(alpha = 0.5f)
  }
  val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
    initialValue = 1f,
    targetValue = 0.35f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "pulse",
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      Modifier
        .size(8.dp)
        .alpha(if (status == Status.RECORDING) pulse else 1f)
        .background(color, CircleShape),
    )
    Spacer(Modifier.width(6.dp))
    Text(label, fontSize = 12.sp, color = Mist.Ink)
  }
}

@Composable
private fun RecordControls(
  status: Status,
  onStart: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onStop: () -> Unit,
) {
  Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    when (status) {
      Status.OFF -> HandButton("기록 켜기", Mist.Yellow, onStart, big = true)
      Status.RECORDING -> {
        HandButton("멈춤", Mist.PaperHi, onPause)
        HandButton("끄기", Mist.PaperHi, onStop)
      }
      Status.PAUSED -> {
        HandButton("다시 걷기", Mist.Yellow, onResume)
        HandButton("끄기", Mist.PaperHi, onStop)
      }
    }
  }
}

@Composable
private fun HandButton(label: String, fill: Color, onClick: () -> Unit, big: Boolean = false) {
  val shape = RoundedCornerShape(999.dp)
  InkBox(shape = shape, fill = fill) {
    Text(
      label,
      textAlign = TextAlign.Center,
      style = TextStyle(fontFamily = Mist.Hand, fontSize = if (big) 26.sp else 20.sp, color = Mist.Ink),
      modifier = Modifier
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = if (big) 22.dp else 18.dp, vertical = if (big) 12.dp else 6.dp),
    )
  }
}

@Composable
private fun NoticePill(notice: Notice, onDismiss: () -> Unit) {
  LaunchedEffect(notice) {
    delay(if (notice.action != null) 8_000 else 4_500)
    onDismiss()
  }
  Row(
    Modifier
      .background(Mist.Ink, RoundedCornerShape(999.dp))
      .clickable {
        notice.action?.invoke()
        onDismiss()
      }
      .padding(horizontal = 18.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(notice.text, color = Mist.PaperHi, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
    notice.actionLabel?.let {
      Spacer(Modifier.width(12.dp))
      Text(it, style = TextStyle(fontFamily = Mist.Hand, fontSize = 18.sp, color = Mist.Yellow))
    }
  }
}

@Composable
private fun RendererErrorCard(message: String, modifier: Modifier = Modifier) {
  InkBox(modifier) {
    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("지도를 그리지 못했어요", style = TextStyle(fontFamily = Mist.Hand, fontSize = 24.sp, color = Mist.Ink))
      Spacer(Modifier.size(4.dp))
      Text("인터넷 연결을 확인해 주세요. 기록은 그대로 이어져요.", fontSize = 13.sp, color = Mist.Ink, textAlign = TextAlign.Center)
      Spacer(Modifier.size(6.dp))
      Text(message, fontSize = 11.sp, color = Mist.InkSoft, textAlign = TextAlign.Center)
    }
  }
}
