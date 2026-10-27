package com.jeiel85.mistmap.importers

import com.jeiel85.mistmap.reveal.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `tests/importers.test.js`와 같은 입력, 같은 기대값. 웹 파서와 결과가 같아야 한다. */
class HistoryParserTest {
  private fun near(p: LatLng, lat: Double, lng: Double) =
    assertTrue("$p ≠ $lat,$lng", kotlin.math.abs(p.lat - lat) < 1e-6 && kotlin.math.abs(p.lng - lng) < 1e-6)

  private fun sizes(r: ParsedHistory) = r.tracks.map { it.size }

  @Test fun gpxSplitsSegmentsAndWaypoints() {
    val r = HistoryParser.parse(
      """<?xml version="1.0"?>
      <gpx version="1.1" creator="test">
        <wpt lat="37.5" lon="127.0"><name>집</name></wpt>
        <trk><trkseg>
          <trkpt lon="127.01" lat="37.51"><ele>10</ele></trkpt>
          <trkpt lat='37.52' lon='127.02'/>
        </trkseg><trkseg>
          <trkpt lat="37.6" lon="127.1"></trkpt>
        </trkseg></trk>
        <rte><rtept lat="35.1" lon="129.0"/><rtept lat="35.2" lon="129.1"/></rte>
      </gpx>""",
    )
    assertEquals("GPX", r.format)
    assertEquals(listOf(1, 2, 1, 2), sizes(r))
    near(r.tracks[0][0], 37.5, 127.0)
    near(r.tracks[1][0], 37.51, 127.01) // lon이 lat보다 먼저 와도 읽는다
    near(r.tracks[3][1], 35.2, 129.1)
  }

  @Test fun kmlCoordinatesAndGxTrack() {
    val r = HistoryParser.parse(
      """<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
        <Placemark><LineString><coordinates>
          127.0,37.5,0 127.01,37.51,0
        </coordinates></LineString></Placemark>
        <Placemark><gx:Track><when>t</when><gx:coord>127.1 37.6 12</gx:coord><gx:coord>127.2 37.7 12</gx:coord></gx:Track></Placemark>
      </kml>""",
    )
    assertEquals("KML", r.format)
    assertEquals(listOf(2, 2), sizes(r))
    near(r.tracks[1][1], 37.7, 127.2)
  }

  @Test fun geoJsonLinesPointsAndMulti() {
    val r = HistoryParser.parse(
      """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127,37.5],[127.01,37.51]]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[127.2,37.7]}},
        {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[[[1,2],[3,4]],[[5,6]]]}}
      ]}""",
    )
    assertEquals("GeoJSON", r.format)
    assertEquals(listOf(2, 1, 2, 1), sizes(r))
    near(r.tracks[1][0], 37.7, 127.2)
  }

  @Test fun googleTimelineAndroidExport() {
    val r = HistoryParser.parse(
      """{
        "semanticSegments":[
          {"startTime":"2026-10-01T09:00:00.000+09:00","timelinePath":[
            {"point":"37.5446°, 127.0560°","time":"2026-10-01T09:00:00.000+09:00"},
            {"point":"37.5450°, 127.0570°","time":"2026-10-01T09:05:00.000+09:00"}]},
          {"visit":{"topCandidate":{"placeLocation":{"latLng":"37.5443°, 127.0374°"}}}},
          {"activity":{"start":{"latLng":"37.50°, 127.00°"},"end":{"latLng":"37.51°, 127.01°"}}}
        ],
        "rawSignals":[{"position":{"LatLng":"37.52°, 127.02°"}},{"wifiScan":{}}],
        "userLocationProfile":{}
      }""",
    )
    assertEquals("구글 타임라인", r.format)
    assertEquals(listOf(2, 1, 2, 1), sizes(r))
    near(r.tracks[0][1], 37.545, 127.057)
    near(r.tracks[1][0], 37.5443, 127.0374)
  }

  @Test fun googleTimelineIosExport() {
    val r = HistoryParser.parse(
      """[
        {"startTime":"t","visit":{"topCandidate":{"placeLocation":"geo:37.544300,127.037400"}}},
        {"activity":{"start":"geo:37.5,127.0","end":"geo:37.51,127.01"}},
        {"timelinePath":[{"point":"geo:37.6,127.1","durationMinutesOffsetFromStartTime":"0"}]}
      ]""",
    )
    assertEquals(listOf(1, 2, 1), sizes(r))
    near(r.tracks[0][0], 37.5443, 127.0374)
  }

  @Test fun takeoutRecordsAndSemanticHistory() {
    val rec = HistoryParser.parse(
      """{"locations":[
        {"latitudeE7":375446000,"longitudeE7":1270560000},
        {"latitudeE7":${4294967296 - 338000000},"longitudeE7":1512000000},
        {"timestamp":"no-position"}]}""",
    )
    assertEquals(2, rec.tracks[0].size)
    near(rec.tracks[0][1], -33.8, 151.2)

    val sem = HistoryParser.parse(
      """{"timelineObjects":[
        {"activitySegment":{
          "startLocation":{"latitudeE7":375000000,"longitudeE7":1270000000},
          "endLocation":{"latitudeE7":375100000,"longitudeE7":1270100000},
          "waypointPath":{"waypoints":[{"latE7":375050000,"lngE7":1270050000}]}}},
        {"placeVisit":{"location":{"latitudeE7":375200000,"longitudeE7":1270200000}}}]}""",
    )
    assertEquals(listOf(3, 1), sizes(sem))
    near(sem.tracks[0][1], 37.505, 127.005)
  }

  @Test fun dropsInvalidCoordinatesAndReportsEmptyFiles() {
    val r = HistoryParser.parse("""{"type":"LineString","coordinates":[[0,0],[200,10],[127,37.5]]}""")
    assertEquals(listOf(listOf(LatLng(37.5, 127.0))), r.tracks)
    assertThrows(ImportException::class.java) { HistoryParser.parse("<gpx></gpx>") }
    assertTrue(assertThrows(ImportException::class.java) { HistoryParser.parse("""{"hello": 1}""") }.message!!.contains("알 수 없는 JSON"))
    assertTrue(assertThrows(ImportException::class.java) { HistoryParser.parse("not json") }.message!!.contains("읽을 수 없어요"))
    assertTrue(assertThrows(ImportException::class.java) { HistoryParser.parse("<html></html>") }.message!!.contains("XML"))
  }

  @Test fun readsFilesWithUtf8Bom() {
    near(HistoryParser.parse("﻿<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>").tracks[0][0], 1.0, 2.0)
  }

  // 저장소의 samples/를 웹과 함께 쓴다. 기대값은 웹 파서(node)로 같은 파일을 읽어 얻었다.
  private fun sample(name: String) = File("../../samples/$name").readText()

  @Test fun seongsuWalkSampleMatchesWeb() {
    val r = HistoryParser.parse(sample("seongsu-walk.gpx"))
    assertEquals("GPX", r.format)
    assertEquals(listOf(1, 417), sizes(r))
    near(r.tracks[0][0], 37.5443, 127.0374)
    near(r.tracks.last().last(), 37.5446, 127.056)
  }

  @Test fun googleTimelineSampleMatchesWeb() {
    val r = HistoryParser.parse(sample("google-timeline-sample.json"))
    assertEquals("구글 타임라인", r.format)
    // 샘플의 지하철(IN_SUBWAY) 구간과 그 시간대의 경로 점은 빠진다 (웹 파서 결과와 같음: [1,20,1], 1구간)
    assertEquals(listOf(1, 20, 1), sizes(r))
    assertEquals(1, r.vehicleSkipped)
    near(r.tracks[0][0], 37.5404, 127.0692)
    near(r.tracks.last().last(), 37.5612, 127.0371)
  }

  // ---- 차량 구간 빼기 (tests/importers.test.js와 같은 입력) ----

  @Test fun androidExportDropsVehicleActivityAndSplitsPath() {
    val r = HistoryParser.parse(
      """{"semanticSegments":[
        {"startTime":"2026-10-01T09:00:00.000+09:00","endTime":"2026-10-01T09:30:00.000+09:00","timelinePath":[
          {"point":"37.50°, 127.00°","time":"2026-10-01T09:00:00.000+09:00"},
          {"point":"37.51°, 127.01°","time":"2026-10-01T09:05:00.000+09:00"},
          {"point":"37.60°, 127.10°","time":"2026-10-01T09:15:00.000+09:00"},
          {"point":"37.70°, 127.20°","time":"2026-10-01T09:25:00.000+09:00"}]},
        {"startTime":"2026-10-01T09:10:00.000+09:00","endTime":"2026-10-01T09:20:00.000+09:00",
         "activity":{"start":{"latLng":"37.51°, 127.01°"},"end":{"latLng":"37.69°, 127.19°"},"topCandidate":{"type":"IN_BUS","probability":0.8}}},
        {"startTime":"2026-10-01T09:20:00.000+09:00","endTime":"2026-10-01T09:30:00.000+09:00",
         "activity":{"start":{"latLng":"37.69°, 127.19°"},"end":{"latLng":"37.70°, 127.20°"},"topCandidate":{"type":"WALKING"}}}
      ]}""",
    )
    assertEquals(listOf(2, 1, 2), sizes(r))
    assertEquals(1, r.vehicleSkipped)
  }

  @Test fun rawSignalsInsideVehicleWindowAreDropped() {
    val r = HistoryParser.parse(
      """{"semanticSegments":[
        {"startTime":"2026-10-01T09:00:00.000+09:00","endTime":"2026-10-01T09:20:00.000+09:00",
         "activity":{"start":{"latLng":"37.50°, 127.00°"},"end":{"latLng":"37.60°, 127.10°"},"topCandidate":{"type":"IN_BUS"}}}],
       "rawSignals":[
        {"position":{"LatLng":"37.49°, 126.99°","timestamp":"2026-10-01T08:55:00.000+09:00"}},
        {"position":{"LatLng":"37.55°, 127.05°","timestamp":"2026-10-01T09:10:00.000+09:00"}},
        {"position":{"LatLng":"37.61°, 127.11°","timestamp":"2026-10-01T09:25:00.000+09:00"}},
        {"position":{"LatLng":"37.62°, 127.12°"}}]}""",
    )
    // 버스 안 원시 위치는 빠지고 그 자리에서 끊긴다(웹 테스트와 같은 기대값). 시각 없는 점은 남긴다
    assertEquals(listOf(1, 2), sizes(r))
    assertEquals(1, r.vehicleSkipped)
  }

  @Test fun manyVehicleWindowsFilterQuickly() {
    // 차량 구간 8,000개 + 걷기 경로 점 80,000개(웹 테스트와 같은 크기). 점마다 구간을 전부 훑으면 수 초 걸린다
    val base = java.time.Instant.parse("2026-01-01T00:00:00Z")
    val json = buildString {
      append("{\"semanticSegments\":[")
      for (i in 0 until 8000) {
        val from = base.plusSeconds(i * 3600L)
        append("{\"startTime\":\"").append(from).append("\",\"endTime\":\"").append(from.plusSeconds(600))
        append("\",\"activity\":{\"start\":\"geo:37.5,127.0\",\"end\":\"geo:37.6,127.1\",\"topCandidate\":{\"type\":\"IN_BUS\"}}},")
      }
      append("{\"startTime\":\"").append(base).append("\",\"endTime\":\"").append(base.plusSeconds(90_000)).append("\",\"timelinePath\":[")
      for (i in 0 until 80_000) {
        if (i > 0) append(',')
        append("{\"point\":\"geo:").append("%.5f".format(37.5 + (i % 1000) * 1e-5)).append(",127.0\",\"time\":\"")
        append(base.plusSeconds(1200L + i)).append("\"}")
      }
      append("]}]}")
    }
    val t0 = System.currentTimeMillis()
    val r = HistoryParser.parse(json)
    val ms = System.currentTimeMillis() - t0
    assertEquals(8000, r.vehicleSkipped)
    assertTrue("${ms}ms", ms < 5000)
  }

  @Test fun iosTakeoutAndRecordsDropVehicle() {
    val ios = HistoryParser.parse(
      """[
        {"startTime":"2026-10-01T09:00:00Z","endTime":"2026-10-01T09:20:00Z","activity":{"start":"geo:37.5,127.0","end":"geo:37.6,127.1","topCandidate":{"type":"in passenger vehicle"}}},
        {"startTime":"2026-10-01T09:00:00Z","timelinePath":[
          {"point":"geo:37.50,127.00","durationMinutesOffsetFromStartTime":"10"},
          {"point":"geo:37.61,127.11","durationMinutesOffsetFromStartTime":"30"}]}
      ]""",
    )
    assertEquals(listOf(1), sizes(ios))
    assertEquals(1, ios.vehicleSkipped)

    val sem = HistoryParser.parse(
      """{"timelineObjects":[
        {"activitySegment":{"activityType":"IN_SUBWAY",
          "startLocation":{"latitudeE7":375000000,"longitudeE7":1270000000},"endLocation":{"latitudeE7":376000000,"longitudeE7":1271000000}}},
        {"activitySegment":{"activityType":"WALKING",
          "startLocation":{"latitudeE7":376000000,"longitudeE7":1271000000},"endLocation":{"latitudeE7":376010000,"longitudeE7":1271010000}}}
      ]}""",
    )
    assertEquals(listOf(2), sizes(sem))
    assertEquals(1, sem.vehicleSkipped)

    val rec = HistoryParser.parse(
      """{"locations":[
        {"latitudeE7":375000000,"longitudeE7":1270000000},
        {"latitudeE7":375100000,"longitudeE7":1270100000,"activity":[{"activity":[{"type":"IN_VEHICLE","confidence":90}]}]},
        {"latitudeE7":375200000,"longitudeE7":1270200000}
      ]}""",
    )
    assertEquals(listOf(1, 1), sizes(rec))
    assertEquals(1, rec.vehicleSkipped)
  }

  // ---- 스트리밍 ----

  @Test fun streamsLargeRecordsWithoutCollectingTracks() {
    // 20만 점 Records.json (약 10MB). 받는 쪽은 점을 세기만 해서, 파서가 경로 목록을 만들지 않고도 끝까지 읽는지 본다
    val n = 200_000
    val json = buildString {
      append("{\"locations\":[")
      for (i in 0 until n) {
        if (i > 0) append(',')
        append("{\"latitudeE7\":").append(375000000 + i).append(",\"longitudeE7\":").append(1270000000 + i).append('}')
      }
      append("]}")
    }
    var points = 0
    var tracks = 0
    var checks = 0
    val summary = HistoryParser.stream(
      json.byteInputStream(),
      object : TrackSink {
        override fun point(p: LatLng) { points++ }
        override fun endTrack() { tracks++ }
      },
      checkCancelled = { checks++ },
    )
    assertEquals(n, summary.points)
    assertEquals(n, points)
    assertEquals(1, tracks)
    assertEquals(n, checks) // 요소마다 취소 여부를 묻는다
  }

  @Test fun cancellationStopsStreaming() {
    val json = "{\"locations\":[" + (0 until 1000).joinToString(",") { "{\"latitudeE7\":375000000,\"longitudeE7\":1270000000}" } + "]}"
    // 가져오기 취소는 코루틴의 CancellationException으로 온다. 형식 오류("읽을 수 없어요")로 바뀌면 안 된다
    var seen = 0
    val e = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
      HistoryParser.stream(
        json.byteInputStream(),
        object : TrackSink {
          override fun point(p: LatLng) {}
          override fun endTrack() {}
        },
        checkCancelled = { if (++seen > 10) throw kotlinx.coroutines.CancellationException("취소") },
      )
    }
    assertEquals("취소", e.message)
  }
}
