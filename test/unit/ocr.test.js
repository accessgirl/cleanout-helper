'use strict';
// Runs real text recognition (Tesseract.js in Node) on the sample screenshots.
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const O = require('../../public/js/organizer.js');

const fixtures = path.join(__dirname, '..', 'fixtures');
const langPath = path.join(__dirname, '..', '..', 'node_modules', '@tesseract.js-data', 'eng', '4.0.0_best_int');

test('reads and organizes real screenshots', { timeout: 120000 }, async () => {
  const Tesseract = require('tesseract.js');
  const worker = await Tesseract.createWorker('eng', 1, { langPath, cacheMethod: 'none' });
  const pages = [];
  try {
    for (const f of ['plan.png', 'article-part1.png', 'article-part2.png']) {
      const { data } = await worker.recognize(path.join(fixtures, f), {}, { blocks: true });
      pages.push({ fileName: f, data });
    }
  } finally {
    await worker.terminate();
  }

  const doc = O.organize(pages, {});
  const [plan, art1, art2] = doc.sections;
  assert.strictEqual(plan.title, 'Garage Cleanout Plan');
  assert.deepStrictEqual(plan.blocks.filter((b) => b.type === 'heading').map((b) => b.text), ['Items to donate', 'Contacts']);
  const list = plan.blocks.find((b) => b.type === 'list');
  assert.deepStrictEqual(list.items.map((i) => i.text), ['Old bicycle - $40', 'Box of books', 'Kitchen chairs (4)']);

  assert.strictEqual(art1.title, 'How to Sort a Cluttered Closet');
  assert.strictEqual(art1.blocks.find((b) => b.type === 'list').style, 'ordered');
  assert.ok(art2.removedLines >= 2, 'overlap with previous screenshot removed');
  assert.strictEqual(art2.title, 'How to Sort a Cluttered Closet (continued)');
  assert.match(art2.blocks.map((b) => b.text).join(' '), /^Finally, book a donation/);

  const values = doc.highlights.flatMap((h) => h.items.map((i) => i.value));
  for (const v of ['(555) 123-4567', 'sam@example.com', 'https://donate.example.org/pickup', '$40']) {
    assert.ok(values.includes(v), 'found ' + v);
  }
});
