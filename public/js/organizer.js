/*
 * organizer.js
 * Turns raw OCR output (Tesseract.js "blocks") into a clean, structured
 * document model: headings, paragraphs, bullet/numbered lists, checklists and
 * "Label: value" fields. Also pulls out key details (dates, phone numbers,
 * emails, links, money amounts) so they can be shown at a glance.
 *
 * Works in the browser (window.Organizer) and in Node (module.exports) so the
 * logic can be unit-tested without a browser.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Organizer = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ---------------------------------------------------------------- helpers

  function median(values) {
    const v = values.filter((n) => Number.isFinite(n)).sort((a, b) => a - b);
    if (!v.length) return 0;
    const mid = Math.floor(v.length / 2);
    return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
  }

  function cleanText(text) {
    return String(text || '')
      .replace(/[​-‍﻿]/g, '')
      .replace(/[“”]/g, '"')
      .replace(/[‘’]/g, "'")
      .replace(/\s+/g, ' ')
      .trim();
  }

  // Normalised form used to compare lines between screenshots.
  function normalize(text) {
    return String(text || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  }

  function alnumCount(text) {
    return (String(text).match(/[\p{L}\p{N}]/gu) || []).length;
  }

  // Characters whose glyph reaches cap height without a descender. Their
  // height is a good estimate of font size regardless of the line's content.
  const CAP_CHARS = /[A-Z0-9bdfhklt]/;

  function capHeight(line) {
    const heights = [];
    (line.words || []).forEach((w) => {
      (w.symbols || []).forEach((s) => {
        if (s.bbox && CAP_CHARS.test(s.text)) heights.push(s.bbox.y1 - s.bbox.y0);
      });
    });
    if (heights.length) return median(heights);
    return line.bbox ? (line.bbox.y1 - line.bbox.y0) * 0.72 : 0;
  }

  // ---------------------------------------------------------------- patterns

  const SYMBOL_BULLET = /^[•●○◦▪▫■◆◇►▸‣⁃∙·\-–—*+>]\s+(.+)$/;
  // OCR often misreads round bullets as one of these letters/marks.
  const LOOKALIKE_BULLET = /^[«»©®oe¢c]\s+([A-Z0-9"'(].*)$/;
  const NUMBERED = /^(\d{1,2})[.)]\s+(.+)$/;
  // "[ ]", "[x]", ballot boxes, plus common OCR misreads of an empty box ("[1", "[]", "LJ").
  const CHECKBOX = /^(?:\[( |x|X|✓|✔)?\]|\[[1lI|](?=\s)|LJ(?=\s)|(☐|□)|(☑|☒|✅|✓|✔))\s*(.+)$/;
  const FIELD = /^([A-Z][A-Za-z0-9 '&/#().-]{0,28}):\s+(\S.*)$/;
  const STATUS_TIME = /\b\d{1,2}:\d{2}\b/;

  function classifyLine(text) {
    let m = text.match(CHECKBOX);
    if (m) return { kind: 'check', checked: Boolean((m[1] && m[1].trim()) || m[3]), text: m[4] };
    m = text.match(NUMBERED);
    if (m) return { kind: 'ordered', number: Number(m[1]), text: m[2] };
    m = text.match(SYMBOL_BULLET) || text.match(LOOKALIKE_BULLET);
    if (m) return { kind: 'bullet', text: m[1] };
    m = text.match(FIELD);
    if (m && !/^https?$/i.test(m[1])) return { kind: 'field', label: m[1].trim(), text: m[2] };
    return { kind: 'text', text };
  }

  function isAllCapsLabel(text) {
    const letters = text.replace(/[^A-Za-z]/g, '');
    return letters.length >= 3 && letters === letters.toUpperCase() &&
      text.split(' ').length <= 6 && text.length <= 48;
  }

  function isShortLabel(text) {
    return text.length <= 40 && text.split(' ').length <= 5 && /^[\p{Lu}\d]/u.test(text) && !/[.,;:!?]$/.test(text);
  }

  // ------------------------------------------------------- OCR -> lines

  /**
   * Flatten Tesseract output into an ordered list of lines with metrics.
   * @param {object} data   `data` from worker.recognize(..., {blocks: true})
   * @param {object} opts   { imageHeight, ignoreStatusBar, minConfidence }
   */
  function extractLines(data, opts) {
    opts = opts || {};
    const minConfidence = opts.minConfidence == null ? 35 : opts.minConfidence;
    const lines = [];
    let paraId = 0;
    (data && data.blocks || []).forEach((block) => {
      (block.paragraphs || []).forEach((para) => {
        paraId += 1;
        (para.lines || []).forEach((line) => {
          const text = cleanText(line.text);
          if (!text) return;
          const alnum = alnumCount(text);
          // Drop OCR noise: icons, separators, specks.
          if (alnum === 0) return;
          if (alnum / text.replace(/\s/g, '').length < 0.4 && alnum < 4) return;
          if (line.confidence < minConfidence && alnum < 4) return;
          if (line.confidence < 20) return;
          const bbox = line.bbox || { x0: 0, y0: 0, x1: 0, y1: 0 };
          // Phone status bar ("9:41  5G  87%") at the very top of the image.
          if (opts.ignoreStatusBar !== false && opts.imageHeight &&
              bbox.y1 < opts.imageHeight * 0.06 && text.length < 30 && STATUS_TIME.test(text)) return;
          lines.push({
            text,
            bbox,
            size: capHeight(line),
            confidence: line.confidence,
            paraId,
          });
        });
      });
    });
    return lines;
  }

  // ---------------------------------------------- overlap between screenshots

  /**
   * When several screenshots are taken while scrolling, the top of one
   * usually repeats the bottom of the previous one. Finds that repeated run.
   * A few lines may be skipped at the bottom of `previous` (a half cut-off
   * line) and at the top of `current` (an app header or cut-off line).
   * Returns { dropPrev, dropCur }: trailing lines to remove from `previous`
   * and leading lines to remove from `current`.
   */
  function findOverlap(previous, current) {
    const prev = previous.map((l) => normalize(l.text));
    const cur = current.map((l) => normalize(l.text));
    let best = { dropPrev: 0, dropCur: 0, score: 0 };
    for (let skipPrev = 0; skipPrev <= Math.min(2, prev.length - 1); skipPrev++) {
      const tail = prev.slice(0, prev.length - skipPrev);
      for (let skipCur = 0; skipCur <= Math.min(3, cur.length - 1); skipCur++) {
        const maxK = Math.min(tail.length, cur.length - skipCur);
        for (let k = maxK; k >= 1; k--) {
          let ok = true;
          let chars = 0;
          for (let i = 0; i < k; i++) {
            const a = tail[tail.length - k + i];
            if (!a || a !== cur[skipCur + i]) { ok = false; break; }
            chars += a.length;
          }
          // Require enough matching text that this isn't a coincidence.
          if (ok && (k >= 2 || chars >= 20)) {
            if (chars > best.score) best = { dropPrev: skipPrev, dropCur: skipCur + k, score: chars };
            break;
          }
        }
      }
    }
    return { dropPrev: best.dropPrev, dropCur: best.dropCur };
  }

  // ------------------------------------------------------- lines -> blocks

  function joinText(a, b) {
    if (/[A-Za-z]-$/.test(a) && /^[a-z]/.test(b)) return a.slice(0, -1) + b;
    return a + ' ' + b;
  }

  /**
   * Group lines into document blocks using font size, spacing and
   * line prefixes.
   */
  function buildBlocks(lines) {
    if (!lines.length) return [];

    const weighted = [];
    lines.forEach((l) => {
      const reps = Math.max(1, Math.round(l.text.length / 10));
      for (let i = 0; i < reps; i++) weighted.push(l.size);
    });
    const bodySize = median(weighted) || 1;
    const gaps = [];
    for (let i = 1; i < lines.length; i++) {
      const g = lines[i].bbox.y0 - lines[i - 1].bbox.y1;
      if (g >= 0) gaps.push(g);
    }
    const typicalGap = median(gaps) || bodySize * 0.6;
    const leftMargin = median(lines.map((l) => l.bbox.x0));

    const blocks = [];
    let current = null; // paragraph or list being built
    let prev = null;

    function flush() {
      if (current) blocks.push(current);
      current = null;
    }

    lines.forEach((line) => {
      const ratio = line.size / bodySize;
      const gap = prev ? line.bbox.y0 - prev.bbox.y1 : 0;
      const bigGap = prev && (gap > Math.max(typicalGap * 1.8, bodySize * 1.1) || gap < -bodySize * 2);
      const c = classifyLine(line.text);

      // ---- headings
      let level = 0;
      if (c.kind === 'text' && line.text.length <= 90 && !/[.,;]$/.test(line.text)) {
        if (ratio >= 1.65) level = 1;
        else if (ratio >= 1.25) level = 2;
        else if (isAllCapsLabel(line.text) || (ratio >= 1.12 && isShortLabel(line.text))) level = 3;
      }
      if (level) {
        const last = blocks[blocks.length - 1];
        if (!current && last && last.type === 'heading' && last.level === level && !bigGap &&
            prev && prev.headingLevel === level) {
          last.text = joinText(last.text, line.text); // heading wrapped onto 2 lines
        } else {
          flush();
          blocks.push({ type: 'heading', level, text: line.text });
        }
        line.headingLevel = level;
        prev = line;
        return;
      }

      // ---- list items
      if (c.kind === 'bullet' || c.kind === 'ordered' || c.kind === 'check') {
        const listType = c.kind === 'check' ? 'checklist' : c.kind === 'ordered' ? 'ordered' : 'bullet';
        if (!current || current.type !== 'list' || current.style !== listType || bigGap) {
          flush();
          current = { type: 'list', style: listType, items: [] };
        }
        const item = { text: c.text, x: line.bbox.x0 };
        if (listType === 'checklist') item.checked = c.checked;
        if (listType === 'ordered') item.number = c.number;
        current.items.push(item);
        prev = line;
        return;
      }

      // Continuation of a wrapped list item (indented, no bullet, close by).
      if (current && current.type === 'list' && !bigGap && prev && !prev.headingLevel &&
          line.bbox.x0 > leftMargin + bodySize * 0.8) {
        const item = current.items[current.items.length - 1];
        item.text = joinText(item.text, line.text);
        prev = line;
        return;
      }

      // ---- "Label: value" fields
      if (c.kind === 'field') {
        if (!current || current.type !== 'fields' || bigGap) {
          flush();
          current = { type: 'fields', items: [] };
        }
        current.items.push({ label: c.label, value: c.text });
        prev = line;
        return;
      }

      // ---- paragraphs
      const newPara = !current || current.type !== 'paragraph' || bigGap ||
        (prev && prev.paraId !== line.paraId && gap > typicalGap * 1.3);
      if (newPara) {
        flush();
        current = { type: 'paragraph', text: line.text };
      } else {
        current.text = joinText(current.text, line.text);
      }
      prev = line;
    });
    flush();

    // Remove layout-only info.
    blocks.forEach((b) => {
      if (b.type === 'list') b.items.forEach((i) => { delete i.x; });
    });
    return blocks;
  }

  // ---------------------------------------------------------- key details

  const MONTHS = '(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)';
  const DAYS = '(?:Mon|Tue|Tues|Wed|Thu|Thur|Thurs|Fri|Sat|Sun)(?:day|nesday|urday|sday)?';

  const DETECTORS = [
    {
      key: 'dates',
      label: 'Dates & times',
      patterns: [
        new RegExp('\\b(?:' + DAYS + ',?\\s+)?' + MONTHS + '\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?(?:,?\\s+(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s?(?:[AaPp]\\.?[Mm]\\.?))?', 'g'),
        new RegExp('\\b(?:' + DAYS + ',?\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+' + MONTHS + '\\.?(?:,?\\s+\\d{4})?', 'g'),
        /\b\d{4}-\d{2}-\d{2}\b/g,
        /\b\d{1,2}[/.-]\d{1,2}[/.-](?:\d{4}|\d{2})\b/g,
        /\b\d{1,2}:\d{2}\s?(?:[AaPp]\.?[Mm]\.?)(?![\w])/g,
        /\b\d{1,2}\s?(?:[AaPp]\.[Mm]\.|[AaPp][Mm])\b/g,
      ],
    },
    {
      key: 'phones',
      label: 'Phone numbers',
      patterns: [/(?:\+\d{1,3}[\s.-]?)?(?:\(\d{3}\)\s?|\b\d{3}[\s.-])\d{3}[\s.-]\d{4}\b/g],
    },
    {
      key: 'emails',
      label: 'Email addresses',
      patterns: [/\b[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[A-Za-z]{2,}\b/g],
    },
    {
      key: 'links',
      label: 'Websites & links',
      patterns: [
        /\b(?:https?:\/\/|www\.)[^\s<>"'()]+/gi,
        /(?<![@\w.\/-])(?:[a-z0-9-]+\.)+(?:com|org|net|edu|gov|io|co|us|uk|ca|app|dev|info|biz|me)(?:\/[^\s<>"'()]*)?(?![\w@])/gi,
      ],
    },
    {
      key: 'money',
      label: 'Money amounts',
      patterns: [
        /(?:[$€£¥]\s?\d[\d,]*(?:\.\d{1,2})?)(?!\d)/g,
        /\b\d[\d,]*(?:\.\d{2})?\s?(?:USD|EUR|GBP|CAD|AUD|dollars)\b/gi,
      ],
    },
  ];

  function trimMatch(key, value) {
    let v = value.trim();
    if (key === 'links' || key === 'emails') v = v.replace(/[.,;:!?]+$/, '');
    if (key === 'money') v = v.replace(/,$/, '');
    return v;
  }

  /**
   * Find key details in a list of {text, source} items.
   * Returns [{key, label, items: [{value, sources: [..]}]}] (only non-empty).
   */
  function extractHighlights(entries) {
    const result = DETECTORS.map((d) => ({ key: d.key, label: d.label, items: [] }));
    entries.forEach(({ text, source }) => {
      DETECTORS.forEach((d, idx) => {
        const bucket = result[idx];
        const found = [];
        d.patterns.forEach((re) => {
          re.lastIndex = 0;
          let m;
          while ((m = re.exec(text))) {
            found.push({ start: m.index, end: m.index + m[0].length, value: trimMatch(d.key, m[0]) });
          }
        });
        // Keep the longest match where matches overlap (e.g. a full date vs. its time).
        found.sort((a, b) => a.start - b.start || (b.end - b.start) - (a.end - a.start));
        let lastEnd = -1;
        found.forEach((f) => {
          if (f.start < lastEnd || !f.value) return;
          lastEnd = f.end;
          const existing = bucket.items.find((i) => i.value.toLowerCase() === f.value.toLowerCase());
          if (existing) {
            if (!existing.sources.includes(source)) existing.sources.push(source);
          } else {
            bucket.items.push({ value: f.value, sources: [source] });
          }
        });
      });
    });
    // A link that is really just an email's domain is noise.
    const emails = result.find((r) => r.key === 'emails').items.map((i) => i.value.toLowerCase());
    const links = result.find((r) => r.key === 'links');
    links.items = links.items.filter((l) => !emails.some((e) => e.endsWith('@' + l.value.toLowerCase()) || e === l.value.toLowerCase()));
    return result.filter((r) => r.items.length);
  }

  function blockTexts(blocks) {
    const out = [];
    blocks.forEach((b) => {
      if (b.type === 'heading' || b.type === 'paragraph') out.push(b.text);
      else if (b.type === 'list') b.items.forEach((i) => out.push(i.text));
      else if (b.type === 'fields') b.items.forEach((i) => out.push(i.label + ': ' + i.value));
    });
    return out;
  }

  function countWords(blocks) {
    return blockTexts(blocks).join(' ').split(/\s+/).filter(Boolean).length;
  }

  // ---------------------------------------------- simple editable markup

  /** Blocks -> plain-text markup the user can edit. */
  function toMarkup(blocks) {
    return blocks.map((b) => {
      switch (b.type) {
        case 'heading': return '#'.repeat(b.level) + ' ' + b.text;
        case 'list':
          return b.items.map((it, i) => {
            if (b.style === 'checklist') return (it.checked ? '[x] ' : '[ ] ') + it.text;
            if (b.style === 'ordered') return (it.number || i + 1) + '. ' + it.text;
            return '- ' + it.text;
          }).join('\n');
        case 'fields': return b.items.map((f) => f.label + ': ' + f.value).join('\n');
        default: return b.text;
      }
    }).join('\n\n');
  }

  /** Markup -> blocks (inverse of toMarkup). */
  function parseMarkup(markup) {
    const blocks = [];
    let current = null;
    const flush = () => { if (current) blocks.push(current); current = null; };
    String(markup || '').split(/\r?\n/).forEach((raw) => {
      const line = raw.trim();
      if (!line) { flush(); return; }
      const h = line.match(/^(#{1,3})\s+(.+)$/);
      if (h) { flush(); blocks.push({ type: 'heading', level: h[1].length, text: h[2].trim() }); return; }
      let m = line.match(/^\[( |x|X)?\]\s*(.+)$/);
      let style = null;
      let item = null;
      if (m) { style = 'checklist'; item = { text: m[2], checked: Boolean(m[1] && m[1].trim()) }; }
      else if ((m = line.match(/^(\d{1,3})[.)]\s+(.+)$/))) { style = 'ordered'; item = { text: m[2], number: Number(m[1]) }; }
      else if ((m = line.match(/^[-*•]\s+(.+)$/))) { style = 'bullet'; item = { text: m[1] }; }
      if (item) {
        if (!current || current.type !== 'list' || current.style !== style) {
          flush();
          current = { type: 'list', style, items: [] };
        }
        current.items.push(item);
        return;
      }
      m = line.match(FIELD);
      if (m && !/^https?$/i.test(m[1])) {
        if (!current || current.type !== 'fields') { flush(); current = { type: 'fields', items: [] }; }
        current.items.push({ label: m[1].trim(), value: m[2] });
        return;
      }
      if (!current || current.type !== 'paragraph') { flush(); current = { type: 'paragraph', text: line }; }
      else current.text = joinText(current.text, line);
    });
    flush();
    return blocks;
  }

  // ------------------------------------------------------------ full doc

  function truncate(text, max) {
    return text.length > max ? text.slice(0, max - 1).trim() + '…' : text;
  }

  /** Pull the first heading out as the section title, if there is one. */
  function titleSection(section, index) {
    const first = section.blocks[0];
    if (first && first.type === 'heading') {
      section.title = truncate(first.text, 70);
      section.blocks = section.blocks.slice(1);
    } else {
      section.title = 'Screenshot ' + (index + 1);
    }
  }

  function recomputeSection(section) {
    section.wordCount = countWords(section.blocks);
    return section;
  }

  /**
   * Build a document from several OCR'd pages.
   * @param {Array} pages  [{ fileName, data, imageHeight }]
   * @param {object} opts  { title, layout: 'per-screenshot'|'combined',
   *                         removeOverlap, ignoreStatusBar }
   */
  function organize(pages, opts) {
    opts = Object.assign({ layout: 'per-screenshot', removeOverlap: true, ignoreStatusBar: true }, opts);
    const pageLines = pages.map((p) => extractLines(p.data, { imageHeight: p.imageHeight, ignoreStatusBar: opts.ignoreStatusBar }));

    const overlaps = pageLines.map(() => 0);
    if (opts.removeOverlap) {
      for (let i = 1; i < pageLines.length; i++) {
        const o = findOverlap(pageLines[i - 1], pageLines[i]);
        if (o.dropCur) {
          pageLines[i] = pageLines[i].slice(o.dropCur);
          if (o.dropPrev) pageLines[i - 1] = pageLines[i - 1].slice(0, -o.dropPrev);
          overlaps[i] = o.dropCur;
        }
      }
    }

    let sections;
    if (opts.layout === 'combined') {
      const blocks = [];
      pageLines.forEach((lines) => blocks.push(...buildBlocks(lines)));
      sections = [{ id: 's1', title: '', sourceFiles: pages.map((p) => p.fileName), blocks }];
      const first = blocks[0];
      if (first && first.type === 'heading' && !opts.title) {
        sections[0].title = truncate(first.text, 70);
        sections[0].blocks = blocks.slice(1);
      } else {
        sections[0].title = 'Content';
      }
      sections[0].removedLines = overlaps.reduce((a, b) => a + b, 0);
    } else {
      sections = pageLines.map((lines, i) => {
        const s = { id: 's' + (i + 1), sourceFiles: [pages[i].fileName], blocks: buildBlocks(lines), removedLines: overlaps[i] };
        titleSection(s, i);
        return s;
      });
      // A scrolling capture without its own heading continues the previous one.
      sections.forEach((s, i) => {
        if (i > 0 && s.removedLines && /^Screenshot \d+$/.test(s.title)) {
          s.title = sections[i - 1].title.replace(/ \(continued\)$/, '') + ' (continued)';
        }
      });
    }

    sections.forEach((s, i) => {
      const conf = pageLines[i] && opts.layout !== 'combined' ? pageLines[i] : [].concat(...pageLines);
      s.confidence = conf.length ? Math.round(conf.reduce((a, l) => a + l.confidence, 0) / conf.length) : 0;
      recomputeSection(s);
    });

    const doc = {
      title: opts.title || guessTitle(sections),
      createdAt: new Date().toISOString(),
      sections,
    };
    refreshHighlights(doc);
    return doc;
  }

  function guessTitle(sections) {
    const named = sections.find((s) => !/^Screenshot \d+$|^Content$/.test(s.title));
    return named && sections.length === 1 ? named.title : 'Notes from My Screenshots';
  }

  /** Recalculate key details after the user edits a section. */
  function refreshHighlights(doc) {
    const entries = [];
    doc.sections.forEach((s, idx) => {
      entries.push({ text: s.title, source: idx });
      blockTexts(s.blocks).forEach((t) => entries.push({ text: t, source: idx }));
    });
    doc.highlights = extractHighlights(entries);
    doc.sections.forEach(recomputeSection);
    doc.wordCount = doc.sections.reduce((a, s) => a + s.wordCount, 0);
    return doc;
  }

  return {
    organize,
    extractLines,
    buildBlocks,
    findOverlap,
    extractHighlights,
    refreshHighlights,
    toMarkup,
    parseMarkup,
    classifyLine,
    blockTexts,
  };
});
