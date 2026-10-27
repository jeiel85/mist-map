// 가져온 기록 → 밝힐 지점 목록. 워커(importWorker.js)와 워커를 못 쓰는 브라우저 양쪽에서 쓴다.
// DOM에 기대지 않는다.
import { parseHistory } from './importers.js';

export const SAMPLE_SPACING_M = 24; // 이어진 경로를 이 간격으로 채운다 (main.js 걷기 표본과 같음)
export const IMPORT_GAP_M = 1500;   // 이보다 멀리 떨어진 두 점은 잇지 않는다(기록 공백·장거리 이동)

const mPerDegLat = 110540;
const mPerDegLng = (lat) => 111320 * Math.cos((lat * Math.PI) / 180);
function distM(a, b) {
  const dx = (b.lng - a.lng) * mPerDegLng((a.lat + b.lat) / 2);
  const dy = (b.lat - a.lat) * mPerDegLat;
  return Math.hypot(dx, dy);
}

/**
 * 파일 하나를 읽어 지점을 만든다. 같은 파일 안의 중복은 [keyOf]로 걸러 낸다(이미 밝힌 곳과의 중복은 부르는 쪽이 거른다).
 * @param {string} text
 * @param {(ll: {lng: number, lat: number}) => string} keyOf 중복 판정 격자 키
 * @param {(fraction: number) => void} [onProgress] 0~1
 * @returns {{format: string, points: {lng: number, lat: number}[], distanceM: number,
 *            bounds: [number, number, number, number]}}  bounds = [minLng, minLat, maxLng, maxLat]
 */
export function planImport(text, keyOf, onProgress = () => {}) {
  const parsed = parseHistory(text);
  const points = [];
  const seen = new Set();
  let distanceM = 0;
  let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity;
  const collect = (q) => {
    const key = keyOf(q);
    if (seen.has(key)) return;
    seen.add(key);
    points.push(q);
  };
  const total = parsed.tracks.reduce((n, t) => n + t.length, 0);
  let done = 0;
  for (const track of parsed.tracks) {
    let prev = null;
    for (const p of track) {
      minLng = Math.min(minLng, p.lng); maxLng = Math.max(maxLng, p.lng);
      minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat);
      const d = prev ? distM(prev, p) : Infinity;
      if (d <= IMPORT_GAP_M) {
        distanceM += d;
        const steps = Math.max(1, Math.ceil(d / SAMPLE_SPACING_M));
        for (let i = 1; i <= steps; i++) {
          const t = i / steps;
          collect({ lng: prev.lng + (p.lng - prev.lng) * t, lat: prev.lat + (p.lat - prev.lat) * t });
        }
      } else {
        collect(p);
      }
      prev = p;
      if (++done % 5000 === 0) onProgress(done / total);
    }
  }
  onProgress(1);
  return { format: parsed.format, points, distanceM, bounds: [minLng, minLat, maxLng, maxLat] };
}
