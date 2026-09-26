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
];

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage();
  for (const s of shots) {
    await page.setViewportSize(s.viewport);
    await page.goto('file://' + path.join(dir, s.html));
    await page.screenshot({ path: path.join(dir, s.out), clip: s.clip });
    console.log('wrote', s.out);
  }
  await browser.close();
})();
