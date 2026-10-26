// README 스크린샷 다시 찍기: node tools/screenshots.cjs [URL]
// 기본 URL은 로컬 서버(npm start). ?debug 시뮬레이션으로 정해진 산책 경로를 걷는다.
const path = require('node:path');
const { launch } = require('./playwright.cjs');

const URL = process.argv[2] ?? 'http://localhost:5174/';
const OUT = path.join(__dirname, '..', 'docs');

const walk = (page) => page.evaluate(async () => {
  await new Promise((r) => setTimeout(r, 2500));
  const M = window.__mist;
  const run = (codes, sec) => {
    codes.forEach((c) => M.keys.add(c));
    for (let i = 0; i < sec * 60; i++) M.update(1 / 60);
    codes.forEach((c) => M.keys.delete(c));
  };
  run(['KeyA'], 4); run(['KeyW', 'KeyA'], 2); run(['KeyW'], 3); run(['KeyD', 'KeyW'], 2.5); run(['KeyD'], 5);
  run(['KeyS', 'KeyD'], 2); run(['KeyS'], 4); run(['KeyD'], 2); run(['KeyS', 'KeyA'], 1.5);
  M.update(0);
  M.map.jumpTo({ zoom: 15.7, center: [M.state.player.lng - 0.0015, M.state.player.lat + 0.0012] });
  await new Promise((r) => setTimeout(r, 4000));
});

(async () => {
  const browser = await launch();
  const shots = [['screenshot', { width: 1280, height: 800 }], ['screenshot-mobile', { width: 390, height: 844 }]];
  for (const [name, viewport] of shots) {
    const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await page.goto(`${URL}?debug`, { waitUntil: 'networkidle' });
    await walk(page);
    await page.screenshot({ path: path.join(OUT, `${name}.png`) });
    await ctx.close();
    console.log(`docs/${name}.png`);
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
