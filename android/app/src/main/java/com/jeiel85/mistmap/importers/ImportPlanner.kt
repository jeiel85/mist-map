package com.jeiel85.mistmap.importers

import com.jeiel85.mistmap.reveal.Geo
import com.jeiel85.mistmap.reveal.LatLng
import com.jeiel85.mistmap.reveal.RevealTrail

/** 가져온 기록 하나를 밝힐 칸과 화면에 맞출 범위로 바꾼 결과. */
data class ImportPlan(
  val cellIds: List<Long>,
  val distanceM: Double,
  val south: Double,
  val west: Double,
  val north: Double,
  val east: Double,
)

/**
 * 경로를 걸은 길처럼 24m마다 표본을 찍어 칸으로 바꾼다 (실시간 기록의 [RevealTrail]과 같은 규칙).
 * 웹(`importFiles`)처럼 [gapM]보다 멀리 떨어진 두 점은 기록 공백이나 장거리 이동으로 보고 잇지 않는다.
 */
object ImportPlanner {
  const val GAP_M = 1500.0

  fun plan(history: ParsedHistory, gapM: Double = GAP_M): ImportPlan {
    val cells = LinkedHashSet<Long>()
    var distance = 0.0
    var south = 90.0
    var north = -90.0
    var west = 180.0
    var east = -180.0
    val trail = RevealTrail()
    for (track in history.tracks) {
      trail.reset()
      var prev: LatLng? = null
      for (p in track) {
        south = minOf(south, p.lat); north = maxOf(north, p.lat)
        west = minOf(west, p.lng); east = maxOf(east, p.lng)
        val from = prev?.takeIf { Geo.distanceM(it, p) <= gapM }
        if (from != null) distance += Geo.distanceM(from, p) else trail.reset()
        cells += trail.advance(from, p)
        prev = p
      }
    }
    return ImportPlan(cells.toList(), distance, south, west, north, east)
  }
}
