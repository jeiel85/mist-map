package com.jeiel85.mistmap.data

import com.jeiel85.mistmap.importers.HistoryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class GpxExporterTest {
  private fun fix(session: Long, time: Long, lat: Double, lng: Double) =
    FixEntity(sessionId = session, time = time, lat = lat, lng = lng, accuracy = 5f, speed = null)

  @Test fun writesOneTrackPerSessionThatOurParserReadsBack() {
    val out = StringWriter()
    GpxExporter.write(
      listOf(
        fix(1, 2_000, 37.5450, 127.0570),
        fix(1, 1_000, 37.5446, 127.0560), // 시간 순으로 정렬해 쓴다
        fix(2, 9_000, 35.1, 129.0),
      ),
      out,
    )
    val gpx = out.toString()
    assertTrue(gpx.contains("<time>1970-01-01T00:00:01Z</time>"))

    // 내보낸 파일을 다시 가져오면 같은 경로가 된다
    val back = HistoryParser.parse(gpx)
    assertEquals("GPX", back.format)
    assertEquals(listOf(2, 1), back.tracks.map { it.size })
    assertEquals(37.5446, back.tracks[0][0].lat, 1e-9)
    assertEquals(129.0, back.tracks[1][0].lng, 1e-9)
  }
}
