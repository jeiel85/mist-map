// WebGL 컨텍스트 손실·복구 뒤에도 안개가 그려지는지 점검 (이슈 #1)
// node tools/context-loss.cjs [URL]   (기본: 로컬 서버)
const zlib = require('node:zlib');
const { launch } = require('./playwright.cjs');

const URL = process.argv[2] ?? 'http://localhost:5174/';

// 1×1 PNG 스크린샷의 RGB를 읽는다 (필터 바이트 하나 + 픽셀)
function pixel(png) {
  let off = 8, idat = [];
  let colorType = 2;
  while (off < png.length) {
    const len = png.readUInt32BE(off);
    const type = png.toString('ascii', off + 4, off + 8);
    const data = png.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') colorType = data[9];
    if (type === 'IDAT') idat.push(data);
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  if (colorType !== 2 && colorType !== 6) throw new Error(`알 수 없는 PNG 색 형식 ${colorType}`);
  return [raw[1], raw[2], raw[3]];
}

// 밤안개(남색)인지: 파랑이 빨강보다 뚜렷이 크고 어둡다
const isNight = ([r, g, b]) => b > r + 20 && r < 90;
// 걷힌 곳의 등불빛 지도인지: 따뜻한 색(빨강이 파랑보다 큼)이고 어둡지 않다
const isLitMap = ([r, g, b]) => r > 120 && r > b + 20;

(async () => {
  const browser = await launch();
  const page = await browser.newPage({ viewport: { width: 600, height: 400 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(`${URL}?debug`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2500);
  // 구석은 밤안개, 캐릭터 바로 아래는 걷힌 지도여야 한다.
  // 캔버스가 비면 페이지 바탕(남색)이 비쳐 구석만으로는 구별이 안 되므로 걷힌 곳도 함께 본다
  const corner = { x: 20, y: 120, width: 1, height: 1 };
  const lit = { x: 335, y: 150, width: 1, height: 1 };
  const before = [pixel(await page.screenshot({ clip: corner })), pixel(await page.screenshot({ clip: lit }))];

  await page.evaluate(async () => {
    const gl = window.__mist.map.painter.context.gl;
    const ext = gl.getExtension('WEBGL_lose_context');
    ext.loseContext();
    await new Promise((r) => setTimeout(r, 500));
    ext.restoreContext();
    await new Promise((r) => setTimeout(r, 1500));
    window.__mist.map.triggerRepaint();
    await new Promise((r) => setTimeout(r, 500));
  });
  const after = [pixel(await page.screenshot({ clip: corner })), pixel(await page.screenshot({ clip: lit }))];
  await browser.close();

  console.log(JSON.stringify({ before, after, errors }));
  const ok = (s) => isNight(s[0]) && isLitMap(s[1]);
  if (!ok(before) || !ok(after) || errors.length) process.exit(1);
  console.log('ok · 컨텍스트 복구 뒤에도 밤안개');
})().catch((e) => { console.error(e); process.exit(1); });
