package com.jeiel85.mistmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeiel85.mistmap.tracking.TrackingState.Status
import java.util.Locale

/** OLED에서 거의 꺼진 픽셀이 되도록 바탕은 검정에 가깝게 */
private val Dark = Color(0xFF04060E)
private const val BREATH_PERIOD_S = 4.0
private const val BREATH_FPS = 10

/**
 * 걷는 동안의 간단히 보기(절전): 지도·안개를 그리지 않고 오늘 걸은 거리만 크게 보여 준다.
 * 가운데 불빛만 느리게 숨 쉬고(초당 10번만 다시 그림) 나머지는 정적이다. 누르면 지도로 돌아간다.
 */
@Composable
fun SimpleWalkScreen(todayM: Double, status: Status, onExit: () -> Unit) {
  Box(
    Modifier
      .fillMaxSize()
      .background(Dark)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExit),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      FireflyGlow(Modifier.size(72.dp))
      Spacer(Modifier.height(18.dp))
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
          String.format(Locale.KOREA, "%.2f", todayM / 1000),
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 64.sp, lineHeight = 64.sp, color = Mist.Moon.copy(alpha = 0.92f)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          "km",
          style = TextStyle(fontFamily = Mist.Hand, fontSize = 26.sp, color = Mist.MoonSoft.copy(alpha = 0.8f)),
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
      Text("오늘 걸은 거리", fontSize = 14.sp, color = Mist.MoonSoft.copy(alpha = 0.7f))
      Spacer(Modifier.height(10.dp))
      Text(
        when (status) {
          Status.RECORDING -> "반디가 길을 밝히는 중"
          Status.PAUSED -> "잠시 멈춤"
          Status.OFF -> "기록 꺼짐"
        },
        fontSize = 13.sp,
        color = Mist.Firefly.copy(alpha = 0.75f),
      )
    }
    Text(
      "화면을 누르면 지도로 돌아가요",
      fontSize = 12.sp,
      color = Mist.MoonSoft.copy(alpha = 0.45f),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(bottom = 28.dp),
    )
  }
}

/**
 * 반디의 꼬리 등불: 은은한 빛 번짐 위에 밝은 점 하나가 약 4초 주기로 숨 쉰다.
 * 절전 화면이라 매 프레임이 아니라 초당 [BREATH_FPS]번만 값을 바꾸고, 다시 구성하지 않고 그리기 단계만 다시 한다.
 */
@Composable
private fun FireflyGlow(modifier: Modifier = Modifier) {
  var breath by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) {
    val start = System.nanoTime()
    while (true) {
      val t = (System.nanoTime() - start) / 1e9
      breath = ((1 - cos(2 * PI * t / BREATH_PERIOD_S)) / 2).toFloat()
      delay(1000L / BREATH_FPS)
    }
  }
  Canvas(
    modifier.graphicsLayer {
      val k = breath
      scaleX = 0.88f + 0.2f * k
      scaleY = scaleX
      alpha = 0.7f + 0.3f * k
    },
  ) {
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(
      Brush.radialGradient(
        listOf(Mist.Firefly.copy(alpha = 0.45f), Mist.Firefly.copy(alpha = 0.12f), Color.Transparent),
        center = c,
        radius = size.minDimension / 2,
      ),
      radius = size.minDimension / 2,
      center = c,
    )
    drawCircle(Color(0xFFFFF3B0), radius = size.minDimension * 0.09f, center = c)
  }
}
