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
  await worker.setParameters({ tessedit_pageseg_mode: '3' }); // same as the app
  const pages = [];
  try {
    for (const f of ['plan.png', 'article-part1.png', 'article-part2.png']) {
      const { data } = await worker.recognize(path.join(fixtures, f), {}, { blocks: true });
      pages.push({ fileName: f, data, imageHeight: data.imageHeight, imageWidth: data.imageWidth });
    }
  } finally {
    await worker.terminate();
  }

  const doc = O.organize(pages, {});
  // The two article screenshots overlap, so they are joined into one section.
  assert.strictEqual(doc.sections.length, 2);
  const [plan, article] = doc.sections;
  assert.strictEqual(plan.title, 'Garage Cleanout Plan');
  assert.deepStrictEqual(plan.blocks.filter((b) => b.type === 'heading').map((b) => b.text), ['Items to donate', 'Contacts']);
  const list = plan.blocks.find((b) => b.type === 'list');
  assert.deepStrictEqual(list.items.map((i) => i.text), ['Old bicycle - $40', 'Box of books', 'Kitchen chairs (4)']);

  assert.strictEqual(article.title, 'How to Sort a Cluttered Closet');
  assert.deepStrictEqual(article.sourceFiles, ['article-part1.png', 'article-part2.png']);
  assert.ok(article.removedLines >= 2, 'overlap with previous screenshot removed');
  const text = O.blockTexts(article.blocks).join(' | ');
  assert.strictEqual((text.match(/Put things back/g) || []).length, 1);
  assert.match(text, /Finally, book a donation/);
  assert.strictEqual(article.blocks.find((b) => b.type === 'list').style, 'ordered');

  const values = doc.highlights.flatMap((h) => h.items.map((i) => i.value));
  for (const v of ['(555) 123-4567', 'sam@example.com', 'https://donate.example.org/pickup', '$40']) {
    assert.ok(values.includes(v), 'found ' + v);
  }
});
