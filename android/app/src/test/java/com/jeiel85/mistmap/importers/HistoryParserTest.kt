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
    assertEquals(listOf(1, 50, 2, 1), sizes(r))
    near(r.tracks[0][0], 37.5404, 127.0692)
    near(r.tracks.last().last(), 37.5612, 127.0371)
  }
}
