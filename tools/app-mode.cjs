// 앱 모드(?app) 브리지 점검: 앱 대신 MessagePort로 위치와 칸을 보내고 스크린샷을 찍는다.
// 끊긴 곳 표시(mark)도 보내 카메라가 옮겨지고 표시가 떴다가 지워지는지 본다.
// node tools/app-mode.cjs [URL] [출력 PNG]   (기본: 로컬 서버, docs/ 밖의 임시 파일)
const os = require('node:os');
const path = require('node:path');
const { launch } = require('./playwright.cjs');

const URL = process.argv[2] ?? 'http://localhost:5174/';
const OUT = process.argv[3] ?? path.join(os.tmpdir(), 'mist-app-mode.png');

(async () => {
  const browser = await launch();
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(`${URL}?app&debug`, { waitUntil: 'networkidle' });

  const result = await page.evaluate(async () => {
    const N = 2 ** 21;
    const cellOf = (lng, lat) => {
      const s = Math.sin((lat * Math.PI) / 180);
      const my = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
      return [Math.floor(((lng + 180) / 360) * N), Math.floor(my * N)];
    };
    const pack = (kind, cells) => {
      const buf = new ArrayBuffer(4 + cells.length * 8);
      const v = new DataView(buf);
      v.setUint8(0, kind);
      cells.forEach(([x, y], i) => { v.setUint32(4 + i * 8, x, true); v.setUint32(8 + i * 8, y, true); });
      return buf;
    };
    // 성수동에서 동쪽으로 300m, 북쪽으로 200m 걷은 길 (24m 간격 표본의 칸)
    const start = { lng: 127.0560, lat: 37.5446 };
    const path = [];
    for (let i = 0; i <= 12; i++) path.push({ lng: start.lng + (i * 24) / 88300, lat: start.lat });
    for (let i = 1; i <= 8; i++) path.push({ lng: path[12].lng, lat: start.lat + (i * 24) / 110540 });
    const cells = [...new Map(path.map((p) => { const c = cellOf(p.lng, p.lat); return [c.join(), c]; })).values()];

    const received = [];
    const ch = new MessageChannel();
    ch.port1.onmessage = (e) => received.push(JSON.parse(e.data));
    window.postMessage('mistmap:port', '*', [ch.port2]);
    // 앱 HUD처럼 위·아래 높이가 다른 패딩: 지도 중심이 캔버스 가운데에서 벗어난다
    ch.port1.postMessage(JSON.stringify({ type: 'insets', top: 24, bottom: 160 }));
    ch.port1.postMessage(JSON.stringify({ type: 'player', lng: path.at(-1).lng, lat: path.at(-1).lat }));
    ch.port1.postMessage(pack(1, cells.slice(0, -3)));
    await new Promise((r) => setTimeout(r, 1500));
    // 걷는 중 새로 밝힌 칸 (base64 경로도 함께 점검)
    const add = new Uint8Array(pack(2, cells.slice(-3)));
    ch.port1.postMessage(JSON.stringify({ type: 'cells', b64: btoa(String.fromCharCode(...add)) }));
    await new Promise((r) => setTimeout(r, 3500));
    const hud = ['.title-card', '.stats', '.dock'].map((s) => getComputedStyle(document.querySelector(s)).display);
    // 첫 칸의 안개 블롭이 지도가 그 칸 중심을 그리는 화면 위치와 겹치는지 (CSS px, 1px 안쪽)
    const frame = window.__mist.frameForFog();
    const [cx, cy] = cells[0];
    const cmy = (cy + 0.5) / N;
    const target = window.__mist.map.project([((cx + 0.5) / N) * 360 - 180, (Math.atan(Math.sinh(Math.PI * (1 - 2 * cmy))) * 180) / Math.PI]);
    let blobOffset = Infinity;
    for (let i = 0; i < frame.count; i++) {
      blobOffset = Math.min(blobOffset, Math.hypot(frame.blobs[i * 3] - target.x, frame.blobs[i * 3 + 1] - target.y));
    }
    // 기록이 끊긴 곳 표시: 카메라가 그 자리로 가고 시각이 붙은 표시가 뜬다
    const spot = path[6];
    ch.port1.postMessage(JSON.stringify({ type: 'mark', lng: spot.lng, lat: spot.lat, label: '14:05 위치가 끊긴 곳' }));
    await new Promise((r) => setTimeout(r, 2000));
    const markEl = document.querySelector('.mark');
    const c = window.__mist.map.getCenter();
    const mark = {
      label: markEl?.querySelector('.mark-label')?.textContent,
      centerOffsetM: Math.hypot((c.lng - spot.lng) * 88300, (c.lat - spot.lat) * 110540),
    };
    window.__port = ch.port1;
    return { received, cells: cells.length, hud, blobOffset, mark };
  });

  await page.screenshot({ path: OUT });
  // 위치 없이 보내면 표시를 지운다
  const markCleared = await page.evaluate(async () => {
    window.__port.postMessage(JSON.stringify({ type: 'mark' }));
    await new Promise((r) => setTimeout(r, 200));
    return !document.querySelector('.mark');
  });
  await browser.close();

  const ok = result.received.some((m) => m.type === 'ready')
    && !result.received.some((m) => m.type === 'error')
    && result.hud.every((d) => d === 'none')
    && result.blobOffset < 1
    && result.mark.label === '14:05 위치가 끊긴 곳'
    && result.mark.centerOffsetM < 5
    && markCleared
    && errors.length === 0;
  console.log(JSON.stringify({ ...result, markCleared, errors, screenshot: OUT }, null, 1));
  if (!ok) process.exit(1);
  console.log('ok · 앱 모드 브리지');
})().catch((e) => { console.error(e); process.exit(1); });
