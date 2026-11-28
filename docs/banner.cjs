// Renders docs/banner.html to docs/img/banner.png for the README. Usage: node docs/banner.cjs (needs playwright).
const { join } = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
  const page = await browser.newPage({ viewport: { width: 1600, height: 640 } });
  await page.goto(pathToFileURL(join(__dirname, 'banner.html')).href, { waitUntil: 'load' });
  await page.screenshot({ path: join(__dirname, 'img', 'banner.png') });
  await browser.close();
})();
