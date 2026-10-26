package com.jeiel85.mistmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeiel85.mistmap.R

/** 웹(src/style.css)의 '반딧불 밤 산책' 팔레트. */
object Mist {
  val Night = Color(0xFF19203D)
  val Card = Color(0xE6232C55)
  val Moon = Color(0xFFFFF3DC)      // 글자 (달빛 크림)
  val MoonSoft = Color(0xFFB7B2D6)
  val Line = Color(0x52FFDC96)      // 등불빛 테두리
  val Firefly = Color(0xFFFFD36E)   // 반딧불 빛
  val OnFirefly = Color(0xFF3A2C14)
  val Pink = Color(0xFFF29E8E)
  val Hand = FontFamily(Font(R.font.gaegu_bold, FontWeight.Bold))
}

@Composable
fun MistTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Mist.Firefly,
      onPrimary = Mist.OnFirefly,
      background = Mist.Night,
      surface = Mist.Card,
      onSurface = Mist.Moon,
    ),
    content = content,
  )
}

/** 웹의 `.card`처럼 반투명 남색 위에 은은한 등불빛 테두리를 두른 상자. */
@Composable
fun GlowBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(20.dp),
  fill: Color = Mist.Card,
  border: Color = Mist.Line,
  content: @Composable () -> Unit,
) {
  Box(
    modifier
      .shadow(10.dp, shape, ambientColor = Color(0xFF05081A), spotColor = Mist.Firefly.copy(alpha = 0.35f))
      .background(fill, shape)
      .border(1.5.dp, border, shape),
  ) { content() }
}
