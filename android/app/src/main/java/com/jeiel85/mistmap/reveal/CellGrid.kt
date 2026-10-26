package com.jeiel85.mistmap.reveal

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * 밝힘의 저장 단위: 웹 메르카토르 쿼드트리 21단계 칸 (DECISIONS D2).
 * 칸 ID = `(x << 21) | y`. 서울에서 한 변 약 15m.
 */
object CellGrid {
  const val LEVEL = 21
  private const val N = 1L shl LEVEL
  private const val MASK = N - 1

  fun cellId(p: LatLng): Long {
    val mx = (p.lng + 180.0) / 360.0
    val s = sin(Math.toRadians(p.lat))
    val my = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    val x = floor(mx * N).toLong().coerceIn(0, MASK)
    val y = floor(my * N).toLong().coerceIn(0, MASK)
    return (x shl LEVEL) or y
  }

  fun x(cellId: Long): Long = cellId ushr LEVEL

  fun y(cellId: Long): Long = cellId and MASK
}
