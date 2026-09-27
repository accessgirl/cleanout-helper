'use strict';
const test = require('node:test');
const assert = require('node:assert');
const E = require('../../public/js/exporters.js');
const O = require('../../public/js/organizer.js');

function sampleDoc() {
  const doc = {
    title: 'Cleanout Notes',
    createdAt: '2026-09-26T12:00:00Z',
    sections: [
      {
        title: 'Garage', sourceFiles: ['a.png'],
        blocks: [
          { type: 'heading', level: 2, text: 'Donate' },
          { type: 'list', style: 'bullet', items: [{ text: 'Bike - $40' }, { text: 'Books <old>' }] },
          { type: 'paragraph', text: 'Email sam@example.com or visit https://donate.example.org/pickup.' },
        ],
      },
      {
        title: 'Moving', sourceFiles: ['b.png'],
        blocks: [
          { type: 'list', style: 'checklist', items: [{ text: 'Truck', checked: true }, { text: 'Kitchen', checked: false }] },
          { type: 'list', style: 'ordered', items: [{ text: 'First', number: 1 }] },
          { type: 'fields', items: [{ label: 'Phone', value: '555-987-6543' }] },
        ],
      },
    ],
  };
  return O.refreshHighlights(doc);
}

test('markdown has title, contents, key details and sections', () => {
  const md = E.toMarkdown(sampleDoc());
  assert.match(md, /^# Cleanout Notes/);
  assert.match(md, /## Contents\n\n1\. \[Garage\]/);
  assert.match(md, /## Key Details at a Glance/);
  assert.match(md, /- \\\$40|\$40/);
  assert.match(md, /- \[x\] Truck/);
  assert.match(md, /- \*\*Phone:\*\* 555-987-6543/);
  assert.match(md, /Books \\<old\\>/);
});

test('plain text is readable', () => {
  const txt = E.toPlainText(sampleDoc());
  assert.match(txt, /^CLEANOUT NOTES\n=+/);
  assert.match(txt, /1\. Garage\n-+/);
  assert.match(txt, /\[ \] Kitchen/);
  assert.match(txt, /Phone numbers:\n  • 555-987-6543/);
});

test('html escapes text and links urls/emails', () => {
  const html = E.toHtml(sampleDoc());
  assert.match(html, /<title>Cleanout Notes<\/title>/);
  assert.match(html, /Books &lt;old&gt;/);
  assert.match(html, /<a href="mailto:sam@example.com">/);
  assert.match(html, /<a href="https:\/\/donate.example.org\/pickup">https:\/\/donate.example.org\/pickup<\/a>\./);
  assert.match(html, /<nav class="toc">/);
  const bare = E.toHtml({ title: 'T', sections: [{ title: 'S', sourceFiles: [], blocks: [{ type: 'paragraph', text: 'See naca.com (found in: a.png) or www.x.org/y.' }] }] });
  assert.match(bare, /<a href="https:\/\/naca.com">naca.com<\/a> \(found in: a.png\)/);
  assert.match(bare, /<a href="https:\/\/www.x.org\/y">www.x.org\/y<\/a>\./);
  assert.match(html, /class="glance"/);
});

test('single section titled like the document does not repeat the title', () => {
  const doc = sampleDoc();
  doc.sections = [doc.sections[0]];
  doc.sections[0].title = doc.title;
  const html = E.toHtml(doc);
  assert.strictEqual((html.match(/Cleanout Notes<\/h[12]>/g) || []).length, 1);
  assert.doesNotMatch(html, /class="toc"/);
});

test('docx builds a valid Word file', async () => {
  const docx = require('docx');
  const buf = await E.toDocx(sampleDoc(), docx, { asBuffer: true });
  assert.ok(buf.length > 3000);
  assert.strictEqual(buf.slice(0, 2).toString(), 'PK'); // zip container
  const xml = await readDocumentXml(buf);
  assert.match(xml, /Cleanout Notes/);
  assert.match(xml, /Key Details at a Glance/);
  assert.match(xml, /Kitchen/);
});

test('safe file names', () => {
  assert.strictEqual(E.safeFileName('My: notes / today?', 'md'), 'My notes today.md');
  assert.strictEqual(E.safeFileName('', 'txt'), 'screenshot-notes.txt');
  assert.strictEqual(E.safeFileName('Sliders are easy and delicious…', 'docx'), 'Sliders are easy and delicious.docx');
});

// Minimal zip reader for word/document.xml (stored or deflated).
async function readDocumentXml(buf) {
  const zlib = require('zlib');
  let i = 0;
  while (i < buf.length - 30 && buf.readUInt32LE(i) === 0x04034b50) {
    const method = buf.readUInt16LE(i + 8);
    const csize = buf.readUInt32LE(i + 18);
    const nlen = buf.readUInt16LE(i + 26);
    const xlen = buf.readUInt16LE(i + 28);
    const name = buf.slice(i + 30, i + 30 + nlen).toString();
    const start = i + 30 + nlen + xlen;
    const data = buf.slice(start, start + csize);
    if (name === 'word/document.xml') return (method === 8 ? zlib.inflateRawSync(data) : data).toString();
    i = start + csize;
  }
  throw new Error('document.xml not found');
}
