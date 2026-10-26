// 앱 모드(?app) 브리지 점검: 앱 대신 MessagePort로 위치와 칸을 보내고 스크린샷을 찍는다.
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
  await page.goto(`${URL}?app`, { waitUntil: 'networkidle' });

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
    ch.port1.postMessage(JSON.stringify({ type: 'player', lng: path.at(-1).lng, lat: path.at(-1).lat }));
    ch.port1.postMessage(pack(1, cells.slice(0, -3)));
    await new Promise((r) => setTimeout(r, 1500));
    // 걷는 중 새로 밝힌 칸 (base64 경로도 함께 점검)
    const add = new Uint8Array(pack(2, cells.slice(-3)));
    ch.port1.postMessage(JSON.stringify({ type: 'cells', b64: btoa(String.fromCharCode(...add)) }));
    await new Promise((r) => setTimeout(r, 3500));
    const hud = ['.title-card', '.stats', '.dock'].map((s) => getComputedStyle(document.querySelector(s)).display);
    return { received, cells: cells.length, hud };
  });

  await page.screenshot({ path: OUT });
  await browser.close();

  const ok = result.received.some((m) => m.type === 'ready')
    && !result.received.some((m) => m.type === 'error')
    && result.hud.every((d) => d === 'none')
    && errors.length === 0;
  console.log(JSON.stringify({ ...result, errors, screenshot: OUT }, null, 1));
  if (!ok) process.exit(1);
  console.log('ok · 앱 모드 브리지');
})().catch((e) => { console.error(e); process.exit(1); });
