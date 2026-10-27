package com.jeiel85.mistmap.data

import com.jeiel85.mistmap.reveal.LatLng
import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 원시 위치를 GPX 1.1로 쓴다. 기록 세션 하나가 트랙(trk) 하나가 된다.
 * 앱이 보관하는 원시 위치는 최근 30일뿐이라(D4) 그보다 오래된 길은 내보내지 않는다.
 * GPX 규격대로 웨이포인트가 트랙보다 앞에 온다.
 */
object GpxExporter {
  /** @param places 가져온 기록이 밝힌 칸의 중심. 웨이포인트(방문지)로 쓰여 다시 가져오면 같은 칸이 밝혀진다 */
  fun write(fixes: List<FixEntity>, out: Writer, places: List<LatLng> = emptyList()) {
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    out.write("<gpx version=\"1.1\" creator=\"Mist Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    for (p in places) out.write("  <wpt lat=\"${p.lat}\" lon=\"${p.lng}\"><name>가져온 곳</name></wpt>\n")
    for ((sessionId, points) in fixes.groupBy { it.sessionId }) {
      out.write("  <trk><name>Mist Map ${sessionId}</name><trkseg>\n")
      for (f in points.sortedBy { it.time }) {
        out.write("    <trkpt lat=\"${f.lat}\" lon=\"${f.lng}\"><time>${iso(f.time)}</time></trkpt>\n")
      }
      out.write("  </trkseg></trk>\n")
    }
    out.write("</gpx>\n")
  }

  private fun iso(epochMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
}
