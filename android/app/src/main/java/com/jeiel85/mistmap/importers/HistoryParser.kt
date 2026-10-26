package com.jeiel85.mistmap.importers

import com.jeiel85.mistmap.reveal.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class ImportException(message: String) : Exception(message)

/** 한 track 안의 점은 시간 순으로 이어진 경로이고, 점이 하나뿐인 track은 머문 장소(방문지)다. */
data class ParsedHistory(val format: String, val tracks: List<List<LatLng>>)

/**
 * 외부 이동 기록 파일 → 경로 목록. 웹의 `src/importers.js`를 그대로 옮겼다.
 * 두 쪽 결과가 같아야 하므로 한쪽을 고치면 다른 쪽과 테스트(`tests/importers.test.js`, `HistoryParserTest`)도 함께 고친다.
 */
object HistoryParser {
  private val json = Json { isLenient = false }

  fun parse(text: String): ParsedHistory {
    val src = text.removePrefix("\uFEFF").trimStart()
    if (src.startsWith("<")) {
      if (Regex("<gpx[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(src)) return finish("GPX", parseGpx(src))
      if (Regex("<kml[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(src)) return finish("KML", parseKml(src))
      throw ImportException("지원하지 않는 XML 파일이에요. GPX나 KML만 읽을 수 있어요")
    }
    val data = try {
      json.parseToJsonElement(src)
    } catch (e: Exception) {
      throw ImportException("파일을 읽을 수 없어요. GPX·KML·GeoJSON·구글 타임라인 JSON인지 확인해 주세요")
    }
    if (isGeoJson(data)) return finish("GeoJSON", parseGeoJson(data))
    parseGoogleTimeline(data)?.let { return finish("구글 타임라인", it) }
    throw ImportException("알 수 없는 JSON 형식이에요. GeoJSON이나 구글 타임라인 내보내기 파일만 읽을 수 있어요")
  }

  private fun finish(format: String, tracks: List<List<LatLng?>>): ParsedHistory {
    val clean = tracks.map { t -> t.filterNotNull().filter(::valid) }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) throw ImportException("$format 파일에 위치 기록이 없어요")
    return ParsedHistory(format, clean)
  }

  private fun valid(p: LatLng) =
    p.lat.isFinite() && p.lng.isFinite() && kotlin.math.abs(p.lat) <= 90 && kotlin.math.abs(p.lng) <= 180 &&
      !(p.lat == 0.0 && p.lng == 0.0)

  // ---- GPX ----
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

  // ---- KML ----
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
  private fun parseGoogleTimeline(d: JsonElement): List<List<LatLng?>>? {
    if (d is JsonArray) return if (d.any(::isSegmentLike)) segmentsToTracks(d) else null
    val o = d as? JsonObject ?: return null

    if (o["semanticSegments"] is JsonArray || o["rawSignals"] is JsonArray) {
      val tracks = segmentsToTracks(o["semanticSegments"] as? JsonArray ?: JsonArray(emptyList()))
      val raw = (o["rawSignals"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { s ->
        val pos = s.obj("position")
        latLng(pos?.get("LatLng") ?: pos?.get("latLng"))
      }
      if (raw.isNotEmpty()) tracks += raw
      return tracks
    }
    (o["locations"] as? JsonArray)?.let { locations -> return listOf(locations.mapNotNull(::latLng)) }
    (o["timelineObjects"] as? JsonArray)?.let { objects ->
      val tracks = mutableListOf<List<LatLng?>>()
      for (obj in objects) {
        obj.obj("activitySegment")?.let { a ->
          val path = (a.obj("simplifiedRawPath")?.get("points") as? JsonArray)
            ?: (a.obj("waypointPath")?.get("waypoints") as? JsonArray)
            ?: JsonArray(emptyList())
          tracks += (listOf(a["startLocation"]) + path + listOf(a["endLocation"])).mapNotNull(::latLng)
        }
        latLng(obj.obj("placeVisit")?.get("location"))?.let { tracks += listOf(it) }
      }
      return tracks
    }
    return null
  }

  private fun isSegmentLike(s: JsonElement) =
    s is JsonObject && ("timelinePath" in s || "visit" in s || "activity" in s)

  private fun segmentsToTracks(segments: JsonArray): MutableList<List<LatLng?>> {
    val tracks = mutableListOf<List<LatLng?>>()
    for (s in segments) {
      val o = s as? JsonObject ?: continue
      val path = o["timelinePath"] as? JsonArray
      val activity = o.obj("activity")
      if (path != null && path.isNotEmpty()) {
        tracks += path.map { latLng((it as? JsonObject)?.get("point")) }
      } else if (activity != null) {
        tracks += listOfNotNull(latLng(activity["start"]), latLng(activity["end"]))
      }
      latLng(o.obj("visit")?.obj("topCandidate")?.get("placeLocation"))?.let { tracks += listOf(it) }
    }
    return tracks
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
