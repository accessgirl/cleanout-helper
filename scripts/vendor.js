#!/usr/bin/env node
/*
 * Copies the text-recognition engine, English language data and the Word
 * export library from node_modules into public/vendor so the app works
 * without contacting any CDN (and works offline once loaded).
 * Runs automatically after `npm install`.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const nm = (...p) => path.join(root, 'node_modules', ...p);
const out = (...p) => path.join(root, 'public', 'vendor', ...p);

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

try {
  copy(nm('tesseract.js', 'dist', 'tesseract.min.js'), out('tesseract', 'tesseract.min.js'));
  copy(nm('tesseract.js', 'dist', 'worker.min.js'), out('tesseract', 'worker.min.js'));
  const coreDir = nm('tesseract.js-core');
  fs.readdirSync(coreDir)
    .filter((f) => /^tesseract-core.*\.wasm\.js$/.test(f))
    .forEach((f) => copy(path.join(coreDir, f), out('tesseract-core', f)));
  copy(nm('@tesseract.js-data', 'eng', '4.0.0_best_int', 'eng.traineddata.gz'), out('lang', 'eng.traineddata.gz'));
  copy(nm('docx', 'dist', 'index.iife.js'), out('docx', 'index.iife.js'));
  console.log('✓ Copied OCR engine, English language data and docx library to public/vendor');
} catch (err) {
  console.warn('! Could not copy vendor files (' + err.message + ').');
  console.warn('  The app will fall back to loading them from the jsDelivr CDN.');
}
