// 가져오기 흐름 점검: node tools/e2e-import.cjs [URL] [스크린샷 폴더]
// 샘플 가져오기 → 같은 파일 재가져오기(중복 0) → 잘못된 파일 안내 → 새로고침 후 유지
// → 시트에서 파일 하나 되돌리기(새로고침 후에도 유지) → 예전 저장 형식(v1) 옮기기 → 콘솔 오류 없음
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
  // GL 드라이버 성능 알림(스크린샷을 찍으며 Chrome이 픽셀을 읽을 때 CI의 GL이 냄)은 앱 오류가 아니다
  const driverNote = /GL Driver Message \([^)]*, Performance,/;
  page.on('console', (m) => { if (['error', 'warning'].includes(m.type()) && !driverNote.test(m.text())) errors.push(m.text()); });
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
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('mistmap:v2')).v), 2, '압축 형식(v2)으로 저장');

  // 파일마다 되돌리기: GPX를 되돌리면 그 파일이 새로 밝힌 곳만 사라진다
  await page.click('#btn-import');
  const rows = await page.$$eval('#import-list li span', (els) => els.map((e) => e.textContent));
  assert.equal(rows.length, 2, `가져온 파일 두 개가 목록에 있어야 함: ${rows}`);
  page.once('dialog', (d) => d.accept());
  const gpxRow = rows.findIndex((r) => r.startsWith('GPX'));
  await page.click(`#import-list li:nth-child(${gpxRow + 1}) button`);
  assert.match(await toastAfter(300), /GPX · seongsu-walk\.gpx 기록을 되돌렸어요/);
  await page.click('#import-close');
  const afterUndo = await page.textContent('#stat-spots');
  assert.ok(Number(afterUndo.replace(/,/g, '')) < Number(spots.replace(/,/g, '')), '되돌리면 발자국이 줄어야 함');
  await page.waitForTimeout(1200);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  assert.equal(await page.textContent('#stat-spots'), afterUndo, '되돌린 결과가 새로고침 후에도 유지');

  // 예전 형식(v1)은 읽어서 다음 저장 때 v2로 옮긴다
  await page.evaluate(() => {
    localStorage.removeItem('mistmap:v2');
    localStorage.setItem('mistmap:v1', JSON.stringify({ origin: { lng: 127.056, lat: 37.5446 }, player: { lng: 127.056, lat: 37.5446 }, walkedM: 10, points: [[127.056, 37.5446], [127.057, 37.5446]] }));
  });
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  assert.equal(await page.textContent('#stat-spots'), '2', 'v1 저장을 읽어야 함');
  assert.deepEqual(await page.evaluate(() => [!!localStorage.getItem('mistmap:v2'), localStorage.getItem('mistmap:v1')]), [true, null], 'v2로 옮겨야 함');

  assert.deepEqual(errors, [], '콘솔 오류가 없어야 함');
  console.log(`ok · ${first} · 발자국 ${spots}`);
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
