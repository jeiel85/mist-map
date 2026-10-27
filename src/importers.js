// 외부 이동 기록 파일 → 경로 목록
//
// parseHistory(text)는 { format, tracks }를 돌려준다.
// tracks는 [{ lng, lat }, ...]의 배열이고, 한 track 안의 점은 시간 순으로 이어진 경로다.
// 점이 하나뿐인 track은 머문 장소(방문지)다.
// 구글 타임라인은 이동 수단을 함께 적어 두므로 차량·대중교통·비행 구간은 빼고(걸은 곳만 밝힌다), 뺀 구간 수를 vehicleSkipped로 알린다.
// 같은 규칙을 앱(android/.../importers/HistoryParser.kt)도 따른다.
// DOM에 기대지 않아 브라우저와 Node 테스트에서 똑같이 동작한다.

export class ImportError extends Error {}

export function parseHistory(text) {
  const src = text.replace(/^﻿/, '').trimStart();
  if (src.startsWith('<')) {
    if (/<gpx[\s>]/i.test(src)) return finish('GPX', parseGpx(src));
    if (/<kml[\s>]/i.test(src)) return finish('KML', parseKml(src));
    throw new ImportError('지원하지 않는 XML 파일이에요. GPX나 KML만 읽을 수 있어요');
  }

  let data;
  try {
    data = JSON.parse(src);
  } catch {
    throw new ImportError('파일을 읽을 수 없어요. GPX·KML·GeoJSON·구글 타임라인 JSON인지 확인해 주세요');
  }
  if (isGeoJson(data)) return finish('GeoJSON', parseGeoJson(data));
  const google = parseGoogleTimeline(data);
  if (google) return finish('구글 타임라인', google.tracks, google.vehicleSkipped);
  throw new ImportError('알 수 없는 JSON 형식이에요. GeoJSON이나 구글 타임라인 내보내기 파일만 읽을 수 있어요');
}

function finish(format, tracks, vehicleSkipped = 0) {
  const clean = tracks.map((t) => t.filter(valid)).filter((t) => t.length > 0);
  if (clean.length === 0) throw new ImportError(`${format} 파일에 위치 기록이 없어요`);
  return { format, tracks: clean, vehicleSkipped };
}

// 차량·대중교통·비행 (구글이 쓰는 이름: IN_PASSENGER_VEHICLE, IN_BUS, IN_SUBWAY, FLYING …, iOS는 "in passenger vehicle")
function isVehicle(type) {
  if (typeof type !== 'string') return false;
  const t = type.trim().toUpperCase().replace(/\s+/g, '_');
  return t.startsWith('IN_') || ['FLYING', 'MOTORCYCLING', 'BOATING', 'SAILING'].includes(t);
}

function valid(p) {
  return Number.isFinite(p.lat) && Number.isFinite(p.lng)
    && Math.abs(p.lat) <= 90 && Math.abs(p.lng) <= 180
    && !(p.lat === 0 && p.lng === 0);
}

// ---- GPX ----
function parseGpx(xml) {
  const tracks = [];
  let seg = null;
  const re = /<(trkseg|rte)\b[^>]*>|<\/(trkseg|rte)\s*>|<(trkpt|rtept|wpt)\b([^>]*)>/gi;
  for (const m of xml.matchAll(re)) {
    if (m[1]) {
      seg = [];
      tracks.push(seg);
    } else if (m[2]) {
      seg = null;
    } else {
      const lat = attr(m[4], 'lat'), lng = attr(m[4], 'lon');
      const p = { lat, lng };
      if (m[3].toLowerCase() === 'wpt' || !seg) tracks.push([p]);
      else seg.push(p);
    }
  }
  return tracks;
}

function attr(attrs, name) {
  const m = attrs.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`, 'i'));
  return m ? parseFloat(m[1]) : NaN;
}

// ---- KML ----
function parseKml(xml) {
  const tracks = [];
  for (const m of xml.matchAll(/<coordinates[^>]*>([\s\S]*?)<\/coordinates>/gi)) {
    tracks.push(m[1].trim().split(/\s+/).filter(Boolean).map((tuple) => {
      const [lng, lat] = tuple.split(',').map(parseFloat);
      return { lng, lat };
    }));
  }
  for (const m of xml.matchAll(/<gx:Track\b[^>]*>([\s\S]*?)<\/gx:Track>/gi)) {
    tracks.push([...m[1].matchAll(/<gx:coord>([^<]+)<\/gx:coord>/gi)].map((c) => {
      const [lng, lat] = c[1].trim().split(/\s+/).map(parseFloat);
      return { lng, lat };
    }));
  }
  return tracks;
}

// ---- GeoJSON ----
const GEOJSON_TYPES = new Set([
  'FeatureCollection', 'Feature', 'GeometryCollection',
  'Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon',
]);

function isGeoJson(d) {
  return d && typeof d === 'object' && !Array.isArray(d) && GEOJSON_TYPES.has(d.type);
}

function parseGeoJson(d) {
  const tracks = [];
  const line = (coords) => coords.map(([lng, lat]) => ({ lng, lat }));
  const walk = (g) => {
    if (!g) return;
    switch (g.type) {
      case 'FeatureCollection': g.features?.forEach(walk); break;
      case 'Feature': walk(g.geometry); break;
      case 'GeometryCollection': g.geometries?.forEach(walk); break;
      case 'Point': tracks.push(line([g.coordinates])); break;
      case 'MultiPoint': g.coordinates.forEach((c) => tracks.push(line([c]))); break;
      case 'LineString': tracks.push(line(g.coordinates)); break;
      case 'MultiLineString':
      case 'Polygon': g.coordinates.forEach((c) => tracks.push(line(c))); break;
      case 'MultiPolygon': g.coordinates.forEach((poly) => poly.forEach((c) => tracks.push(line(c)))); break;
    }
  };
  walk(d);
  return tracks;
}

// ---- 구글 타임라인 ----
// 지원 형식
//  1. 휴대폰 "타임라인 데이터 내보내기"(Android): { semanticSegments, rawSignals }
//  2. 같은 기능의 iOS 내보내기: 최상위 배열, 좌표가 "geo:lat,lng" 문자열
//  3. 예전 테이크아웃 Records.json: { locations: [{ latitudeE7, longitudeE7 }] }
//  4. 예전 테이크아웃 월별 Semantic Location History: { timelineObjects }
function parseGoogleTimeline(d) {
  if (Array.isArray(d)) return d.some(isSegmentLike) ? segmentsToTracks(d) : null;
  if (!d || typeof d !== 'object') return null;

  if (Array.isArray(d.semanticSegments) || Array.isArray(d.rawSignals)) {
    return segmentsToTracks(d.semanticSegments ?? [], d.rawSignals ?? []);
  }
  if (Array.isArray(d.locations)) {
    // 점마다 붙은 활동 추정이 차량이면 그 점에서 경로를 끊는다
    const tracks = [[]];
    let vehicleSkipped = 0;
    for (const loc of d.locations) {
      if (isVehicle(loc.activity?.[0]?.activity?.[0]?.type)) {
        vehicleSkipped++;
        if (tracks.at(-1).length) tracks.push([]);
        continue;
      }
      const p = latLng(loc);
      if (p) tracks.at(-1).push(p);
    }
    return { tracks, vehicleSkipped };
  }
  if (Array.isArray(d.timelineObjects)) {
    const tracks = [];
    let vehicleSkipped = 0;
    for (const o of d.timelineObjects) {
      const a = o.activitySegment;
      if (a && isVehicle(a.activityType)) {
        vehicleSkipped++;
      } else if (a) {
        const path = a.simplifiedRawPath?.points ?? a.waypointPath?.waypoints ?? [];
        tracks.push([a.startLocation, ...path, a.endLocation].map(latLng).filter(Boolean));
      }
      const v = latLng(o.placeVisit?.location);
      if (v) tracks.push([v]);
    }
    return { tracks, vehicleSkipped };
  }
  return null;
}

function isSegmentLike(s) {
  return s && typeof s === 'object' && ('timelinePath' in s || 'visit' in s || 'activity' in s);
}

const timeOf = (iso) => {
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : null;
};

/** 시간 구간들을 정렬·병합해 두고, 시각이 그중 하나에 드는지 이분 탐색으로 답한다(몇 년 치 기록도 점마다 전부 훑지 않게) */
function windowLookup(windows) {
  const merged = [];
  for (const [a, b] of [...windows].sort((x, y) => x[0] - y[0])) {
    const last = merged.at(-1);
    if (last && a <= last[1]) last[1] = Math.max(last[1], b);
    else merged.push([a, b]);
  }
  return (t) => {
    if (t === null || !merged.length) return false;
    let lo = 0, hi = merged.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (t < merged[mid][0]) hi = mid - 1;
      else if (t > merged[mid][1]) lo = mid + 1;
      else return true;
    }
    return false;
  };
}

function segmentsToTracks(segments, rawSignals = []) {
  // 차량 활동 구간의 시간대. 경로(timelinePath) 점 중 이 시간에 든 것은 빼고 그 자리에서 경로를 끊는다
  const vehicleWindows = [];
  for (const s of segments) {
    if (s.activity && isVehicle(s.activity.topCandidate?.type)) {
      const from = timeOf(s.startTime), to = timeOf(s.endTime);
      if (from !== null && to !== null) vehicleWindows.push([from, to]);
    }
  }
  const inVehicle = windowLookup(vehicleWindows);

  const tracks = [];
  let vehicleSkipped = 0;
  for (const s of segments) {
    if (Array.isArray(s.timelinePath) && s.timelinePath.length) {
      const start = timeOf(s.startTime);
      let track = [];
      for (const p of s.timelinePath) {
        // Android는 점마다 time, iOS는 구간 시작에서 몇 분 뒤인지
        const t = p.time ? timeOf(p.time)
          : start !== null && p.durationMinutesOffsetFromStartTime !== undefined
            ? start + Number(p.durationMinutesOffsetFromStartTime) * 60000 : null;
        if (inVehicle(t)) {
          if (track.length) { tracks.push(track); track = []; }
          continue;
        }
        const ll = latLng(p.point);
        if (ll) track.push(ll);
      }
      if (track.length) tracks.push(track);
    } else if (s.activity) {
      if (isVehicle(s.activity.topCandidate?.type)) vehicleSkipped++;
      else tracks.push([latLng(s.activity.start), latLng(s.activity.end)].filter(Boolean));
    }
    const v = latLng(s.visit?.topCandidate?.placeLocation);
    if (v) tracks.push([v]);
  }
  // 원시 위치도 차량 구간 시간에 든 것은 빼고 그 자리에서 끊는다
  let raw = [];
  for (const r of rawSignals) {
    if (inVehicle(timeOf(r.position?.timestamp))) {
      if (raw.length) { tracks.push(raw); raw = []; }
      continue;
    }
    const ll = latLng(r.position?.LatLng ?? r.position?.latLng);
    if (ll) raw.push(ll);
  }
  if (raw.length) tracks.push(raw);
  return { tracks, vehicleSkipped };
}

// "37.5446°, 127.0560°" · "geo:37.5446,127.0560" · { latLng } · { latitudeE7, longitudeE7 } · { latE7, lngE7 }
function latLng(v) {
  if (!v) return null;
  if (typeof v === 'string') {
    const m = v.match(/(-?\d+(?:\.\d+)?)\s*°?\s*,\s*(-?\d+(?:\.\d+)?)/);
    return m ? { lat: parseFloat(m[1]), lng: parseFloat(m[2]) } : null;
  }
  if (typeof v !== 'object') return null;
  if (v.latLng ?? v.LatLng) return latLng(v.latLng ?? v.LatLng);
  if ('latitudeE7' in v) return { lat: e7(v.latitudeE7), lng: e7(v.longitudeE7) };
  if ('latE7' in v) return { lat: e7(v.latE7), lng: e7(v.lngE7) };
  return null;
}

// 예전 Records.json에는 음수가 부호 없는 32비트로 기록된 값이 섞여 있다
function e7(n) {
  return (n > 1800000000 ? n - 4294967296 : n) / 1e7;
}
