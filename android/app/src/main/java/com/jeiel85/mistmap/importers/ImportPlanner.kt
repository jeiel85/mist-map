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
 * 웹(`importPlan.js`)처럼 [gapM]보다 멀리 떨어진 두 점은 기록 공백이나 장거리 이동으로 보고 잇지 않는다.
 * [HistoryParser.stream]이 점을 하나씩 넘기므로 경로 전체를 메모리에 두지 않는다.
 */
class ImportPlanner(private val gapM: Double = GAP_M) : TrackSink {
  private val cells = LinkedHashSet<Long>()
  private val trail = RevealTrail()
  private var prev: LatLng? = null
  private var distance = 0.0
  private var south = 90.0
  private var north = -90.0
  private var west = 180.0
  private var east = -180.0

  override fun point(p: LatLng) {
    south = minOf(south, p.lat); north = maxOf(north, p.lat)
    west = minOf(west, p.lng); east = maxOf(east, p.lng)
    val from = prev?.takeIf { Geo.distanceM(it, p) <= gapM }
    if (from != null) distance += Geo.distanceM(from, p) else trail.reset()
    cells += trail.advance(from, p)
    prev = p
  }

  override fun endTrack() {
    prev = null
    trail.reset()
  }

  fun build() = ImportPlan(cells.toList(), distance, south, west, north, east)

  companion object {
    const val GAP_M = 1500.0

    /** 이미 읽은 경로 목록으로 (테스트용) */
    fun plan(history: ParsedHistory, gapM: Double = GAP_M): ImportPlan {
      val planner = ImportPlanner(gapM)
      for (track in history.tracks) {
        track.forEach(planner::point)
        planner.endTrack()
      }
      return planner.build()
    }
  }
}
