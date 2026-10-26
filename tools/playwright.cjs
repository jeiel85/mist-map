// 로컬 설치가 없으면 전역 @playwright/cli에 딸린 playwright를 쓴다
const { execSync } = require('node:child_process');
const path = require('node:path');

function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    const root = execSync('npm root -g', { encoding: 'utf8' }).trim();
    return require(path.join(root, '@playwright/cli/node_modules/playwright'));
  }
}

// 헤드리스에서도 WebGL2가 돌도록 시스템 Chrome + SwiftShader
async function launch() {
  const { chromium } = loadPlaywright();
  return chromium.launch({ channel: 'chrome', args: ['--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
}

module.exports = { launch };
