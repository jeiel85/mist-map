package com.jeiel85.mistmap.reveal

import org.junit.Assert.assertEquals
import org.junit.Test

class CellGridTest {
  // 기댓값은 웹(src/main.js의 mercX·mercY, tools/app-mode.cjs)과 같은 식으로 node에서 계산했다
  @Test fun seongsuMatchesWebFormula() {
    val id = CellGrid.cellId(LatLng(37.5446, 127.0560))
    assertEquals(1788730L, CellGrid.x(id))
    assertEquals(812287L, CellGrid.y(id))
  }

  @Test fun idPacksXAndY() {
    assertEquals((1048576L shl 21) or 1048576L, CellGrid.cellId(LatLng(0.0, 0.0)))
  }

  @Test fun clampsToWorldEdge() {
    val id = CellGrid.cellId(LatLng(89.9, 180.0))
    assertEquals((1L shl 21) - 1, CellGrid.x(id))
    assertEquals(0L, CellGrid.y(id))
  }
}
