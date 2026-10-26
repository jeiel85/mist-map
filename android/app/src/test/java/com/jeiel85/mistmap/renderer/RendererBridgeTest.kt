package com.jeiel85.mistmap.renderer

import com.jeiel85.mistmap.reveal.CellGrid
import com.jeiel85.mistmap.reveal.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RendererBridgeTest {
  // src/main.js applyCells()가 읽는 형식
  @Test fun encodesKindThenLittleEndianXYPairs() {
    val id = CellGrid.cellId(LatLng(37.5446, 127.0560))
    val bytes = RendererBridge.encodeCells(RendererBridge.CELLS_ADD, listOf(id))
    assertEquals(12, bytes.size)
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(2, buf.get(0).toInt())
    assertEquals(1788730, buf.getInt(4))
    assertEquals(812287, buf.getInt(8))
  }
}
