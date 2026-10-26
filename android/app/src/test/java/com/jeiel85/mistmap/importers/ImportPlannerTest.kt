package com.jeiel85.mistmap.importers

import com.jeiel85.mistmap.reveal.CellGrid
import com.jeiel85.mistmap.reveal.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {
  private val start = LatLng(37.5446, 127.0560)
  private fun east(m: Double) = LatLng(start.lat, start.lng + m / 88_297)

  @Test fun connectsNearbyPointsEvery24m() {
    val plan = ImportPlanner.plan(ParsedHistory("GPX", listOf(listOf(start, east(250.0)))))
    // 0, 24, …, 240m → 11칸
    assertEquals(11, plan.cellIds.size)
    assertEquals(250.0, plan.distanceM, 1.0)
  }

  @Test fun doesNotConnectPointsFartherThanGap() {
    val plan = ImportPlanner.plan(ParsedHistory("GPX", listOf(listOf(start, east(2000.0)))))
    assertEquals(listOf(CellGrid.cellId(start), CellGrid.cellId(east(2000.0))), plan.cellIds)
    assertEquals(0.0, plan.distanceM, 0.0)
  }

  @Test fun repeatedVisitsCountOnce() {
    val plan = ImportPlanner.plan(ParsedHistory("구글 타임라인", listOf(listOf(start), listOf(start), listOf(start))))
    assertEquals(listOf(CellGrid.cellId(start)), plan.cellIds)
  }

  @Test fun boundsCoverAllPoints() {
    val far = LatLng(35.1, 129.0)
    val plan = ImportPlanner.plan(ParsedHistory("GPX", listOf(listOf(start), listOf(far))))
    assertTrue(plan.south == 35.1 && plan.north == start.lat && plan.west == start.lng && plan.east == 129.0)
  }

  @Test fun seongsuSampleBecomesAWalkedPath() {
    val history = HistoryParser.parse(java.io.File("../../samples/seongsu-walk.gpx").readText())
    val plan = ImportPlanner.plan(history)
    // 약 3km 산책 → 24m 표본이면 100칸 남짓 (같은 칸을 다시 지나면 줄어든다)
    assertTrue("distance=${plan.distanceM}", plan.distanceM in 2000.0..6000.0)
    assertTrue("cells=${plan.cellIds.size}", plan.cellIds.size in 60..260)
  }
}
