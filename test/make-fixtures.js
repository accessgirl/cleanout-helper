#!/usr/bin/env node
/*
 * Regenerates the test screenshots in test/fixtures from the HTML files
 * next to them. Needs Playwright: `npx playwright install chromium` first.
 */
'use strict';
const path = require('path');
const { chromium } = require('playwright');

const dir = path.join(__dirname, 'fixtures');
const shots = [
  { html: 'plan.html', out: 'plan.png', viewport: { width: 760, height: 520 } },
  { html: 'dark.html', out: 'dark-mode.png', viewport: { width: 660, height: 440 } },
  // One long page captured as two overlapping "scrolling" screenshots.
  { html: 'article.html', out: 'article-part1.png', viewport: { width: 700, height: 1100 }, clip: { x: 0, y: 0, width: 700, height: 560 } },
  { html: 'article.html', out: 'article-part2.png', viewport: { width: 700, height: 1100 }, clip: { x: 0, y: 380, width: 700, height: 330 } },
  // Phone chat screenshots (iPhone size, 3x pixel density), light and dark.
  { html: 'chat.html', theme: 'light', out: 'chat-light.png', viewport: { width: 390, height: 700 }, scale: 3 },
  { html: 'chat.html', theme: 'dark', out: 'chat-dark.png', viewport: { width: 390, height: 700 }, scale: 3 },
];

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const fs = require('fs');
  for (const s of shots) {
    const page = await browser.newPage({ viewport: s.viewport, deviceScaleFactor: s.scale || 1 });
    const html = fs.readFileSync(path.join(dir, s.html), 'utf8').replace('THEME', s.theme || '');
    await page.setContent(html);
    await page.screenshot({ path: path.join(dir, s.out), clip: s.clip });
    await page.close();
    console.log('wrote', s.out);
  }
  await browser.close();
})();
