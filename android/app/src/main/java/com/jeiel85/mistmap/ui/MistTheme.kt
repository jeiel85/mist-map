package com.jeiel85.mistmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jeiel85.mistmap.R

/** 웹 프로토타입(src/style.css)의 종이·잉크 팔레트. */
object Mist {
  val Paper = Color(0xFFF4EBD9)
  val PaperHi = Color(0xFFFFFAF0)
  val Ink = Color(0xFF3D342B)
  val InkSoft = Color(0xFF8A7B66)
  val Yellow = Color(0xFFF6CD6A)
  val Pink = Color(0xFFE58F8F)
  val Hand = FontFamily(Font(R.font.gaegu_bold, FontWeight.Bold))
}

@Composable
fun MistTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = lightColorScheme(
      primary = Mist.Ink,
      onPrimary = Mist.PaperHi,
      background = Mist.Paper,
      surface = Mist.PaperHi,
      onSurface = Mist.Ink,
    ),
    content = content,
  )
}

/**
 * 웹의 `.card`처럼 잉크 테두리와 번지지 않는 그림자(3px 3px 0)를 가진 손그림 상자.
 */
@Composable
fun InkBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(18.dp),
  fill: Color = Mist.PaperHi,
  shadow: Dp = 3.dp,
  content: @Composable () -> Unit,
) {
  Box(modifier) {
    Box(
      Modifier
        .matchParentSize()
        .offset(shadow, shadow)
        .background(Mist.Ink, shape),
    )
    Box(
      Modifier
        .background(fill, shape)
        .border(2.dp, Mist.Ink, shape),
    ) { content() }
  }
}
