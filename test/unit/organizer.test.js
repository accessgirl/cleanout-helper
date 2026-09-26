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
    ['We should start early on Saturday so the dump truck', { gap: 10 }],
    'can get there before the lines at the transfer station',
    'get long and the dump is still open when we finish',
    'the job.',
    ['Address: 12 Main St', { gap: 22 }],
  ]);
  const doc = O.organize([{ fileName: 'a.png', data }], {});
  const s = doc.sections[0];
  assert.strictEqual(s.title, 'Weekend Plans');
  assert.deepStrictEqual(s.blocks.map((b) => b.type), ['heading', 'list', 'heading', 'paragraph', 'fields']);
  assert.deepStrictEqual(s.blocks[1].items.map((i) => i.text), ['Paint rollers', 'Trash bags']);
  assert.strictEqual(s.blocks[3].text, 'We should start early on Saturday so the dump truck can get there before the lines ' +
    'at the transfer station get long and the dump is still open when we finish the job.');
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
  assert.deepStrictEqual(O.blockTexts(doc.sections[1].blocks), ['Shared line alpha is here', 'Shared line beta is here']);
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

test('short lines in a row become a list; separate lines are not glued together', () => {
  const data = page([
    ['INGREDIENTS', { gap: 10 }],
    '12 count Hawaiian Original Rolls',
    'Olive oil',
    '1 lb chicken breast, cut into cubes',
    'BBQ sauce',
  ]);
  const doc = O.organize([{ fileName: 'r.png', data }], {});
  const list = doc.sections[0].blocks.find((b) => b.type === 'list');
  assert.deepStrictEqual(list.items.map((i) => i.text), ['12 count Hawaiian Original Rolls', 'Olive oil', '1 lb chicken breast, cut into cubes', 'BBQ sauce']);
});

test('puts scrolling screenshots in order, joins them and drops repeated app bars', () => {
  // A long page: lines every 40px. Screen 1 shows lines 0-5, screen 2 lines 3-8.
  const all = ['Line zero of the long recipe text', 'Line one of the long recipe text', 'Line two of the long recipe text',
    'Line three of the long recipe text', 'Line four of the long recipe text', 'Line five of the long recipe text',
    'Line six of the long recipe text', 'Line seven of the long recipe text', 'Line eight of the long recipe text'];
  const screen = (from, to) => {
    const lines = all.slice(from, to).map((t, i) => ({
      text: t, confidence: 95, bbox: { x0: 20, y0: 100 + i * 40, x1: 300, y1: 120 + i * 40 }, words: [],
    }));
    lines.push({ text: 'Write a message here please', confidence: 95, bbox: { x0: 20, y0: 700, x1: 300, y1: 720 }, words: [] });
    return { blocks: [{ paragraphs: [{ lines }] }] };
  };
  // Uploaded in the wrong order.
  const doc = O.organize([
    { fileName: 'second.png', data: screen(3, 9), imageHeight: 800, imageWidth: 400 },
    { fileName: 'first.png', data: screen(0, 6), imageHeight: 800, imageWidth: 400 },
  ], {});
  assert.strictEqual(doc.sections.length, 1);
  assert.deepStrictEqual(doc.sections[0].sourceFiles, ['first.png', 'second.png']);
  // Same-width lines read as one wrapped paragraph; the app bar is gone.
  assert.strictEqual(O.blockTexts(doc.sections[0].blocks).join(' '), all.join(' '));
});

test('chat screenshots become a transcript', () => {
  const W = 1170;
  const bubble = (text, side, y) => ({
    text, confidence: 95, words: [],
    bbox: side === 'me' ? { x0: 316, y0: y, x1: 1080, y1: y + 48 } : side === 'them' ? { x0: 77, y0: y, x1: 800, y1: y + 48 } : { x0: 466, y0: y, x1: 702, y1: y + 36 },
  });
  const lines = [
    bubble('Jamie Diaz', 'center', 280),
    bubble('Today 2:14 PM', 'center', 400),
    bubble('Are we still on for Saturday?', 'them', 500),
    bubble('Yes! Can you be there by 9:30', 'me', 700),
    bubble('AM?', 'me', 767),
    bubble('Sure, I will bring the truck', 'them', 900),
    bubble('Great, see you then', 'me', 1100),
  ];
  lines.forEach((l, i) => { l.block = i; });
  const data = { blocks: lines.map((l) => ({ paragraphs: [{ lines: [l] }] })) };
  const doc = O.organize([{ fileName: 'chat.png', data, imageHeight: 2532, imageWidth: W }], {});
  const s = doc.sections[0];
  assert.strictEqual(s.title, 'Conversation with Jamie Diaz');
  const chat = s.blocks.find((b) => b.type === 'fields');
  assert.deepStrictEqual(chat.items, [
    { label: 'Jamie Diaz', value: 'Are we still on for Saturday?' },
    { label: 'Me', value: 'Yes! Can you be there by 9:30 AM?' },
    { label: 'Jamie Diaz', value: 'Sure, I will bring the truck' },
    { label: 'Me', value: 'Great, see you then' },
  ]);
});

test('drops unreadable photo text and trims icons', () => {
  const w = (text, confidence, x0) => ({ text, confidence, bbox: { x0, y0: 0, x1: x0 + text.length * 10, y1: 20 } });
  const mk = (words, y) => ({
    text: words.map((x) => x.text).join(' '), confidence: 80, words,
    bbox: { x0: words[0].bbox.x0, y0: y, x1: words[words.length - 1].bbox.x1, y1: y + 20 },
  });
  const data = { blocks: [{ paragraphs: [{ lines: [
    mk([w('op', 20, 10), w('ET', 16, 40), w('a', 29, 70)], 100),
    mk([w('wv', 11, 10), w('View', 95, 40), w('the', 95, 90), w('recipe', 95, 130)], 140),
    mk([w('Sure.', 95, 10), w('|', 70, 70), w('will', 95, 90), w('come', 95, 140)], 180),
  ] }] }] };
  const lines = O.extractLines(data, {});
  assert.deepStrictEqual(lines.map((l) => l.text), ['View the recipe', 'Sure. I will come']);
});
