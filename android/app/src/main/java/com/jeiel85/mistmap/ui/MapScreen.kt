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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
  onImport: () -> Unit,
  onExport: () -> Unit,
  onDim: (Boolean) -> Unit,
) {
  val status by vm.status.collectAsStateWithLifecycle()
  val imports by vm.imports.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  var showRecords by rememberSaveable { mutableStateOf(false) }
  // 걷는 동안 간단히 보기(절전): 기록 중 1분 동안 손대지 않으면 들어가고, 누르면 지도로 돌아온다
  var simple by rememberSaveable { mutableStateOf(false) }
  var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(status, lastTouch, simple, showRecords) {
    if (status == Status.OFF) simple = false
    if (status != Status.RECORDING || simple || showRecords) return@LaunchedEffect
    delay(SIMPLE_AFTER_MS)
    simple = true
  }
  LaunchedEffect(simple) { onDim(simple) }
  DisposableEffect(Unit) { onDispose { onDim(false) } }
  BackHandler(enabled = simple) { simple = false }
  val todayM by vm.todayM.collectAsStateWithLifecycle()
  val notice by vm.notice.collectAsStateWithLifecycle()
  val rendererError by vm.rendererError.collectAsStateWithLifecycle()
  val density = LocalDensity.current
  val statusBarDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }

  Box(
    Modifier
      .fillMaxSize()
      .background(Mist.Night)
      .pointerInput(Unit) {
        // 소비하지 않고 지켜보기만 한다 (지도 조작은 그대로 WebView로 간다)
        awaitPointerEventScope {
          while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            lastTouch = System.currentTimeMillis()
          }
        }
      },
  ) {
    RendererView(
      paused = simple,
      modifier = Modifier.fillMaxSize(),
      onConnected = vm::attach,
      onMessage = { vm.onRendererMessage(it.optString("type"), it.optString("message").ifEmpty { null }) },
    )

    rendererError?.let { RendererErrorCard(it, Modifier.align(Alignment.Center).padding(24.dp)) }

    if (status != Status.OFF) {
      TopPill(
        label = "간단히 보기",
        onClick = { simple = true },
        modifier = Modifier
          .align(Alignment.TopStart)
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(top = 10.dp, start = 16.dp),
      )
    }

    TopPill(
      label = "기록 모음",
      onClick = { showRecords = true },
      modifier = Modifier
        .align(Alignment.TopEnd)
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(top = 10.dp, end = 16.dp),
    )

    AnimatedVisibility(
      visible = notice != null,
      enter = fadeIn() + slideInVertically { -it / 2 },
      exit = fadeOut() + slideOutVertically { -it / 2 },
      modifier = Modifier
        .align(Alignment.TopCenter)
        .windowInsetsPadding(WindowInsets.statusBars)
        .padding(top = TOP_BAR, start = 16.dp, end = 16.dp),
    ) {
      notice?.let { NoticePill(it, onDismiss = { vm.dismissNotice(it) }) }
    }

    busy?.let {
      Text(
        it,
        color = Mist.Night,
        fontSize = 13.sp,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(top = TOP_BAR + 52.dp)
          .background(Mist.Firefly, RoundedCornerShape(999.dp))
          .padding(horizontal = 16.dp, vertical = 6.dp),
      )
    }

    if (showRecords) {
      RecordsSheet(
        imports = imports,
        busy = busy,
        onImport = { showRecords = false; onImport() },
        onExport = { showRecords = false; onExport() },
        onUndo = vm::undoImport,
        onDismiss = { showRecords = false },
      )
    }

    // 간단히 보기 동안에는 가려진 HUD를 구성에서 빼서 "기록 중" 깜빡임이 매 프레임 돌지 않게 한다
    if (!simple) {
      Row(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .onSizeChanged { size ->
            // 위쪽은 "기록 모음" 줄까지 가린다
            with(density) { vm.updateInsets((statusBarDp + TOP_BAR).value.toInt(), size.height.toDp().value.toInt()) }
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

    // 간단히 보기는 HUD까지 모두 덮어야 하므로 Box의 맨 마지막에 둔다
    if (simple) {
      SimpleWalkScreen(todayM, status, onExit = { simple = false; lastTouch = System.currentTimeMillis() })
    }
  }
}

private val TOP_BAR = 56.dp
private const val SIMPLE_AFTER_MS = 60_000L

@Composable
private fun TopPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(999.dp)
  GlowBox(modifier, shape = shape) {
    Text(
      label,
      style = TextStyle(fontFamily = Mist.Hand, fontSize = 19.sp, color = Mist.Moon),
      modifier = Modifier
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun TodayCard(todayM: Double, status: Status, modifier: Modifier = Modifier) {
  GlowBox(modifier) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
      StatusLine(status)
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
          String.format(Locale.KOREA, "%.2f", todayM / 1000),
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 40.sp, lineHeight = 40.sp, color = Mist.Moon),
        )
        Spacer(Modifier.width(4.dp))
        Text(
          "km",
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 20.sp, color = Mist.MoonSoft),
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      Text("오늘 걸은 거리", fontSize = 12.sp, color = Mist.MoonSoft)
    }
  }
}

@Composable
private fun StatusLine(status: Status) {
  val (label, color) = when (status) {
    Status.RECORDING -> "기록 중" to Mist.Firefly
    Status.PAUSED -> "잠시 멈춤" to Mist.Pink
    Status.OFF -> "기록 꺼짐" to Mist.MoonSoft.copy(alpha = 0.5f)
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
    Text(label, fontSize = 12.sp, color = Mist.Moon)
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
      Status.OFF -> HandButton("기록 켜기", onStart, primary = true, big = true)
      Status.RECORDING -> {
        HandButton("멈춤", onPause)
        HandButton("끄기", onStop)
      }
      Status.PAUSED -> {
        HandButton("다시 걷기", onResume, primary = true)
        HandButton("끄기", onStop)
      }
    }
  }
}

@Composable
// primary: 반딧불 빛으로 채운 주 버튼, 아니면 반투명 남색 버튼
private fun HandButton(label: String, onClick: () -> Unit, primary: Boolean = false, big: Boolean = false) {
  val shape = RoundedCornerShape(999.dp)
  GlowBox(
    shape = shape,
    fill = if (primary) Mist.Firefly else Mist.Card,
    border = if (primary) Color.Transparent else Mist.Line,
  ) {
    Text(
      label,
      textAlign = TextAlign.Center,
      style = TextStyle(fontFamily = Mist.Hand, fontSize = if (big) 26.sp else 20.sp, color = if (primary) Mist.OnFirefly else Mist.Moon),
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
      .background(Mist.Moon, RoundedCornerShape(999.dp))
      .clickable {
        notice.action?.invoke()
        onDismiss()
      }
      .padding(horizontal = 18.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(notice.text, color = Mist.Night, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
    notice.actionLabel?.let {
      Spacer(Modifier.width(12.dp))
      Text(it, style = TextStyle(fontFamily = Mist.Hand, fontSize = 18.sp, color = Color(0xFFB0741C)))
    }
  }
}

@Composable
private fun RendererErrorCard(message: String, modifier: Modifier = Modifier) {
  GlowBox(modifier) {
    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("지도를 그리지 못했어요", style = TextStyle(fontFamily = Mist.Hand, fontSize = 24.sp, color = Mist.Firefly))
      Spacer(Modifier.size(4.dp))
      Text("인터넷 연결을 확인해 주세요. 기록은 그대로 이어져요.", fontSize = 13.sp, color = Mist.Moon, textAlign = TextAlign.Center)
      Spacer(Modifier.size(6.dp))
      Text(message, fontSize = 11.sp, color = Mist.MoonSoft, textAlign = TextAlign.Center)
    }
  }
}
