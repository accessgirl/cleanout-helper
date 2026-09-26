'use strict';
// End-to-end test: runs the real app in Chromium via Playwright.
// Skipped automatically when Playwright is not installed.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

let playwright = null;
try { playwright = require('playwright'); } catch (e) { /* optional */ }

const fixtures = path.join(__dirname, '..', 'fixtures');
const port = 3100 + Math.floor(Math.random() * 500);
const outDir = process.env.E2E_OUT || null;

test('upload screenshots, build and export a document', { skip: !playwright && 'playwright not installed', timeout: 180000 }, async () => {
  const server = spawn(process.execPath, [path.join(__dirname, '..', '..', 'scripts', 'serve.js')], { env: { ...process.env, PORT: String(port) }, stdio: 'pipe' });
  await new Promise((resolve) => server.stdout.once('data', resolve));
  const browser = await playwright.chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 900 }, acceptDownloads: true });
    const errors = [];
    const external = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('request', (r) => { if (!r.url().startsWith('http://localhost') && !/^(blob|data):/.test(r.url())) external.push(r.url()); });

    await page.goto('http://localhost:' + port + '/');
    await page.setInputFiles('#file-input', ['plan.png', 'dark-mode.png', 'article-part1.png', 'article-part2.png'].map((f) => path.join(fixtures, f)));
    await page.waitForSelector('.thumb >> nth=3');
    assert.strictEqual(await page.locator('.thumb').count(), 4);

    // Reorder: move dark-mode screenshot to the end.
    await page.locator('.thumb >> nth=1').locator('[data-act="right"]').click();
    await page.locator('.thumb >> nth=2').locator('[data-act="right"]').click();
    const names = await page.locator('.thumb .name').allTextContents();
    assert.deepStrictEqual(names, ['plan.png', 'article-part1.png', 'article-part2.png', 'dark-mode.png']);

    await page.fill('#opt-title', 'My Cleanout Notes');
    await page.check('#opt-images');
    await page.click('#run');
    await page.waitForSelector('#step-result:not([hidden])', { timeout: 150000 });
    if (outDir) await page.screenshot({ path: path.join(outDir, 'app-result.png'), fullPage: true });

    const doc = await page.evaluate(() => window.ScreenshotApp.doc);
    assert.strictEqual(doc.title, 'My Cleanout Notes');
    assert.deepStrictEqual(doc.sections.map((s) => s.title), ['Garage Cleanout Plan', 'How to Sort a Cluttered Closet', 'How to Sort a Cluttered Closet (continued)', 'Moving Day Checklist']);
    assert.ok(doc.sections[2].removedLines >= 2);
    const checklist = doc.sections[3].blocks.find((b) => b.type === 'list' && b.style === 'checklist');
    assert.ok(checklist, 'dark-mode checklist recognized');
    assert.deepStrictEqual(checklist.items.map((i) => i.checked), [true, false, false]);
    assert.ok(doc.sections.every((s) => s.images && s.images.length === 1));

    const frame = page.frameLocator('#preview');
    await frame.locator('text=Key Details at a Glance').waitFor();
    assert.ok(await frame.locator('a[href="mailto:sam@example.com"]').count() >= 1);

    // Edit a section and check the preview + key details update.
    await page.click('#tab-edit');
    const ta = page.locator('.edit-section textarea').first();
    await ta.fill((await ta.inputValue()) + '\n\nBackup contact: 555-000-1111');
    await frame.locator('text=555-000-1111').first().waitFor({ state: 'attached' });
    await page.click('#tab-preview');

    for (const [kind, ext] of [['docx', 'docx'], ['md', 'md'], ['txt', 'txt'], ['html', 'html']]) {
      const [dl] = await Promise.all([page.waitForEvent('download'), page.click('[data-export="' + kind + '"]')]);
      assert.strictEqual(dl.suggestedFilename(), 'My Cleanout Notes.' + ext);
      const file = outDir ? path.join(outDir, dl.suggestedFilename()) : await dl.path();
      if (outDir) await dl.saveAs(file);
      const buf = fs.readFileSync(file);
      if (ext === 'docx') assert.strictEqual(buf.slice(0, 2).toString(), 'PK');
      else assert.match(buf.toString(), /555-000-1111/);
    }

    assert.deepStrictEqual(errors, []);
    assert.deepStrictEqual(external, [], 'everything is served locally');
  } finally {
    await browser.close();
    server.kill();
  }
});
