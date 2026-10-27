package com.jeiel85.mistmap.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeiel85.mistmap.reveal.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {
  private val zone = ZoneOffset.UTC
  private lateinit var context: Context

  @Before fun clear() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
  }

  private fun todayAt(hour: Int, minute: Int) =
    LocalDate.now(zone).atTime(hour, minute).toInstant(zone).toEpochMilli()

  @Test fun gapKeepsWhereItStartedAndOnlyTheLastTen() {
    val d = Diagnostics(context, zone)
    d.addGap(todayAt(8, 5), LatLng(37.544612, 127.056034))
    d.addGap(todayAt(9, 30), null) // 기록 시작 직후 끊김: 위치 없음
    val today = d.today()
    assertEquals(2, today.gaps)
    assertEquals("08:05", today.gapList[0].time)
    assertEquals(37.54461, today.gapList[0].at!!.lat, 1e-9)
    assertEquals(127.05603, today.gapList[0].at!!.lng, 1e-9)
    assertNull(today.gapList[1].at)

    repeat(12) { d.addGap(todayAt(10, it), LatLng(37.5, 127.0)) }
    assertEquals(14, d.today().gaps)
    assertEquals(10, d.today().gapList.size)
    assertEquals("10:11", d.today().gapList.last().time)
  }

  @Test fun readsTimesSavedBeforeLocationsWereKept() {
    val day = LocalDate.now(zone).toString()
    context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit()
      .putInt("$day.gaps", 2).putString("$day.gapTimes", "07:10,07:40").commit()
    assertEquals(listOf(Gap("07:10", null), Gap("07:40", null)), Diagnostics(context, zone).today().gapList)
  }

  @Test fun brokenLocationTextIsIgnored() {
    assertEquals(Gap("07:10", null), Gap.decode("07:10@abc;"))
    assertEquals(Gap("07:10", LatLng(1.5, -2.25)), Gap.decode(Gap("07:10", LatLng(1.5, -2.25)).encode()))
  }
}
