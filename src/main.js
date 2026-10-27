import { createMapStyle } from './mapStyle.js';
import { createFogLayer } from './fog.js';
import { ImportError } from './importers.js';
import { planImport } from './importPlan.js';

const maplibregl = window.maplibregl;

// ---- 설정 ----
const REVEAL_RADIUS_M = 55;      // 한 지점이 밝히는 반경(블롭 반지름)
const SAMPLE_SPACING_M = 24;     // 이동 중 새 지점을 찍는 간격
const CELL_M = 15;               // 면적 계산용 격자 한 변
const WALK_SPEED = 28;           // m/s (게임 속도)
const RUN_SPEED = 75;
const STROLL_SPEED = 22;
const START = { lng: 127.0560, lat: 37.5446 }; // 성수동
const STORAGE_KEY_V1 = 'mistmap:v1';
const STORAGE_KEY = 'mistmap:v2'; // 지점마다 출처(걸음 0, 가져온 파일 n)를 함께 담는 압축 형식
const DEDUP_M = 18;              // 이 격자 칸에 이미 지점이 있으면 새로 찍지 않는다(적도 기준 m)
const IMPORT_MAX_POINTS = 100000; // 한 번에 가져올 새 지점 상한 (localStorage 용량 보호)
const GEO_MAX_ACCURACY_M = 35;   // 이보다 부정확한 위치는 따라가지 않는다 (앱의 LocationFilter와 같음)
const MIN_BLOB_PX = 3;           // 크게 축소해도 밝힌 곳이 점으로라도 보이게
const MERGE_BLOB_PX = 10;        // 블롭이 이보다 작게 그려지면 가까운 것끼리 하나로 묶어 개수를 줄인다
const DEEP_IDLE_AFTER_S = 20;    // 이만큼 아무 일이 없으면 더 드물게 그린다
const DEEP_IDLE_FPS = 4;
const EARTH_C = 40075016.686;
const CELL_LEVEL = 21;           // 앱이 넘기는 칸: 웹 메르카토르 쿼드트리 21단계 (DECISIONS D2)
const IDLE_FPS = 15;             // 움직임·걷힘 연출이 없을 때 안개를 다시 그리는 빈도

// ?app: Android 앱의 WebView 안에서 렌더러로만 동작한다. 기록·저장·HUD는 앱이 맡는다.
const APP = new URLSearchParams(location.search).has('app');
if (APP) document.body.classList.add('app');

// ---- 좌표 유틸 ----
const mercX = (lng) => (lng + 180) / 360;
const mercY = (lat) => {
  const s = Math.sin((lat * Math.PI) / 180);
  return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
};
const mPerDegLat = 110540;
const mPerDegLng = (lat) => 111320 * Math.cos((lat * Math.PI) / 180);
function distM(a, b) {
  const dx = (b.lng - a.lng) * mPerDegLng((a.lat + b.lat) / 2);
  const dy = (b.lat - a.lat) * mPerDegLat;
  return Math.hypot(dx, dy);
}
function offset(p, eastM, northM) {
  return { lng: p.lng + eastM / mPerDegLng(p.lat), lat: p.lat + northM / mPerDegLat };
}

// ---- 상태 ----
const state = {
  origin: { ...START },
  points: [],         // { lng, lat, mx, my, born, src } src: 0=걸음, n=가져온 파일 id
  imports: [],        // { id, label, at } 가져온 파일 (되돌리기용)
  cells: new Set(),
  walkedM: 0,
  player: { ...START },
  facing: 1,
  target: null,       // 클릭 목적지
  stroll: false,
  strollHeading: Math.random() * Math.PI * 2,
  follow: true,
  geoWatch: null,
};
const keys = new Set();
const now = () => performance.now() / 1000;

// ---- 저장 ----
// v2: { v: 2, origin, player, walkedM, imports, pts: [lng×1e5, lat×1e5, src, …] } — v1보다 약 40% 작다
// v1: { origin, player, walkedM, points: [[lng, lat], …] } — 읽기만 하고 다음 저장 때 v2로 옮긴다
function loadSave() {
  if (APP) return null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY) ?? localStorage.getItem(STORAGE_KEY_V1);
    if (!raw) return null;
    const data = JSON.parse(raw);
    const points = [];
    if (data.v === 2) {
      for (let i = 0; i + 2 < data.pts.length; i += 3) points.push({ lng: data.pts[i] / 1e5, lat: data.pts[i + 1] / 1e5, src: data.pts[i + 2] });
    } else {
      for (const [lng, lat] of data.points ?? []) points.push({ lng, lat, src: 0 });
    }
    return { ...data, points, imports: data.imports ?? [] };
  } catch (e) {
    console.warn('저장 데이터를 읽지 못했습니다', e);
    return null;
  }
}
let saveTimer = 0;
let storageWarned = false;
const STORAGE_WARN_CHARS = 4_000_000;
function scheduleSave() {
  if (APP) return;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const pts = new Array(state.points.length * 3);
      state.points.forEach((p, i) => {
        pts[i * 3] = Math.round(p.lng * 1e5);
        pts[i * 3 + 1] = Math.round(p.lat * 1e5);
        pts[i * 3 + 2] = p.src;
      });
      const json = JSON.stringify({
        v: 2, origin: state.origin, player: state.player, walkedM: state.walkedM, imports: state.imports, pts,
      });
      localStorage.setItem(STORAGE_KEY, json);
      localStorage.removeItem(STORAGE_KEY_V1);
      // 브라우저 저장소는 보통 5MB. 다 차기 전에 한 번 알린다 (앱은 기기 DB라 해당 없음)
      if (json.length > STORAGE_WARN_CHARS && !storageWarned) {
        storageWarned = true;
        toast('브라우저 저장 공간이 거의 찼어요. 오래된 가져온 기록을 되돌리면 공간이 생겨요');
      }
    } catch (e) {
      console.warn('저장하지 못했습니다', e);
      toast('브라우저 저장소를 쓸 수 없어 기록이 저장되지 않아요');
    }
  }, 800);
}

// ---- 밝히기 ----
function markCells(p) {
  const r = REVEAL_RADIUS_M * 0.83; // 단일 블롭의 경계 반경
  const cx = (p.lng - state.origin.lng) * mPerDegLng(state.origin.lat);
  const cy = (p.lat - state.origin.lat) * mPerDegLat;
  const n = Math.ceil(r / CELL_M);
  const ix0 = Math.round(cx / CELL_M), iy0 = Math.round(cy / CELL_M);
  for (let ix = ix0 - n; ix <= ix0 + n; ix++) {
    for (let iy = iy0 - n; iy <= iy0 + n; iy++) {
      if (Math.hypot(ix * CELL_M - cx, iy * CELL_M - cy) <= r) state.cells.add(`${ix},${iy}`);
    }
  }
}

const occupied = new Set();

// 화면 안 지점만 빨리 찾도록 메르카토르 14단계 타일(서울 약 1.9km)로 묶어 둔다.
// 칸이 수십만 개여도 한 프레임에 화면 근처 타일만 본다 (이슈 #4)
const INDEX_LEVEL = 2 ** 14;
const index = new Map();
const tileKey = (tx, ty) => tx * INDEX_LEVEL + ty;
function indexAdd(p) {
  const key = tileKey(Math.floor(p.mx * INDEX_LEVEL), Math.floor(p.my * INDEX_LEVEL));
  let list = index.get(key);
  if (!list) index.set(key, (list = []));
  list.push(p);
}
function rebuildIndex() {
  index.clear();
  for (const p of state.points) indexAdd(p);
}
const dedupKey = (ll) => {
  const k = EARTH_C / DEDUP_M;
  return `${Math.floor(mercX(ll.lng) * k)},${Math.floor(mercY(ll.lat) * k)}`;
};

let revealCount = 0;
let activeUntil = 0; // 이 시각까지는 매 프레임 다시 그린다 (걷힘 스프링·이동)
function reveal(ll, born = now(), src = 0) {
  const p = { lng: ll.lng, lat: ll.lat, mx: mercX(ll.lng), my: mercY(ll.lat), born, src };
  activeUntil = Math.max(activeUntil, born + 2.5);
  state.points.push(p);
  indexAdd(p);
  occupied.add(dedupKey(p));
  markCells(p);
  if (revealCount++ % 3 === 0 && Math.abs(born - now()) < 0.1) spawnPuffs(ll);
  return p;
}

// 이미 밝힌 길을 다시 걸을 때는 지점을 쌓지 않는다
let lastSample = null;
function maybeReveal() {
  if (lastSample && distM(lastSample, state.player) < SAMPLE_SPACING_M) return;
  lastSample = { ...state.player };
  if (occupied.has(dedupKey(state.player))) return;
  reveal(state.player);
  scheduleSave();
}

// ---- 기록 가져오기 ----
// 파일 읽기·경로 채우기는 워커에서 한다(몇 년 치 타임라인도 화면이 멈추지 않게). 워커를 못 쓰면 이 스레드에서.
let importWorker = null;
try {
  importWorker = new Worker(new URL('./importWorker.js', import.meta.url), { type: 'module' });
} catch (e) {
  console.warn('가져오기 워커를 만들지 못해 화면 스레드에서 읽습니다', e);
}
let importSeq = 0;

function planInWorker(file, onProgress) {
  if (!importWorker) {
    return file.text().then((text) => planImport(text, dedupKey, onProgress));
  }
  const id = ++importSeq;
  return new Promise((resolve, reject) => {
    const onMessage = ({ data }) => {
      if (data.id !== id) return;
      if (data.type === 'progress') { onProgress(data.fraction); return; }
      importWorker.removeEventListener('message', onMessage);
      if (data.type === 'done') resolve(data.result);
      else reject(data.importError ? new ImportError(data.message) : new Error(data.message));
    };
    importWorker.addEventListener('message', onMessage);
    importWorker.postMessage({ id, file, dedupM: DEDUP_M });
  });
}

async function importFiles(files) {
  let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity;
  const results = [];
  for (const [n, file] of files.entries()) {
    const prefix = files.length > 1 ? `${n + 1}/${files.length} ` : '';
    try {
      toast(`${prefix}${file.name} 읽는 중…`);
      const plan = await planInWorker(file, (f) => toast(`${prefix}${file.name} 읽는 중… ${Math.round(f * 100)}%`));
      results.push({ file, plan });
      [minLng, minLat] = [Math.min(minLng, plan.bounds[0]), Math.min(minLat, plan.bounds[1])];
      [maxLng, maxLat] = [Math.max(maxLng, plan.bounds[2]), Math.max(maxLat, plan.bounds[3])];
    } catch (e) {
      if (!(e instanceof ImportError)) console.error(e);
      toast(e instanceof ImportError ? `${file.name}: ${e.message}` : `${file.name}을(를) 읽다가 문제가 생겼어요`);
    }
  }
  if (results.length === 0) return;

  setStroll(false);
  state.follow = false;
  map.fitBounds([[minLng, minLat], [maxLng, maxLat]], { padding: 90, maxZoom: 16.2, duration: 1400 });

  // 카메라가 도착할 즈음부터 기록 순서대로 안개가 쓸려 나간다. 파일 하나가 되돌리기 단위다
  const areaBefore = state.cells.size;
  const t0 = now() + 1.2;
  let added = 0, truncated = false;
  const formats = new Set();
  for (const { file, plan } of results) {
    formats.add(plan.format);
    let fresh = plan.points.filter((q) => !occupied.has(dedupKey(q)));
    if (fresh.length > IMPORT_MAX_POINTS) { fresh = fresh.slice(0, IMPORT_MAX_POINTS); truncated = true; }
    if (fresh.length === 0) continue;
    const id = Math.max(0, ...state.imports.map((i) => i.id)) + 1;
    state.imports.push({ id, label: `${plan.format} · ${file.name}`, at: Date.now() });
    fresh.forEach((q, i) => reveal(q, t0 + (2.6 * i) / fresh.length, id));
    added += fresh.length;
    state.walkedM += plan.distanceM;
  }
  scheduleSave();
  renderImports();

  const label = [...formats].join(' · ');
  if (added === 0) {
    toast(`${label} 기록의 장소는 이미 모두 밝혀져 있어요`);
  } else {
    const km2 = ((state.cells.size - areaBefore) * CELL_M * CELL_M) / 1e6;
    toast(`${label}에서 ${added.toLocaleString('ko-KR')}곳을 새로 밝혔어요 · +${km2.toFixed(2)} km²`
      + (truncated ? ` (너무 커서 파일마다 ${IMPORT_MAX_POINTS.toLocaleString('ko-KR')}곳까지만)` : ''));
  }
}

/** 가져온 파일 하나를 되돌린다. 그 파일이 새로 밝힌 지점만 사라진다(걸어서 밝힌 곳·먼저 밝힌 곳은 남음). */
function undoImport(id) {
  const item = state.imports.find((i) => i.id === id);
  if (!item) return;
  state.points = state.points.filter((p) => p.src !== id);
  state.imports = state.imports.filter((i) => i.id !== id);
  occupied.clear();
  state.cells.clear();
  for (const p of state.points) { occupied.add(dedupKey(p)); markCells(p); }
  rebuildIndex();
  activeUntil = now() + 0.5;
  scheduleSave();
  renderImports();
  toast(`${item.label} 기록을 되돌렸어요`);
}

// 반경 스프링: 살짝 넘쳤다가 자리 잡는다
function growth(age) {
  if (age <= 0) return 0;
  if (age > 2.5) return 1;
  return 1 - Math.exp(-5 * age) * Math.cos(8 * age);
}

// ---- 지도 ----
const save = loadSave();
if (save?.points?.length) {
  state.origin = save.origin ?? START;
  state.player = save.player ?? START;
  state.walkedM = save.walkedM ?? 0;
}

const map = new maplibregl.Map({
  container: 'map',
  style: createMapStyle(),
  center: [state.player.lng, state.player.lat],
  zoom: 16.2,
  minZoom: 4, // 여러 도시에 걸친 기록도 한눈에 (블롭은 frameForFog에서 묶고 최소 크기를 둔다)
  maxZoom: 18.5,
  pitchWithRotate: false,
  dragRotate: false,
  touchPitch: false,
  attributionControl: false,
});
map.touchZoomRotate.disableRotation();
map.keyboard.disable();
map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');

let blobBuf = new Float32Array(1024 * 3);
function frameForFog() {
  const canvas = map.getCanvas();
  const w = canvas.clientWidth, h = canvas.clientHeight;
  const c = map.getCenter();
  // 패딩(앱 HUD)이 있으면 지도 중심은 캔버스 한가운데가 아니라 패딩을 뺀 영역의 가운데에 그려진다
  const cp = map.project(c);
  const cmx = mercX(c.lng), cmy = mercY(c.lat);
  const world = 512 * Math.pow(2, map.getZoom());
  const ppm = world / (EARTH_C * Math.cos((c.lat * Math.PI) / 180));
  const t = now();
  const rBase = Math.max(REVEAL_RADIUS_M * ppm, MIN_BLOB_PX);
  const margin = rBase * 3.6;
  // 크게 축소하면 블롭 하나가 몇 px뿐이라 화면 안 지점 수만큼 그리면 느리다: 같은 작은 칸에 든 것은 하나만 그린다
  const merge = rBase < MERGE_BLOB_PX ? new Set() : null;
  const bucket = rBase * 0.6;

  if (blobBuf.length < state.points.length * 3) blobBuf = new Float32Array(state.points.length * 6);
  // 화면(+여유)에 드는 메르카토르 범위의 타일만 본다. 크게 축소해 타일이 지점 묶음보다 많으면 묶음을 훑는다
  const mx0 = cmx + (-margin - cp.x) / world, mx1 = cmx + (w + margin - cp.x) / world;
  const my0 = cmy + (-margin - cp.y) / world, my1 = cmy + (h + margin - cp.y) / world;
  const tx0 = Math.floor(mx0 * INDEX_LEVEL), tx1 = Math.floor(mx1 * INDEX_LEVEL);
  const ty0 = Math.floor(my0 * INDEX_LEVEL), ty1 = Math.floor(my1 * INDEX_LEVEL);
  const lists = [];
  if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) <= index.size) {
    for (let tx = tx0; tx <= tx1; tx++) for (let ty = ty0; ty <= ty1; ty++) {
      const list = index.get(tileKey(tx, ty));
      if (list) lists.push(list);
    }
  } else {
    lists.push(...index.values());
  }
  let n = 0;
  for (const list of lists) for (const p of list) {
    const x = (p.mx - cmx) * world + cp.x;
    const y = (p.my - cmy) * world + cp.y;
    if (x < -margin || y < -margin || x > w + margin || y > h + margin) continue;
    if (merge) {
      const key = Math.floor(x / bucket) * 100003 + Math.floor(y / bucket);
      if (merge.has(key)) continue;
      merge.add(key);
    }
    const r = rBase * growth(t - p.born);
    if (r <= 0.01) continue;
    blobBuf[n * 3] = x; blobBuf[n * 3 + 1] = y; blobBuf[n * 3 + 2] = r;
    n++;
  }
  return {
    blobs: blobBuf,
    count: n,
    origin: [(mercX(state.origin.lng) - cmx) * world + cp.x, (mercY(state.origin.lat) - cmy) * world + cp.y],
    ppm,
    time: t,
  };
}

// ---- 캐릭터 · 핀 · 안개 조각 ----
const heroEl = document.createElement('div');
heroEl.className = 'hero';
// 반딧불 '반디': 꼬리 등불로 밤안개를 밝힌다
heroEl.innerHTML = `
  <div class="hero-light"></div>
  <div class="hero-body">
    <span class="wing l"></span><span class="wing r"></span>
    <span class="antenna l"></span><span class="antenna r"></span>
    <div class="hero-lamp"></div>
    <div class="hero-shell"></div>
    <div class="hero-face">
      <span class="eye"></span><span class="eye"></span>
      <span class="blush"></span><span class="blush"></span>
      <span class="mouth"></span>
    </div>
  </div>`;
const hero = new maplibregl.Marker({ element: heroEl, anchor: 'bottom' })
  .setLngLat([state.player.lng, state.player.lat]).addTo(map);
if (APP) heroEl.hidden = true; // 첫 위치를 받기 전에는 숨긴다

const pinEl = document.createElement('div');
pinEl.className = 'pin';
pinEl.innerHTML = '<div class="pin-head"></div><div class="pin-dot"></div>';
const pin = new maplibregl.Marker({ element: pinEl, anchor: 'bottom' });

function spawnPuffs(ll) {
  // 반딧불 불티가 위로 흩날린다
  for (let i = 0; i < 5; i++) {
    // 마커 엘리먼트의 transform은 MapLibre가 위치용으로 쓰므로 애니메이션은 안쪽 요소에 건다
    const wrap = document.createElement('div');
    const el = document.createElement('div');
    el.className = 'puff';
    wrap.appendChild(el);
    el.style.setProperty('--dx', `${(Math.random() - 0.5) * 70}px`);
    el.style.setProperty('--dy', `${-30 - Math.random() * 50}px`);
    el.style.setProperty('--s', (0.6 + Math.random() * 0.7).toFixed(2));
    el.style.animationDelay = `${i * 90}ms`;
    const m = new maplibregl.Marker({ element: wrap }).setLngLat([ll.lng, ll.lat]).addTo(map);
    el.addEventListener('animationend', () => m.remove(), { once: true });
  }
}

// ---- 이동 ----
function moveBy(eastM, northM) {
  const next = offset(state.player, eastM, northM);
  state.walkedM += Math.hypot(eastM, northM);
  if (Math.abs(eastM) > 0.01) state.facing = eastM > 0 ? 1 : -1;
  state.player = next;
}

function update(dt) {
  let vx = 0, vy = 0;
  if (APP) keys.clear();
  if (keys.has('ArrowLeft') || keys.has('KeyA')) vx -= 1;
  if (keys.has('ArrowRight') || keys.has('KeyD')) vx += 1;
  if (keys.has('ArrowUp') || keys.has('KeyW')) vy += 1;
  if (keys.has('ArrowDown') || keys.has('KeyS')) vy -= 1;

  let moving = false;
  if (vx || vy) {
    const len = Math.hypot(vx, vy);
    const speed = keys.has('ShiftLeft') || keys.has('ShiftRight') ? RUN_SPEED : WALK_SPEED;
    moveBy((vx / len) * speed * dt, (vy / len) * speed * dt);
    state.target = null; pin.remove();
    moving = true;
  } else if (state.target) {
    const d = distM(state.player, state.target);
    // 앱에서는 다음 위치가 올 즈음(약 5초 뒤) 도착하도록 실제 걸음처럼 미끄러진다
    const speed = APP ? Math.max(1.5, state.target.dist / 4.5) : Math.max(WALK_SPEED, state.target.dist / 3.5);
    if (d <= speed * dt) {
      moveBy((state.target.lng - state.player.lng) * mPerDegLng(state.player.lat),
             (state.target.lat - state.player.lat) * mPerDegLat);
      state.target = null;
      pin.remove();
    } else {
      const ex = (state.target.lng - state.player.lng) * mPerDegLng(state.player.lat);
      const ny = (state.target.lat - state.player.lat) * mPerDegLat;
      moveBy((ex / d) * speed * dt, (ny / d) * speed * dt);
    }
    moving = true;
  } else if (state.stroll) {
    // 느린 사인 합으로 방향을 틀어 골목을 누비듯 크게 휘어 걷는다
    const t = now();
    state.strollHeading += (Math.sin(t * 0.37) * 0.55 + Math.sin(t * 0.13 + 1.7) * 0.45 + (Math.random() - 0.5) * 0.4) * dt;
    moveBy(Math.cos(state.strollHeading) * STROLL_SPEED * dt, Math.sin(state.strollHeading) * STROLL_SPEED * dt);
    moving = true;
  }

  heroEl.classList.toggle('walking', moving);
  heroEl.style.setProperty('--face', state.facing);
  hero.setLngLat([state.player.lng, state.player.lat]);
  if (moving) {
    if (!APP) maybeReveal();
    if (state.follow) map.jumpTo({ center: [state.player.lng, state.player.lat] });
  }
  return moving;
}

// ---- HUD ----
const $ = (id) => document.getElementById(id);
function updateHud() {
  const km2 = (state.cells.size * CELL_M * CELL_M) / 1e6;
  $('stat-area').textContent = km2 < 0.1 ? km2.toFixed(3) : km2.toFixed(2);
  $('stat-walk').textContent = (state.walkedM / 1000).toFixed(2);
  $('stat-spots').textContent = state.points.length.toLocaleString('ko-KR');
  $('btn-stroll').classList.toggle('on', state.stroll);
  $('btn-follow').hidden = state.follow;
}

let toastTimer = 0;
function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

function setStroll(on) {
  state.stroll = on;
  if (on) { state.target = null; pin.remove(); state.follow = true; stopGeo(); }
}

function stopGeo() {
  if (state.geoWatch !== null) {
    navigator.geolocation.clearWatch(state.geoWatch);
    state.geoWatch = null;
    $('btn-locate').classList.remove('on');
  }
}

function startGeo() {
  if (!('geolocation' in navigator)) { toast('이 브라우저는 위치 기능을 지원하지 않아요'); return; }
  setStroll(false);
  $('btn-locate').classList.add('on');
  state.geoWatch = navigator.geolocation.watchPosition(
    (pos) => {
      // 실내·건물 사이에서 튀는 부정확한 위치는 따라가지 않는다
      if (pos.coords.accuracy > GEO_MAX_ACCURACY_M) return;
      const ll = { lng: pos.coords.longitude, lat: pos.coords.latitude };
      const first = !state.target && distM(state.player, ll) > 2000;
      state.target = { ...ll, dist: distM(state.player, ll) };
      state.follow = true;
      if (first) toast('내 위치로 날아가는 중…');
    },
    (err) => {
      stopGeo();
      toast(err.code === err.PERMISSION_DENIED ? '위치 권한이 거부되었어요' : '위치를 가져오지 못했어요');
    },
    { enableHighAccuracy: true, maximumAge: 5000 },
  );
}

$('btn-stroll').addEventListener('click', () => setStroll(!state.stroll));
$('btn-follow').addEventListener('click', () => {
  state.follow = true;
  map.easeTo({ center: [state.player.lng, state.player.lat], duration: 600 });
});
$('btn-locate').addEventListener('click', () => (state.geoWatch === null ? startGeo() : stopGeo()));
$('btn-reset').addEventListener('click', () => {
  if (!confirm('지금까지 밝힌 지도를 모두 지우고 다시 안개로 덮을까요?')) return;
  state.points = [];
  state.imports = [];
  state.cells.clear();
  occupied.clear();
  index.clear();
  lastSample = null;
  state.walkedM = 0;
  renderImports();
  state.origin = { ...state.player };
  reveal(state.player);
  scheduleSave();
  toast('다시 안개 속으로…');
});

// 가져오기 시트 · 드래그 앤 드롭
const sheet = $('import-sheet');
const openSheet = () => { sheet.hidden = false; keys.clear(); renderImports(); $('import-drop').focus(); };

function renderImports() {
  const list = $('import-list');
  list.replaceChildren(...state.imports.slice().reverse().map((item) => {
    const li = document.createElement('li');
    const count = state.points.reduce((n, p) => n + (p.src === item.id ? 1 : 0), 0);
    const when = new Date(item.at).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' });
    const text = document.createElement('span');
    text.textContent = `${item.label} · ${when} · ${count.toLocaleString('ko-KR')}곳`;
    const undo = document.createElement('button');
    undo.type = 'button';
    undo.textContent = '되돌리기';
    undo.addEventListener('click', () => {
      if (confirm(`${item.label}에서 밝힌 곳을 다시 안개로 덮을까요? 걸어서 밝힌 곳은 그대로 남아요.`)) undoImport(item.id);
    });
    li.append(text, undo);
    return li;
  }));
  $('import-history').hidden = state.imports.length === 0;
}
const closeSheet = () => { sheet.hidden = true; };
$('btn-import').addEventListener('click', openSheet);
$('import-close').addEventListener('click', closeSheet);
sheet.addEventListener('click', (e) => { if (e.target === sheet) closeSheet(); });
$('import-input').addEventListener('change', (e) => {
  const files = [...e.target.files];
  e.target.value = '';
  if (files.length) { closeSheet(); importFiles(files); }
});

const hasFiles = (e) => [...(e.dataTransfer?.types ?? [])].includes('Files');
let dragDepth = 0;
window.addEventListener('dragenter', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  dragDepth++;
  document.body.classList.add('dragging');
});
window.addEventListener('dragover', (e) => { if (hasFiles(e)) e.preventDefault(); });
window.addEventListener('dragleave', () => {
  if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('dragging'); }
});
window.addEventListener('drop', (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  dragDepth = 0;
  document.body.classList.remove('dragging');
  closeSheet();
  importFiles([...e.dataTransfer.files]);
});

window.addEventListener('keydown', (e) => {
  if (APP || e.target instanceof HTMLInputElement) return;
  if (!sheet.hidden) {
    if (e.code === 'Escape') closeSheet();
    return;
  }
  if (e.code.startsWith('Arrow') || ['KeyW', 'KeyA', 'KeyS', 'KeyD'].includes(e.code)) {
    e.preventDefault();
    if (state.stroll) setStroll(false);
    state.follow = true;
  }
  keys.add(e.code);
});
window.addEventListener('keyup', (e) => keys.delete(e.code));
window.addEventListener('blur', () => keys.clear());

map.on('dragstart', () => { state.follow = false; });
map.on('click', (e) => {
  if (APP) return;
  setStroll(false);
  stopGeo();
  const ll = { lng: e.lngLat.lng, lat: e.lngLat.lat };
  state.target = { ...ll, dist: distM(state.player, ll) };
  state.follow = true;
  pin.setLngLat([ll.lng, ll.lat]).addTo(map);
  pinEl.classList.remove('drop'); void pinEl.offsetWidth; pinEl.classList.add('drop');
});

// ---- 앱 브리지 ----
// 앱은 페이지가 뜨면 'mistmap:port' 메시지와 함께 MessagePort 하나를 보내고, 이후 모든 메시지는 그 포트로 오간다.
//   앱 → 웹: 칸 묶음 ArrayBuffer (WebView가 지원하지 않으면 같은 바이트를 {type:'cells', b64})
//            [u8 종류(1=전체, 2=추가), 3바이트 비움, (u32 x, u32 y) 리틀 엔디언 반복]
//            {type:'player', lng, lat}, {type:'insets', top, bottom}, {type:'fit', south, west, north, east}
//   웹 → 앱: {type:'ready'}, {type:'error', message}
const CELLS_SET = 1, CELLS_ADD = 2;
const CELL_N = 2 ** CELL_LEVEL;
function cellCenter(x, y) {
  const mx = (x + 0.5) / CELL_N, my = (y + 0.5) / CELL_N;
  return { lng: mx * 360 - 180, lat: (Math.atan(Math.sinh(Math.PI * (1 - 2 * my))) * 180) / Math.PI };
}

let hasFix = false;
let hasOrigin = false;
function setOriginOnce(ll) {
  // 종이 무늬·등고선의 기준점. 사용자 근처에 두어야 셰이더 좌표가 커지지 않는다
  if (hasOrigin) return;
  hasOrigin = true;
  state.origin = { ...ll };
}

function applyCells(buf) {
  const view = new DataView(buf);
  const kind = view.getUint8(0);
  const count = (buf.byteLength - 4) >> 3;
  if (kind === CELLS_SET) {
    state.points = [];
    state.cells.clear();
    occupied.clear();
    index.clear();
  } else if (kind !== CELLS_ADD) {
    console.warn('알 수 없는 칸 메시지', kind);
    return;
  }
  const t0 = now();
  for (let i = 0; i < count; i++) {
    const ll = cellCenter(view.getUint32(4 + i * 8, true), view.getUint32(8 + i * 8, true));
    setOriginOnce(ll);
    if (kind === CELLS_SET) {
      // 처음 열 때는 캐릭터에서 가까운 칸부터 차례로 걷힌다
      reveal(ll, t0 + 0.4 + Math.min(distM(state.player, ll) / 1500, 1.6));
    } else {
      reveal(ll);
    }
  }
}

function movePlayer({ lng, lat }) {
  const ll = { lng, lat };
  setOriginOnce(ll);
  if (!hasFix || distM(state.player, ll) > 300) {
    hasFix = true;
    heroEl.hidden = false;
    state.player = ll;
    state.target = null;
    hero.setLngLat([lng, lat]);
    if (state.follow) map.jumpTo({ center: [lng, lat] });
    return;
  }
  state.target = { ...ll, dist: distM(state.player, ll) };
}

// 앱 HUD가 가리는 위·아래 높이(CSS px). 캐릭터는 보이는 영역 가운데에 오고, 버튼·안내는 HUD를 피한다
function setInsets({ top = 0, bottom = 0 }) {
  document.body.style.setProperty('--app-top', `${top}px`);
  document.body.style.setProperty('--app-bottom', `${bottom}px`);
  map.setPadding({ top, bottom, left: 0, right: 0 });
}

// 가져온 기록이 한눈에 들어오게 카메라를 옮긴다 (웹의 가져오기와 같은 연출)
function fitTo({ south, west, north, east }) {
  state.follow = false;
  map.fitBounds([[west, south], [east, north]], { padding: 60, maxZoom: 16.2, duration: 1400 });
}

function base64ToBuffer(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

const native = (() => {
  let port = null;
  let ready = false;
  const outbox = [];
  const inbox = []; // 지도가 준비되기 전에 온 메시지

  function send(msg) {
    if (!APP) return;
    const text = JSON.stringify(msg);
    if (port) port.postMessage(text); else outbox.push(text);
  }
  function handle(data) {
    if (!ready) { inbox.push(data); return; }
    try {
      if (data instanceof ArrayBuffer) { applyCells(data); return; }
      const msg = JSON.parse(data);
      if (msg.type === 'player') movePlayer(msg);
      else if (msg.type === 'insets') setInsets(msg);
      else if (msg.type === 'fit') fitTo(msg);
      else if (msg.type === 'cells') applyCells(base64ToBuffer(msg.b64));
      else console.warn('알 수 없는 앱 메시지', msg.type);
    } catch (e) {
      console.error(e);
      send({ type: 'error', message: `메시지 처리 실패: ${e.message}` });
    }
  }

  if (APP) {
    window.addEventListener('message', (e) => {
      if (port || e.data !== 'mistmap:port' || !e.ports?.[0]) return;
      port = e.ports[0];
      port.onmessage = (m) => handle(m.data);
      outbox.splice(0).forEach((text) => port.postMessage(text));
    });
    // 지도 라이브러리를 못 받는 등(오프라인) 시작 단계의 실패도 앱이 알 수 있게 한다
    window.addEventListener('error', (e) => send({ type: 'error', message: e.message }));
  }

  return {
    send,
    markReady() {
      ready = true;
      send({ type: 'ready' });
      inbox.splice(0).forEach(handle);
    },
  };
})();

// ---- 시작 ----
map.on('load', () => {
  try {
    map.addLayer(createFogLayer(frameForFog));
  } catch (e) {
    console.error(e);
    $('fatal').textContent = e.message;
    $('fatal').hidden = false;
    native.send({ type: 'error', message: e.message });
    return;
  }
  // GPU가 컨텍스트를 잃었다 되찾으면(탭 전환·GPU 리셋) MapLibre는 스스로 복구하지만
  // 우리 안개 레이어의 셰이더·버퍼는 옛 컨텍스트와 함께 사라지므로 새로 붙인다
  map.on('webglcontextrestored', () => {
    if (map.getLayer('mist')) map.removeLayer('mist');
    map.addLayer(createFogLayer(frameForFog));
    activeUntil = now() + 1;
  });

  const t0 = now();
  if (APP) {
    // 칸과 위치는 앱이 보내 준다
  } else if (save?.points?.length) {
    // 저장된 지점은 캐릭터에서 가까운 순으로 차례차례 걷힌다 (밝힐 때 이미 중복을 걸렀으므로 그대로 싣는다)
    state.imports = save.imports;
    for (const q of save.points) reveal(q, t0 + 0.4 + Math.min(distM(state.player, q) / 1500, 1.6), q.src);
    if (save.v !== 2) scheduleSave();
    toast(`다시 오셨네요! ${state.points.length.toLocaleString('ko-KR')}곳의 기억을 불러왔어요`);
  } else {
    reveal(state.player, t0 + 0.5);
    toast('방향키나 WASD로 걸어 보세요. 지도를 누르면 그곳까지 걸어가요');
  }
  document.body.classList.add('ready');

  native.markReady();

  // 걷는 중이거나 걷힘 연출이 남아 있으면 매 프레임, 아니면 안개가 천천히 일렁이는 정도로만 다시 그린다.
  // 그마저 한동안 아무 일이 없으면 별 반짝임만 남기고 더 드물게 그린다(배터리)
  let last = now(), lastPaint = 0, lastBusy = now();
  map.on('move', () => { lastBusy = now(); });
  const tick = () => {
    const t = now();
    if (update(Math.min(t - last, 0.05))) activeUntil = Math.max(activeUntil, t + 0.3);
    last = t;
    if (t < activeUntil) lastBusy = t;
    const idleFps = t - lastBusy > DEEP_IDLE_AFTER_S ? DEEP_IDLE_FPS : IDLE_FPS;
    if (t < activeUntil || t - lastPaint >= 1 / idleFps) {
      updateHud();
      map.triggerRepaint();
      lastPaint = t;
    }
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
});

// ?debug: 개발 중 콘솔에서 시뮬레이션을 직접 돌릴 수 있게 노출
if (new URLSearchParams(location.search).has('debug')) window.__mist = { state, update, keys, map, importFiles, undoImport, frameForFog };

// 지도 타일을 계속 못 받으면(오프라인·OpenFreeMap 장애) 빈 지도만 보이지 않게 알린다. 밤안개와 밝힌 곳은 그대로 그린다
let tileErrors = [];
let tileToastAt = -Infinity;
map.on('error', (e) => {
  console.warn('지도 오류', e.error ?? e);
  if (!e.sourceId && !e.tile) return;
  const t = now();
  tileErrors = tileErrors.filter((x) => t - x < 10).concat(t);
  if (tileErrors.length >= 3 && t - tileToastAt > 60) {
    tileToastAt = t;
    toast('지도를 불러오지 못하고 있어요. 인터넷 연결을 확인해 주세요');
  }
});
