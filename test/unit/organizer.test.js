'use strict';
const test = require('node:test');
const assert = require('node:assert');
const O = require('../../public/js/organizer.js');

// Build a fake Tesseract line. `size` = cap height in px, y = top position.
let y = 0;
function line(text, { size = 12, gap = 8, x = 20, conf = 95, para = 0 } = {}) {
  const y0 = y + gap;
  const y1 = y0 + size;
  y = y1;
  return {
    para,
    line: {
      text,
      confidence: conf,
      bbox: { x0: x, y0, x1: x + text.length * size * 0.6, y1 },
      words: [{ symbols: text.split('').map((ch) => ({ text: ch, bbox: { x0: 0, y0, x1: 1, y1 } })) }],
    },
  };
}
function page(lines) {
  y = 0;
  const built = lines.map((l) => (typeof l === 'string' ? line(l) : line(l[0], l[1])));
  return { blocks: [{ paragraphs: [{ lines: built.map((b) => b.line) }] }] };
}

test('detects headings, lists, fields and paragraphs', () => {
  const data = page([
    ['Weekend Plans', { size: 24 }],
    ['Things to buy', { size: 17, gap: 20 }],
    ['• Paint rollers', { gap: 10 }],
    '• Trash bags',
    ['Notes', { size: 17, gap: 20 }],
    ['We should start early on Saturday so the dump', { gap: 10 }],
    'is still open when we are done.',
    ['Address: 12 Main St', { gap: 22 }],
  ]);
  const doc = O.organize([{ fileName: 'a.png', data }], {});
  const s = doc.sections[0];
  assert.strictEqual(s.title, 'Weekend Plans');
  assert.deepStrictEqual(s.blocks.map((b) => b.type), ['heading', 'list', 'heading', 'paragraph', 'fields']);
  assert.deepStrictEqual(s.blocks[1].items.map((i) => i.text), ['Paint rollers', 'Trash bags']);
  assert.strictEqual(s.blocks[3].text, 'We should start early on Saturday so the dump is still open when we are done.');
  assert.deepStrictEqual(s.blocks[4].items[0], { label: 'Address', value: '12 Main St' });
});

test('classifies checklist, numbered and OCR-misread bullets', () => {
  assert.deepStrictEqual(O.classifyLine('[x] Done thing'), { kind: 'check', checked: true, text: 'Done thing' });
  assert.deepStrictEqual(O.classifyLine('[1 Open thing'), { kind: 'check', checked: false, text: 'Open thing' });
  assert.deepStrictEqual(O.classifyLine('☐ Open'), { kind: 'check', checked: false, text: 'Open' });
  assert.strictEqual(O.classifyLine('2. Second').kind, 'ordered');
  assert.strictEqual(O.classifyLine('« Old bicycle').kind, 'bullet');
  assert.strictEqual(O.classifyLine('e Box of books').kind, 'bullet');
  assert.strictEqual(O.classifyLine('e.g. not a bullet').kind, 'text');
  assert.strictEqual(O.classifyLine('Time: 9:00').kind, 'field');
  assert.strictEqual(O.classifyLine('https://example.com').kind, 'text');
});

test('removes text repeated between scrolling screenshots', () => {
  const a = page(['Intro line one here', 'Shared line alpha is here', 'Shared line beta is here', ['Cut off gar', { conf: 50 }]]);
  const b = page(['Shared line alpha is here', 'Shared line beta is here', 'Brand new line']);
  const doc = O.organize([{ fileName: '1.png', data: a }, { fileName: '2.png', data: b }], { layout: 'combined' });
  const text = O.blockTexts(doc.sections[0].blocks).join(' | ');
  assert.strictEqual((text.match(/Shared line alpha/g) || []).length, 1);
  assert.ok(!/Cut off gar/.test(text), 'cut-off line at the bottom of the first screenshot is dropped');
  assert.ok(/Brand new line/.test(text));
  assert.strictEqual(doc.sections[0].removedLines, 2);
});

test('keeps text when overlap removal is off', () => {
  const a = page(['Shared line alpha is here', 'Shared line beta is here']);
  const b = page(['Shared line alpha is here', 'Shared line beta is here']);
  const doc = O.organize([{ fileName: '1.png', data: a }, { fileName: '2.png', data: b }], { removeOverlap: false });
  assert.strictEqual(doc.sections[1].blocks.length, 1);
});

test('drops phone status bar and noise', () => {
  const data = page([['9:41 5G 87%', { gap: 2 }], ['@ ~ |', { gap: 20 }], ['Real content line', { gap: 30 }]]);
  const lines = O.extractLines(data, { imageHeight: 900 });
  assert.deepStrictEqual(lines.map((l) => l.text), ['Real content line']);
});

test('extracts key details', () => {
  const h = O.extractHighlights([
    { text: 'Meet Saturday, October 12, 2026 at 9:00 AM near the park.', source: 0 },
    { text: 'Call (555) 123-4567 or +1 555-222-3333, email jo.b@mail.example.com', source: 0 },
    { text: 'Tickets $25.50 each at www.tickets.com/show, total 102 USD.', source: 1 },
    { text: 'Deadline 11/03/2026 and again 2026-12-01; doors open 7pm', source: 1 },
    { text: 'Email again: jo.b@mail.example.com', source: 1 },
  ]);
  const get = (k) => (h.find((x) => x.key === k) || { items: [] }).items.map((i) => i.value);
  assert.deepStrictEqual(get('dates'), ['Saturday, October 12, 2026 at 9:00 AM', '11/03/2026', '2026-12-01', '7pm']);
  assert.deepStrictEqual(get('phones'), ['(555) 123-4567', '+1 555-222-3333']);
  assert.deepStrictEqual(get('emails'), ['jo.b@mail.example.com']);
  assert.deepStrictEqual(get('links'), ['www.tickets.com/show']);
  assert.deepStrictEqual(get('money'), ['$25.50', '102 USD']);
  const email = h.find((x) => x.key === 'emails').items[0];
  assert.deepStrictEqual(email.sources, [0, 1]);
});

test('markup round-trips', () => {
  const blocks = [
    { type: 'heading', level: 2, text: 'Tasks' },
    { type: 'list', style: 'checklist', items: [{ text: 'A', checked: true }, { text: 'B', checked: false }] },
    { type: 'list', style: 'ordered', items: [{ text: 'One', number: 1 }, { text: 'Two', number: 2 }] },
    { type: 'list', style: 'bullet', items: [{ text: 'x' }] },
    { type: 'fields', items: [{ label: 'When', value: 'Noon' }] },
    { type: 'paragraph', text: 'Hello there.' },
  ];
  assert.deepStrictEqual(O.parseMarkup(O.toMarkup(blocks)), blocks);
});
