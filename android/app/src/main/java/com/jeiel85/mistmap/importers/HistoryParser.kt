package com.jeiel85.mistmap.importers

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.jeiel85.mistmap.reveal.LatLng
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime

class ImportException(message: String) : Exception(message)

/** 한 track 안의 점은 시간 순으로 이어진 경로이고, 점이 하나뿐인 track은 머문 장소(방문지)다. */
data class ParsedHistory(val format: String, val tracks: List<List<LatLng>>, val vehicleSkipped: Int = 0)

/** 읽은 경로를 점 하나씩 받는다. 가져오기는 받는 대로 칸으로 바꾸므로 큰 파일도 경로 전체를 메모리에 두지 않는다. */
interface TrackSink {
  fun point(p: LatLng)

  /** 지금 경로가 끝났다. 다음 점은 앞 점과 잇지 않는다 */
  fun endTrack()
}

data class ParseSummary(val format: String, val points: Int, val vehicleSkipped: Int)

/**
 * 외부 이동 기록 파일 → 경로. 웹의 `src/importers.js`를 그대로 옮겼다.
 * 두 쪽 결과가 같아야 하므로 한쪽을 고치면 다른 쪽과 테스트(`tests/importers.test.js`, `HistoryParserTest`)도 함께 고친다.
 *
 * JSON은 통째로 올리지 않고 요소 하나씩 읽는다(몇 년 치 Records.json 수백 MB). XML(GPX·KML)은 정규식이라 문자열로 읽는다.
 * 구글 타임라인의 차량·대중교통·비행 구간은 빼고(걸은 곳만 밝힌다) 그 수를 [ParseSummary.vehicleSkipped]로 알린다.
 */
object HistoryParser {
  /** 문자열 하나를 읽어 경로 목록으로 (테스트·작은 파일용). 큰 파일은 [stream]. */
  fun parse(text: String): ParsedHistory {
    val tracks = mutableListOf<List<LatLng>>()
    var current = mutableListOf<LatLng>()
    val sink = object : TrackSink {
      override fun point(p: LatLng) {
        current += p
      }

      override fun endTrack() {
        if (current.isNotEmpty()) tracks += current
        current = mutableListOf()
      }
    }
    val summary = stream(text.byteInputStream(Charsets.UTF_8), sink)
    return ParsedHistory(summary.format, tracks, summary.vehicleSkipped)
  }

  /**
   * @param checkCancelled 요소마다 부른다. 취소되었으면 예외를 던지면 된다
   * @param maxXmlChars XML은 문자열로 읽으므로 이보다 크면 막는다
   */
  fun stream(
    input: InputStream,
    sink: TrackSink,
    checkCancelled: () -> Unit = {},
    maxXmlChars: Long = Long.MAX_VALUE,
  ): ParseSummary {
    val buffered = BufferedInputStream(input, 64 * 1024)
    skipBom(buffered)
    val counting = CountingSink(sink)
    val first = peekNonSpace(buffered)
    val (format, skipped) = when (first) {
      '<'.code -> parseXml(readText(buffered, maxXmlChars), counting)
      '{'.code, '['.code -> parseJson(buffered, counting, checkCancelled)
      else -> throw ImportException("파일을 읽을 수 없어요. GPX·KML·GeoJSON·구글 타임라인 JSON인지 확인해 주세요")
    }
    if (counting.count == 0) throw ImportException("$format 파일에 위치 기록이 없어요")
    return ParseSummary(format, counting.count, skipped)
  }

  /** 잘못된 좌표는 버리고(경로는 끊지 않음, 웹과 같음) 몇 개를 넘겼는지 센다 */
  private class CountingSink(private val sink: TrackSink) : TrackSink {
    var count = 0
    override fun point(p: LatLng) {
      if (!valid(p)) return
      count++
      sink.point(p)
    }

    override fun endTrack() = sink.endTrack()

    fun track(points: List<LatLng?>) {
      points.forEach { it?.let(::point) }
      endTrack()
    }
  }

  private fun valid(p: LatLng) =
    p.lat.isFinite() && p.lng.isFinite() && kotlin.math.abs(p.lat) <= 90 && kotlin.math.abs(p.lng) <= 180 &&
      !(p.lat == 0.0 && p.lng == 0.0)

  private fun skipBom(input: BufferedInputStream) {
    input.mark(3)
    val bom = ByteArray(3)
    val n = input.read(bom)
    val isBom = n == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()
    if (!isBom) input.reset()
  }

  private fun peekNonSpace(input: BufferedInputStream): Int {
    input.mark(64 * 1024)
    var c: Int
    do {
      c = input.read()
    } while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code)
    input.reset()
    return c
  }

  private fun readText(input: InputStream, maxChars: Long): String {
    val text = StringBuilder()
    val buf = CharArray(64 * 1024)
    InputStreamReader(input, Charsets.UTF_8).use { reader ->
      while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        text.append(buf, 0, n)
        if (text.length > maxChars) throw ImportException("파일이 너무 커요. 기간을 나눠 내보낸 파일을 넣어 주세요")
      }
    }
    return text.toString()
  }

  // ---- XML ----
  private fun parseXml(src: String, sink: CountingSink): Pair<String, Int> {
    if (Regex("<gpx[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(src)) {
      parseGpx(src).forEach(sink::track)
      return "GPX" to 0
    }
    if (Regex("<kml[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(src)) {
      parseKml(src).forEach(sink::track)
      return "KML" to 0
    }
    throw ImportException("지원하지 않는 XML 파일이에요. GPX나 KML만 읽을 수 있어요")
  }

  private val gpxTag = Regex(
    "<(trkseg|rte)\\b[^>]*>|</(trkseg|rte)\\s*>|<(trkpt|rtept|wpt)\\b([^>]*)>",
    RegexOption.IGNORE_CASE,
  )

  private fun parseGpx(xml: String): List<List<LatLng>> {
    val tracks = mutableListOf<MutableList<LatLng>>()
    var seg: MutableList<LatLng>? = null
    for (m in gpxTag.findAll(xml)) {
      val (open, close, point, attrs) = m.destructured
      when {
        open.isNotEmpty() -> seg = mutableListOf<LatLng>().also { tracks += it }
        close.isNotEmpty() -> seg = null
        else -> {
          val p = LatLng(attr(attrs, "lat"), attr(attrs, "lon"))
          val s = seg
          if (point.equals("wpt", ignoreCase = true) || s == null) tracks += mutableListOf(p) else s += p
        }
      }
    }
    return tracks
  }

  private fun attr(attrs: String, name: String): Double =
    Regex("\\b$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)
      ?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN

  private val kmlCoordinates = Regex("<coordinates[^>]*>([\\s\\S]*?)</coordinates>", RegexOption.IGNORE_CASE)
  private val kmlTrack = Regex("<gx:Track\\b[^>]*>([\\s\\S]*?)</gx:Track>", RegexOption.IGNORE_CASE)
  private val kmlCoord = Regex("<gx:coord>([^<]+)</gx:coord>", RegexOption.IGNORE_CASE)
  private val whitespace = Regex("\\s+")

  private fun parseKml(xml: String): List<List<LatLng>> {
    val tracks = mutableListOf<List<LatLng>>()
    for (m in kmlCoordinates.findAll(xml)) {
      tracks += m.groupValues[1].trim().split(whitespace).filter { it.isNotEmpty() }.map { tuple ->
        val parts = tuple.split(",").map { it.toDoubleOrNull() ?: Double.NaN }
        LatLng(parts.getOrElse(1) { Double.NaN }, parts[0])
      }
    }
    for (m in kmlTrack.findAll(xml)) {
      tracks += kmlCoord.findAll(m.groupValues[1]).map { c ->
        val parts = c.groupValues[1].trim().split(whitespace).map { it.toDoubleOrNull() ?: Double.NaN }
        LatLng(parts.getOrElse(1) { Double.NaN }, parts[0])
      }.toList()
    }
    return tracks
  }

  // ---- JSON (요소 하나씩) ----
  private fun parseJson(input: InputStream, sink: CountingSink, checkCancelled: () -> Unit): Pair<String, Int> {
    val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
    try {
      val timeline = TimelineCollector(sink)
      if (reader.peek() == JsonToken.BEGIN_ARRAY) {
        // iOS 타임라인 내보내기: 최상위가 구간 배열
        reader.beginArray()
        while (reader.hasNext()) {
          checkCancelled()
          (readElement(reader) as? JsonObject)?.let(timeline::segment)
        }
        reader.endArray()
        if (!timeline.sawSegment) throw unknownJson()
        return "구글 타임라인" to timeline.finish()
      }
      // 최상위 객체: 구글 타임라인의 큰 배열은 요소마다 처리하고, 나머지 키(GeoJSON 등)는 그대로 모은다
      val rest = linkedMapOf<String, JsonElement>()
      var google = false
      reader.beginObject()
      while (reader.hasNext()) {
        val key = reader.nextName()
        val handler: ((JsonObject) -> Unit)? = when (key) {
          "semanticSegments" -> timeline::segment
          "rawSignals" -> timeline::rawSignal
          "locations" -> timeline::location
          "timelineObjects" -> timeline::timelineObject
          else -> null
        }
        if (handler != null && reader.peek() == JsonToken.BEGIN_ARRAY) {
          google = true
          reader.beginArray()
          while (reader.hasNext()) {
            checkCancelled()
            (readElement(reader) as? JsonObject)?.let(handler)
          }
          reader.endArray()
          if (key == "locations") timeline.endLocations()
        } else {
          rest[key] = readElement(reader)
        }
      }
      reader.endObject()
      val obj = JsonObject(rest)
      if (isGeoJson(obj)) {
        parseGeoJson(obj).forEach(sink::track)
        return "GeoJSON" to 0
      }
      if (google) return "구글 타임라인" to timeline.finish()
      throw unknownJson()
    } catch (e: IOException) {
      throw ImportException("파일을 읽을 수 없어요. GPX·KML·GeoJSON·구글 타임라인 JSON인지 확인해 주세요")
    } catch (e: IllegalStateException) {
      // 취소(CancellationException)도 IllegalStateException이라 형식 오류로 바꾸지 않고 그대로 올린다
      if (e is java.util.concurrent.CancellationException) throw e
      throw ImportException("파일을 읽을 수 없어요. GPX·KML·GeoJSON·구글 타임라인 JSON인지 확인해 주세요")
    }
  }

  private fun unknownJson() =
    ImportException("알 수 없는 JSON 형식이에요. GeoJSON이나 구글 타임라인 내보내기 파일만 읽을 수 있어요")

  /** 지금 위치의 값 하나를 트리로 읽는다 (요소 하나 크기라 작다) */
  private fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
    JsonToken.BEGIN_ARRAY -> {
      val items = mutableListOf<JsonElement>()
      reader.beginArray()
      while (reader.hasNext()) items += readElement(reader)
      reader.endArray()
      JsonArray(items)
    }
    JsonToken.BEGIN_OBJECT -> {
      val fields = linkedMapOf<String, JsonElement>()
      reader.beginObject()
      while (reader.hasNext()) fields[reader.nextName()] = readElement(reader)
      reader.endObject()
      JsonObject(fields)
    }
    JsonToken.STRING -> JsonPrimitive(reader.nextString())
    JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toDoubleOrNull() ?: Double.NaN)
    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
    JsonToken.NULL -> {
      reader.nextNull()
      JsonNull
    }
    else -> throw IllegalStateException("예상하지 못한 JSON 토큰 ${reader.peek()}")
  }

  // ---- GeoJSON ----
  private val geoJsonTypes = setOf(
    "FeatureCollection", "Feature", "GeometryCollection",
    "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon",
  )

  private fun isGeoJson(d: JsonElement) = d is JsonObject && d.str("type") in geoJsonTypes

  private fun parseGeoJson(d: JsonElement): List<List<LatLng?>> {
    val tracks = mutableListOf<List<LatLng?>>()
    fun position(c: JsonElement): LatLng? {
      val a = c as? JsonArray ?: return null
      val lng = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
      val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
      return LatLng(lat, lng)
    }
    fun line(coords: JsonElement?): List<LatLng?> = (coords as? JsonArray)?.map(::position) ?: emptyList()
    fun walk(g: JsonElement?) {
      val o = g as? JsonObject ?: return
      val coords = o["coordinates"]
      when (o.str("type")) {
        "FeatureCollection" -> (o["features"] as? JsonArray)?.forEach(::walk)
        "Feature" -> walk(o["geometry"])
        "GeometryCollection" -> (o["geometries"] as? JsonArray)?.forEach(::walk)
        "Point" -> tracks += listOf(coords?.let(::position))
        "MultiPoint" -> (coords as? JsonArray)?.forEach { tracks += listOf(position(it)) }
        "LineString" -> tracks += line(coords)
        "MultiLineString", "Polygon" -> (coords as? JsonArray)?.forEach { tracks += line(it) }
        "MultiPolygon" -> (coords as? JsonArray)?.forEach { poly -> (poly as? JsonArray)?.forEach { tracks += line(it) } }
      }
    }
    walk(d)
    return tracks
  }

  // ---- 구글 타임라인 ----
  // 지원 형식
  //  1. 휴대폰 "타임라인 데이터 내보내기"(Android): { semanticSegments, rawSignals }
  //  2. 같은 기능의 iOS 내보내기: 최상위 배열, 좌표가 "geo:lat,lng" 문자열
  //  3. 예전 테이크아웃 Records.json: { locations: [{ latitudeE7, longitudeE7 }] }
  //  4. 예전 테이크아웃 월별 Semantic Location History: { timelineObjects }

  /**
   * 1·2는 차량 활동의 시간대를 알아야 경로 점을 거를 수 있어 구간을 모았다가 [finish]에서 내보낸다(웹과 같은 순서).
   * 3·4는 받는 대로 내보낸다.
   */
  private class TimelineCollector(private val sink: CountingSink) {
    private sealed interface Item
    private class Path(val points: List<Pair<LatLng?, Long?>>) : Item
    private class Activity(val start: LatLng?, val end: LatLng?, val vehicle: Boolean) : Item
    private class Visit(val at: LatLng) : Item

    private val items = mutableListOf<Item>()
    private val vehicleWindows = mutableListOf<LongRange>()
    private val raw = mutableListOf<LatLng>()
    private var skipped = 0
    var sawSegment = false
      private set

    fun segment(s: JsonObject) {
      if ("timelinePath" in s || "visit" in s || "activity" in s) sawSegment = true
      val path = s["timelinePath"] as? JsonArray
      val activity = s.obj("activity")
      if (activity != null && isVehicle(activity.obj("topCandidate")?.str("type"))) {
        val from = timeOf(s.str("startTime"))
        val to = timeOf(s.str("endTime"))
        if (from != null && to != null) vehicleWindows += from..to
      }
      if (path != null && path.isNotEmpty()) {
        val start = timeOf(s.str("startTime"))
        items += Path(
          path.map { p ->
            val o = p as? JsonObject
            // Android는 점마다 time, iOS는 구간 시작에서 몇 분 뒤인지
            val t = o?.str("time")?.let(::timeOf)
              ?: o?.get("durationMinutesOffsetFromStartTime")?.let { offset ->
                val minutes = (offset as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (start != null && minutes != null) start + (minutes * 60_000).toLong() else null
              }
            latLng(o?.get("point")) to t
          },
        )
      } else if (activity != null) {
        items += Activity(latLng(activity["start"]), latLng(activity["end"]), isVehicle(activity.obj("topCandidate")?.str("type")))
      }
      latLng(s.obj("visit")?.obj("topCandidate")?.get("placeLocation"))?.let { items += Visit(it) }
    }

    fun rawSignal(s: JsonObject) {
      val pos = s.obj("position")
      latLng(pos?.get("LatLng") ?: pos?.get("latLng"))?.let { raw += it }
    }

    /** Records.json: 점마다 붙은 활동 추정이 차량이면 그 점에서 경로를 끊는다 */
    fun location(o: JsonObject) {
      // activity[0].activity[0].type (웹과 같은 경로)
      val guesses = ((o["activity"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("activity") as? JsonArray
      val type = (guesses?.firstOrNull() as? JsonObject)?.str("type")
      if (isVehicle(type)) {
        skipped++
        sink.endTrack()
        return
      }
      latLng(o)?.let(sink::point)
    }

    fun endLocations() = sink.endTrack()

    fun timelineObject(o: JsonObject) {
      o.obj("activitySegment")?.let { a ->
        if (isVehicle(a.str("activityType"))) {
          skipped++
        } else {
          val path = (a.obj("simplifiedRawPath")?.get("points") as? JsonArray)
            ?: (a.obj("waypointPath")?.get("waypoints") as? JsonArray)
            ?: JsonArray(emptyList())
          sink.track((listOf(a["startLocation"]) + path + listOf(a["endLocation"])).mapNotNull(::latLng))
        }
      }
      latLng(o.obj("placeVisit")?.get("location"))?.let { sink.track(listOf(it)) }
    }

    /** @return 뺀 차량 구간 수 */
    fun finish(): Int {
      val inVehicle = { t: Long? -> t != null && vehicleWindows.any { t in it } }
      for (item in items) when (item) {
        is Path -> {
          for ((point, t) in item.points) {
            if (inVehicle(t)) sink.endTrack() else point?.let(sink::point)
          }
          sink.endTrack()
        }
        is Activity -> if (item.vehicle) skipped++ else sink.track(listOf(item.start, item.end))
        is Visit -> sink.track(listOf(item.at))
      }
      if (raw.isNotEmpty()) sink.track(raw)
      return skipped
    }
  }

  /** 차량·대중교통·비행 (구글이 쓰는 이름: IN_PASSENGER_VEHICLE, IN_BUS, IN_SUBWAY, FLYING …, iOS는 "in passenger vehicle") */
  internal fun isVehicle(type: String?): Boolean {
    if (type == null) return false
    val t = type.trim().uppercase().replace(Regex("\\s+"), "_")
    return t.startsWith("IN_") || t in setOf("FLYING", "MOTORCYCLING", "BOATING", "SAILING")
  }

  private fun timeOf(iso: String?): Long? {
    if (iso == null) return null
    return try {
      OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: Exception) {
      try {
        Instant.parse(iso).toEpochMilli()
      } catch (e2: Exception) {
        null
      }
    }
  }

  private val latLngText = Regex("(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*,\\s*(-?\\d+(?:\\.\\d+)?)")

  /** "37.5446°, 127.0560°" · "geo:37.5446,127.0560" · { latLng } · { latitudeE7, longitudeE7 } · { latE7, lngE7 } */
  private fun latLng(v: JsonElement?): LatLng? {
    if (v == null || v is JsonNull) return null
    if (v is JsonPrimitive) {
      if (!v.isString) return null
      val m = latLngText.find(v.content) ?: return null
      return LatLng(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }
    val o = v as? JsonObject ?: return null
    (o["latLng"] ?: o["LatLng"])?.let { return latLng(it) }
    if ("latitudeE7" in o) return LatLng(e7(o["latitudeE7"]), e7(o["longitudeE7"]))
    if ("latE7" in o) return LatLng(e7(o["latE7"]), e7(o["lngE7"]))
    return null
  }

  /** 예전 Records.json에는 음수가 부호 없는 32비트로 기록된 값이 섞여 있다 */
  private fun e7(v: JsonElement?): Double {
    val n = (v as? JsonPrimitive)?.doubleOrNull ?: return Double.NaN
    return (if (n > 1_800_000_000) n - 4_294_967_296.0 else n) / 1e7
  }

  private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
}
