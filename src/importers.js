// 외부 이동 기록 파일 → 경로 목록
//
// parseHistory(text)는 { format, tracks }를 돌려준다.
// tracks는 [{ lng, lat }, ...]의 배열이고, 한 track 안의 점은 시간 순으로 이어진 경로다.
// 점이 하나뿐인 track은 머문 장소(방문지)다.
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
  if (google) return finish('구글 타임라인', google);
  throw new ImportError('알 수 없는 JSON 형식이에요. GeoJSON이나 구글 타임라인 내보내기 파일만 읽을 수 있어요');
}

function finish(format, tracks) {
  const clean = tracks.map((t) => t.filter(valid)).filter((t) => t.length > 0);
  if (clean.length === 0) throw new ImportError(`${format} 파일에 위치 기록이 없어요`);
  return { format, tracks: clean };
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
    const tracks = segmentsToTracks(d.semanticSegments ?? []);
    const raw = (d.rawSignals ?? []).map((s) => latLng(s.position?.LatLng ?? s.position?.latLng)).filter(Boolean);
    if (raw.length) tracks.push(raw);
    return tracks;
  }
  if (Array.isArray(d.locations)) {
    return [d.locations.map(latLng).filter(Boolean)];
  }
  if (Array.isArray(d.timelineObjects)) {
    const tracks = [];
    for (const o of d.timelineObjects) {
      const a = o.activitySegment;
      if (a) {
        const path = a.simplifiedRawPath?.points ?? a.waypointPath?.waypoints ?? [];
        tracks.push([a.startLocation, ...path, a.endLocation].map(latLng).filter(Boolean));
      }
      const v = latLng(o.placeVisit?.location);
      if (v) tracks.push([v]);
    }
    return tracks;
  }
  return null;
}

function isSegmentLike(s) {
  return s && typeof s === 'object' && ('timelinePath' in s || 'visit' in s || 'activity' in s);
}

function segmentsToTracks(segments) {
  const tracks = [];
  for (const s of segments) {
    if (Array.isArray(s.timelinePath) && s.timelinePath.length) {
      tracks.push(s.timelinePath.map((p) => latLng(p.point)).filter(Boolean));
    } else if (s.activity) {
      tracks.push([latLng(s.activity.start), latLng(s.activity.end)].filter(Boolean));
    }
    const v = latLng(s.visit?.topCandidate?.placeLocation);
    if (v) tracks.push([v]);
  }
  return tracks;
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
