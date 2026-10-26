import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseHistory, ImportError } from '../src/importers.js';

const near = (p, lat, lng) => {
  assert.ok(Math.abs(p.lat - lat) < 1e-6 && Math.abs(p.lng - lng) < 1e-6, `${JSON.stringify(p)} ≠ ${lat},${lng}`);
};

test('GPX: 트랙 세그먼트별로 나누고 웨이포인트는 방문지로', () => {
  const r = parseHistory(`<?xml version="1.0"?>
    <gpx version="1.1" creator="test">
      <wpt lat="37.5" lon="127.0"><name>집</name></wpt>
      <trk><trkseg>
        <trkpt lon="127.01" lat="37.51"><ele>10</ele></trkpt>
        <trkpt lat='37.52' lon='127.02'/>
      </trkseg><trkseg>
        <trkpt lat="37.6" lon="127.1"></trkpt>
      </trkseg></trk>
      <rte><rtept lat="35.1" lon="129.0"/><rtept lat="35.2" lon="129.1"/></rte>
    </gpx>`);
  assert.equal(r.format, 'GPX');
  assert.deepEqual(r.tracks.map((t) => t.length), [1, 2, 1, 2]);
  near(r.tracks[0][0], 37.5, 127.0);
  near(r.tracks[1][0], 37.51, 127.01); // lon이 lat보다 먼저 와도 읽는다
  near(r.tracks[3][1], 35.2, 129.1);
});

test('KML: coordinates와 gx:Track', () => {
  const r = parseHistory(`<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
    <Placemark><LineString><coordinates>
      127.0,37.5,0 127.01,37.51,0
    </coordinates></LineString></Placemark>
    <Placemark><gx:Track><when>t</when><gx:coord>127.1 37.6 12</gx:coord><gx:coord>127.2 37.7 12</gx:coord></gx:Track></Placemark>
  </kml>`);
  assert.equal(r.format, 'KML');
  assert.deepEqual(r.tracks.map((t) => t.length), [2, 2]);
  near(r.tracks[1][1], 37.7, 127.2);
});

test('GeoJSON: 선·점·다중 도형', () => {
  const r = parseHistory(JSON.stringify({
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', geometry: { type: 'LineString', coordinates: [[127, 37.5], [127.01, 37.51]] } },
      { type: 'Feature', geometry: { type: 'Point', coordinates: [127.2, 37.7] } },
      { type: 'Feature', geometry: { type: 'MultiLineString', coordinates: [[[1, 2], [3, 4]], [[5, 6]]] } },
    ],
  }));
  assert.equal(r.format, 'GeoJSON');
  assert.deepEqual(r.tracks.map((t) => t.length), [2, 1, 2, 1]);
  near(r.tracks[1][0], 37.7, 127.2);
});

test('구글 타임라인 (Android 기기 내보내기)', () => {
  const r = parseHistory(JSON.stringify({
    semanticSegments: [
      {
        startTime: '2026-10-01T09:00:00.000+09:00',
        timelinePath: [
          { point: '37.5446°, 127.0560°', time: '2026-10-01T09:00:00.000+09:00' },
          { point: '37.5450°, 127.0570°', time: '2026-10-01T09:05:00.000+09:00' },
        ],
      },
      { visit: { topCandidate: { placeLocation: { latLng: '37.5443°, 127.0374°' } } } },
      { activity: { start: { latLng: '37.50°, 127.00°' }, end: { latLng: '37.51°, 127.01°' } } },
    ],
    rawSignals: [{ position: { LatLng: '37.52°, 127.02°' } }, { wifiScan: {} }],
    userLocationProfile: {},
  }));
  assert.equal(r.format, '구글 타임라인');
  assert.deepEqual(r.tracks.map((t) => t.length), [2, 1, 2, 1]);
  near(r.tracks[0][1], 37.545, 127.057);
  near(r.tracks[1][0], 37.5443, 127.0374);
});

test('구글 타임라인 (iOS 내보내기, geo: 문자열)', () => {
  const r = parseHistory(JSON.stringify([
    { startTime: 't', visit: { topCandidate: { placeLocation: 'geo:37.544300,127.037400' } } },
    { activity: { start: 'geo:37.5,127.0', end: 'geo:37.51,127.01' } },
    { timelinePath: [{ point: 'geo:37.6,127.1', durationMinutesOffsetFromStartTime: '0' }] },
  ]));
  assert.deepEqual(r.tracks.map((t) => t.length), [1, 2, 1]);
  near(r.tracks[0][0], 37.5443, 127.0374);
});

test('구글 테이크아웃 Records.json · Semantic Location History', () => {
  const rec = parseHistory(JSON.stringify({
    locations: [
      { latitudeE7: 375446000, longitudeE7: 1270560000 },
      { latitudeE7: 4294967296 - 338000000, longitudeE7: 1512000000 }, // 부호 없는 음수 표기
      { timestamp: 'no-position' },
    ],
  }));
  assert.equal(rec.tracks[0].length, 2);
  near(rec.tracks[0][1], -33.8, 151.2);

  const sem = parseHistory(JSON.stringify({
    timelineObjects: [
      { activitySegment: {
        startLocation: { latitudeE7: 375000000, longitudeE7: 1270000000 },
        endLocation: { latitudeE7: 375100000, longitudeE7: 1270100000 },
        waypointPath: { waypoints: [{ latE7: 375050000, lngE7: 1270050000 }] },
      } },
      { placeVisit: { location: { latitudeE7: 375200000, longitudeE7: 1270200000 } } },
    ],
  }));
  assert.deepEqual(sem.tracks.map((t) => t.length), [3, 1]);
  near(sem.tracks[0][1], 37.505, 127.005);
});

test('잘못된 좌표는 버리고, 기록이 없으면 알려 준다', () => {
  const r = parseHistory(JSON.stringify({ type: 'LineString', coordinates: [[0, 0], [200, 10], [127, 37.5]] }));
  assert.deepEqual(r.tracks, [[{ lng: 127, lat: 37.5 }]]);
  assert.throws(() => parseHistory('<gpx></gpx>'), ImportError);
  assert.throws(() => parseHistory('{"hello": 1}'), /알 수 없는 JSON/);
  assert.throws(() => parseHistory('not json'), /읽을 수 없어요/);
  assert.throws(() => parseHistory('<html></html>'), /XML/);
});

test('UTF-8 BOM이 붙은 파일도 읽는다', () => {
  const r = parseHistory('﻿<gpx><wpt lat="1" lon="2"/></gpx>');
  near(r.tracks[0][0], 1, 2);
});
