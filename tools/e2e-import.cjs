// 가져오기 흐름 점검: node tools/e2e-import.cjs [URL] [스크린샷 폴더]
// 샘플 가져오기 → 같은 파일 재가져오기(중복 0) → 잘못된 파일 안내 → 새로고침 후 유지 → 콘솔 오류 없음
const path = require('node:path');
const assert = require('node:assert/strict');
const { launch } = require('./playwright.cjs');

const URL = process.argv[2] ?? 'http://localhost:5174/';
const SHOTS = process.argv[3];
const SAMPLES = path.join(__dirname, '..', 'samples');

(async () => {
  const browser = await launch();
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 800 } })).newPage();
  const errors = [];
  page.on('console', (m) => { if (['error', 'warning'].includes(m.type())) errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  const toastAfter = async (ms) => { await page.waitForTimeout(ms); return page.textContent('#toast'); };

  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  await page.click('#btn-import');
  await page.setInputFiles('#import-input', [path.join(SAMPLES, 'seongsu-walk.gpx'), path.join(SAMPLES, 'google-timeline-sample.json')]);
  const first = await toastAfter(800);
  assert.match(first, /GPX · 구글 타임라인에서 [\d,]+곳을 새로 밝혔어요/);
  await page.waitForTimeout(5000);
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'import-after.png') });
  const spots = await page.textContent('#stat-spots');

  await page.click('#btn-import');
  await page.setInputFiles('#import-input', [path.join(SAMPLES, 'seongsu-walk.gpx')]);
  assert.match(await toastAfter(600), /이미 모두 밝혀져 있어요/);

  await page.click('#btn-import');
  await page.setInputFiles('#import-input', { name: 'bad.json', mimeType: 'application/json', buffer: Buffer.from('{"x":1}') });
  assert.match(await toastAfter(400), /bad\.json: 알 수 없는 JSON/);

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  assert.equal(await page.textContent('#stat-spots'), spots, '새로고침 후 기록이 유지되어야 함');

  assert.deepEqual(errors, [], '콘솔 오류가 없어야 함');
  console.log(`ok · ${first} · 발자국 ${spots}`);
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
