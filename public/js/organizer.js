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
      .replace(/'{2,}/g, "'")
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

  function capSamples(line) {
    let n = 0;
    (line.words || []).forEach((w) => (w.symbols || []).forEach((sy) => { if (sy.bbox && CAP_CHARS.test(sy.text)) n++; }));
    return n;
  }

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
  const MARKER_TOKEN = /^(?:[•●○◦▪▫■◆◇►▸‣⁃∙·*+>«»©®oe¢c\-–—]|\[.?\]?|\]|\[1|LJ|☐|□|☑|☒|✅|✓|✔)$/;
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

  // App buttons and labels that are not part of the content.
  const UI_PHRASES = /^(?:reply|replies|like|likes|love|share|send|comment|comments|follow|following|message|translate|see more|see less|see translation|most relevant|newest|all comments|view (?:all |more )?(?:\d+ )?(?:more )?(?:replies|reply|comments?)|write a (?:comment|reply)|add a comment|type a message|imessage|text message|delivered|read|seen)$/i;

  // Text that social/video apps and browsers draw over the content.
  const OVERLAY_PATTERNS = [
    /^\S+\.[a-z]{2,}\s*[—–-]\s*private$/i, // "naca.com — Private" (in-app browser)
    /^follow (?:and|&|\+) (?:comment|share|like)\b/i, // "Follow and Comment "EIN" to get…"
    /^(?:add a comment|write a comment|comment as)\b/i,
    /^\d+(?:[.,]\d+)?[km]?\s*(?:likes?|comments?|shares?|views?|replies)$/i, // "14K likes"
  ];

  // ------------------------------------------------------- OCR -> lines

  function wordText(w) {
    return cleanText(w && w.text);
  }

  /**
   * Flatten Tesseract output into an ordered list of lines with metrics.
   * Unreliable text is dropped here: words read from photos and icons have
   * low confidence, so lines made mostly of those are skipped and weak words
   * at the start or end of a line (an avatar or icon next to text) are trimmed.
   * @param {object} data   `data` from worker.recognize(..., {blocks: true})
   * @param {object} opts   { imageHeight, imageWidth, ignoreStatusBar, page }
   */
  function extractLines(data, opts) {
    opts = opts || {};
    const H = opts.imageHeight || 0;
    const W = opts.imageWidth || 0;
    const phoneShaped = H && W && H >= W * 1.6;
    const skipChrome = opts.ignoreStatusBar !== false;
    const lines = [];
    let blockId = 0;
    let paraId = 0;
    (data && data.blocks || []).forEach((block) => {
      blockId += 1;
      (block.paragraphs || []).forEach((para) => {
        paraId += 1;
        (para.lines || []).forEach((line) => {
          const skip = opts.skipWords;
          const all = (line.words || []).filter((w) => wordText(w));
          const dropped = (w) => (skip && skip.has(w)) || (typeof w.confidence === 'number' && w.confidence < 35);
          let words = all.filter((w) => !dropped(w));
          // The start of the line was hidden under an app overlay.
          if (all.length && skip && skip.has(all[0]) && words.length) words.unshift({ text: '…', confidence: 99, bbox: all[0].bbox, gap: true });
          // Mark where covered or unreadable words were taken out of a line.
          all.forEach((w, i) => {
            if (dropped(w) && alnumCount(w.text) >= 3 && i > 0 && i < all.length - 1 && !dropped(all[i - 1])) {
              const next = words.indexOf(all.slice(i + 1).find((x) => !dropped(x)));
              if (next > 0) words.splice(next, 0, { text: '…', confidence: 99, bbox: w.bbox, gap: true });
            }
          });
          // Line was entirely an app overlay ("English", "Follow") → skip it.
          if (all.length && all.every((w) => skip && skip.has(w))) return;
          if (all.length && !words.length) return;
          // A capital I is often read as "|" ("| will bring…").
          words = words.map((w, i) => (wordText(w) === '|' && words[i + 1] && /^[a-z']/.test(wordText(words[i + 1])) ?
            Object.assign({}, w, { text: 'I', confidence: Math.max(w.confidence || 0, 85) }) : w));
          if (words.length && words.every((w) => typeof w.confidence === 'number')) {
            // Mostly unreadable → a photo, drawing or icon row, not text.
            if (median(words.map((w) => w.confidence)) < 60) return;
            const weak = (w) => !w.gap && (w.confidence < 60 || alnumCount(w.text) === 0);
            // Keep bullets and checkboxes at the start; drop other weak marks (icons, avatars).
            let cutWord = false;
            while (words.length && weak(words[0]) && !MARKER_TOKEN.test(wordText(words[0]))) {
              if (alnumCount(words[0].text) >= 4) cutWord = true; // a real word, partly hidden
              words.shift();
            }
            if (cutWord && line.confidence >= 60 && words.length && !words[0].gap) words.unshift({ text: '…', confidence: 99, bbox: words[0].bbox, gap: true });
            while (words.length && weak(words[words.length - 1])) words.pop();
            if (!words.length) return;
          } else {
            words = [];
          }
          // Symbol-only "words" in the middle are usually icons (a verified badge, an emoji).
          if (words.length) {
            words = words.filter((w, i) => i === 0 || i === words.length - 1 || w.confidence >= 85 || w.gap ||
              /[\p{L}\p{N}&\-–—/+=:%$]/u.test(w.text) || (i === 1 && MARKER_TOKEN.test(wordText(w))));
          }
          const text = words.length ? cleanText(words.map(wordText).join(' ')) : cleanText(line.text);
          if (!text) return;
          const alnum = alnumCount(text);
          // Drop OCR noise: icons, separators, specks.
          if (alnum === 0) return;
          if (alnum === 1 && !/^\d$/.test(text)) return; // a lone letter is an icon (🔍 → "Q")
          if (alnum / text.replace(/\s/g, '').length < 0.4 && alnum < 4) return;
          if (line.confidence < 45 && alnum < 6) return;
          if (line.confidence < 25) return;

          const lb = line.bbox || { x0: 0, y0: 0, x1: 0, y1: 0 };
          const bbox = words.length && words[0].bbox ?
            { x0: words[0].bbox.x0, y0: lb.y0, x1: words[words.length - 1].bbox.x1, y1: lb.y1 } :
            { x0: lb.x0, y0: lb.y0, x1: lb.x1, y1: lb.y1 };

          // A line cut in half by the top or bottom edge of the screenshot.
          if (H && (bbox.y0 <= 2 || bbox.y1 >= H - 2) && line.confidence < 70) return;

          if (skipChrome) {
            // Phone status bar (clock, battery) and navigation bar.
            if (phoneShaped && bbox.y1 < H * 0.045) return;
            if (phoneShaped && bbox.y0 > H * 0.955 && text.length < 30) return;
            if (H && bbox.y1 < H * 0.06 && text.length < 30 && STATUS_TIME.test(text)) return;
            if (UI_PHRASES.test(text.replace(/[\s.…:!]+$/, ''))) return;
            if (OVERLAY_PATTERNS.some((re) => re.test(text))) return;
          }

          const size = capHeight(line);
          const sizeSamples = capSamples(line);
          const first = words[0];
          lines.push({
            text,
            norm: normalize(text),
            bbox,
            size,
            sizeSamples,
            firstWordWidth: first && first.bbox ? first.bbox.x1 - first.bbox.x0 : (text.split(' ')[0].length * size * 0.7),
            confidence: line.confidence,
            blockId: (opts.page || 0) + ':' + blockId,
            paraId: (opts.page || 0) + ':' + paraId,
          });
        });
      });
    });
    return lines;
  }

  // ------------------------------------------------ fixed app overlays

  /**
   * Words drawn by the app on top of the content (a video's caption, a
   * "Follow" button, a language pop-up) sit at exactly the same spot in
   * every screenshot, while the content under them moves. Returns the set
   * of such word objects so they can be skipped. Pairs of screenshots that
   * are mostly identical (nothing moved) are ignored.
   */
  function findOverlayWords(pages) {
    const pageWords = pages.map((p) => {
      const out = [];
      (p.data && p.data.blocks || []).forEach((b) => (b.paragraphs || []).forEach((pa) => (pa.lines || []).forEach((l) => (l.words || []).forEach((w) => {
        const norm = normalize(w.text);
        if (norm.length >= 2 && w.bbox) out.push({ w, norm, cx: (w.bbox.x0 + w.bbox.x1) / 2, cy: (w.bbox.y0 + w.bbox.y1) / 2 });
      }))));
      return out;
    });
    const overlay = new Set();
    for (let i = 0; i < pages.length; i++) {
      for (let j = i + 1; j < pages.length; j++) {
        if (!pages[i].imageHeight || pages[i].imageHeight !== pages[j].imageHeight || pages[i].imageWidth !== pages[j].imageWidth) continue;
        const tol = Math.max(3, pages[i].imageHeight * 0.0025);
        const shared = [];
        pageWords[i].forEach((a) => {
          const b = pageWords[j].find((c) => c.norm === a.norm && Math.abs(c.cx - a.cx) <= tol * 2 && Math.abs(c.cy - a.cy) <= tol);
          if (b) shared.push(a.w, b.w);
        });
        const smaller = Math.min(pageWords[i].length, pageWords[j].length) || 1;
        if (shared.length / 2 < smaller * 0.5) shared.forEach((w) => overlay.add(w));
      }
    }
    // An overlay seen twice may have been read in other screenshots too,
    // slightly moved (a pop-up that shifts): catch those as well.
    const known = [];
    pageWords.forEach((ws) => ws.forEach((x) => { if (overlay.has(x.w)) known.push(x); }));
    pages.forEach((p, i) => {
      if (!p.imageHeight) return;
      pageWords[i].forEach((x) => {
        if (known.some((k) => k.norm === x.norm && Math.abs(k.cy - x.cy) <= p.imageHeight * 0.05 && Math.abs(k.cx - x.cx) <= (p.imageWidth || 0) * 0.03)) overlay.add(x.w);
      });
    });
    return overlay;
  }

  // ------------------------------------------------ scrolling screenshots

  function bigrams(s) {
    const map = new Map();
    for (let i = 0; i < s.length - 1; i++) {
      const g = s.substr(i, 2);
      map.set(g, (map.get(g) || 0) + 1);
    }
    return map;
  }

  /** How alike two normalised strings are, 0..1 (Dice coefficient). */
  function similarity(a, b) {
    if (a === b) return 1;
    if (a.length < 2 || b.length < 2) return 0;
    const A = bigrams(a);
    const B = bigrams(b);
    let inter = 0;
    A.forEach((n, g) => { if (B.has(g)) inter += Math.min(n, B.get(g)); });
    return (2 * inter) / (a.length + b.length - 2);
  }

  /** Pairs of lines with (nearly) the same text in two screenshots. */
  function matchLines(A, B) {
    const used = new Set();
    const matches = [];
    B.forEach((b) => {
      if (b.norm.length < 6) return;
      // The same pixels give (almost) the same text, so be strict: similar
      // but different lines ("1 cup shredded … cheese") must not pair up.
      let best = A.find((a) => !used.has(a) && a.norm === b.norm) || null;
      let bestSim = 0.92;
      if (!best) {
        A.forEach((a) => {
          if (used.has(a) || Math.abs(a.norm.length - b.norm.length) > Math.max(a.norm.length, b.norm.length) * 0.2) return;
          const sim = similarity(a.norm, b.norm);
          if (sim >= bestSim) { best = a; bestSim = sim; }
        });
      }
      if (best) {
        used.add(best);
        matches.push({ a: best, b, dy: best.bbox.y0 - b.bbox.y0 });
      }
    });
    return matches;
  }

  /**
   * Work out which screenshots are parts of the same scrolled page.
   * Two screenshots are linked when several lines appear in both, shifted by
   * the same distance. Lines that appear in both at the *same* place are
   * fixed parts of the app (a header, a "Write a comment" bar) and are
   * removed. Returns groups of page indexes in reading order, and each
   * page's position on the long page.
   */
  function findScrollGroups(pageLines, heights, autoOrder) {
    const n = pageLines.length;
    const parent = pageLines.map((_, i) => i);
    const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])));
    const links = [];
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const matches = matchLines(pageLines[i], pageLines[j]);
        if (!matches.length) continue;
        const tol = Math.max(4, Math.max(heights[i] || 0, heights[j] || 0) * 0.012);
        const moved = matches.filter((m) => Math.abs(m.dy) > tol);
        if (!moved.length) continue;
        const dy = median(moved.map((m) => m.dy));
        const consistent = moved.filter((m) => Math.abs(m.dy - dy) <= tol * 2);
        const chars = consistent.reduce((a, m) => a + m.b.norm.length, 0);
        if (consistent.length < 2 && chars < 25) continue;
        links.push({ i, j, dy });
        parent[find(j)] = find(i);
        // Same text at the same spot in both = part of the app, not the content.
        matches.filter((m) => Math.abs(m.dy) <= tol).forEach((m) => { m.a.chrome = true; m.b.chrome = true; });
      }
    }

    // Position of every page on its long page (page top, in pixels).
    const pos = pageLines.map(() => null);
    for (let start = 0; start < n; start++) {
      if (pos[start] !== null) continue;
      pos[start] = 0;
      const queue = [start];
      while (queue.length) {
        const k = queue.shift();
        links.forEach((l) => {
          if (l.i === k && pos[l.j] === null) { pos[l.j] = pos[k] + l.dy; queue.push(l.j); }
          else if (l.j === k && pos[l.i] === null) { pos[l.i] = pos[k] - l.dy; queue.push(l.i); }
        });
      }
    }

    const groups = [];
    const byRoot = new Map();
    for (let i = 0; i < n; i++) {
      const r = find(i);
      if (!byRoot.has(r)) { byRoot.set(r, []); groups.push(byRoot.get(r)); }
      byRoot.get(r).push(i);
    }
    if (autoOrder) groups.forEach((g) => g.sort((a, b) => pos[a] - pos[b]));
    return { groups, pos };
  }

  /**
   * Remove text a page repeats from the pages before it (same text at the
   * same place on the long page). Where two different lines land on the same
   * spot, the one read more confidently wins (usually the one that was not
   * cut off by the edge of the screen).
   * @param earlier  [{ line, y }] lines of earlier pages, y on the long page
   * @param cur      lines of this page
   * @param shift    this page's position on the long page
   */
  function removeRepeats(earlier, cur, shift) {
    let removed = 0;
    const kept = [];
    cur.forEach((line) => {
      const y = line.bbox.y0 + shift;
      const tol = Math.max(8, (line.bbox.y1 - line.bbox.y0) * 0.7);
      const same = earlier.filter((e) => Math.abs(e.y - y) <= tol && e.line.bbox.x0 < line.bbox.x1 && line.bbox.x0 < e.line.bbox.x1);
      if (!same.length) { kept.push(line); return; }
      const p = same.reduce((best, c) => (similarity(c.line.norm, line.norm) > similarity(best.line.norm, line.norm) ? c : best)).line;
      const sim = similarity(p.norm, line.norm);
      if (sim >= 0.6) removed++;
      if (line.confidence > p.confidence + 5 || (sim >= 0.6 && line.norm.length > p.norm.length + 2 && line.confidence >= p.confidence - 5)) {
        Object.assign(p, {
          text: line.text, norm: line.norm, confidence: line.confidence, firstWordWidth: line.firstWordWidth,
          size: line.size, sizeSamples: line.sizeSamples,
          bbox: Object.assign({}, p.bbox, { x0: line.bbox.x0, x1: line.bbox.x1 }),
        });
      }
    });
    return { kept, removed };
  }

  // "Luke Brown · 8w · Author" — who posted a comment or message, and when.
  const BYLINE = /^([\p{L}][\p{L} .'’-]{1,40}?)\s*(?:[&@©®✓✔*]\s*)?[-·•|]\s*(\d{1,3}\s?(?:s|m|h|d|w|y|mo|min|mins|hr|hrs|wk|wks|yr|yrs)|just now|yesterday)\b(.*)$/iu;

  /** Tidy post/comment bylines; drop a byline left dangling at the very end. */
  function tidyBylines(blocks) {
    blocks.forEach((b) => {
      if (b.type !== 'paragraph') return;
      const m = b.text.match(BYLINE);
      if (!m) return;
      const extra = m[3].split(/\s*[-·•|]\s*/).map((t) => t.trim()).filter(Boolean);
      b.text = [m[1].trim(), m[2].trim()].concat(extra).join(' · ');
      b.byline = true;
    });
    while (blocks.length && blocks[blocks.length - 1].byline) blocks.pop();
    return blocks;
  }

  // ------------------------------------------------------------ chats

  /** Where a line sits across the screen: 'left', 'right' or 'center'. */
  function lineSide(l, W) {
    const mid = (l.bbox.x0 + l.bbox.x1) / 2;
    if (l.bbox.x0 > W * 0.2 && l.bbox.x1 < W * 0.8 && Math.abs(mid - W / 2) < W * 0.06) return 'center';
    if (l.bbox.x0 < W * 0.15) return 'left';
    if (l.bbox.x0 > W * 0.18) return 'right';
    return 'left';
  }

  /**
   * A chat screenshot has message bubbles on both sides: at least two
   * separate text areas hugging the left edge and two hugging the right.
   */
  function looksLikeChat(lines, W) {
    if (!W || lines.length < 4) return false;
    const blocks = { left: new Set(), right: new Set() };
    let rightEdge = false;
    let left = 0;
    let leftShort = 0;
    lines.forEach((l) => {
      const side = lineSide(l, W);
      if (side === 'left') {
        left++;
        blocks.left.add(l.blockId);
        // A bubble on the left never stretches across most of the screen.
        if (l.bbox.x1 < W * 0.8) leftShort++;
      }
      // A bubble on the right starts well away from the left edge.
      if (side === 'right' && l.bbox.x0 > W * 0.25) {
        blocks.right.add(l.blockId);
        if (l.bbox.x1 > W * 0.8) rightEdge = true;
      }
    });
    return rightEdge && blocks.left.size >= 2 && blocks.right.size >= 2 && leftShort >= left * 0.8;
  }

  /** The contact's name shown centered near the top of a chat, if any. */
  function chatName(lines, W, H) {
    const cand = lines.find((l) => l.bbox.y1 < (H || Infinity) * 0.25 && lineSide(l, W) === 'center' &&
      /^\p{Lu}[\p{L}'’.-]*(?: [\p{L}'’.-]+){0,3}$/u.test(l.text.replace(/\s*[›>»]$/, '')) && !STATUS_TIME.test(l.text));
    return cand ? cand.text.replace(/\s*[›>»]$/, '') : null;
  }

  /**
   * Chat screenshot → a transcript: "Name: message" for each bubble.
   * Left bubbles are the other person, right bubbles are "Me".
   */
  function buildChatBlocks(lines, W, H) {
    const name = chatName(lines, W, H);
    const blocks = [];
    let current = null;
    let prev = null;
    lines.forEach((l) => {
      if (name && l.text.replace(/\s*[›>»]$/, '') === name && lineSide(l, W) === 'center') return;
      const side = lineSide(l, W);
      if (side === 'center') {
        // Timestamps and notes such as "Today 2:14 PM" separate the messages.
        current = null;
        blocks.push({ type: 'paragraph', text: l.text, note: true });
        prev = l;
        return;
      }
      const who = side === 'right' ? 'Me' : (name || 'Them');
      // Lines of one bubble are tightly spaced; bubbles have padding between them.
      const sameBubble = prev && current && lineSide(prev, W) === side &&
        l.bbox.y0 - prev.bbox.y1 < (prev.bbox.y1 - prev.bbox.y0) * 0.9;
      if (sameBubble) {
        const item = current.items[current.items.length - 1];
        item.value = joinText(item.value, l.text);
      } else {
        if (!current) { current = { type: 'fields', chat: true, items: [] }; blocks.push(current); }
        current.items.push({ label: who, value: l.text });
      }
      prev = l;
    });
    return { blocks, name };
  }

  // ------------------------------------------------------- lines -> blocks

  function joinText(a, b) {
    // Screens rarely split words, so a line ending in "-" is a real hyphen
    // ("four-" + "family" → "four-family").
    if (/[\p{L}\d]-$/u.test(a) && /^[\p{L}\d]/u.test(b)) return a + b;
    return a + ' ' + b;
  }

  function isListMarker(text) {
    const k = classifyLine(text).kind;
    return k === 'bullet' || k === 'ordered' || k === 'check';
  }

  /**
   * Group lines into document blocks using font size, spacing, line
   * endings and line prefixes. Lines must be in reading order with
   * y positions on one shared scale.
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
      if (g >= 0 && !lines[i].pageBreak) gaps.push(g);
    }
    const typicalGap = median(gaps) || bodySize * 0.6;
    const pageWidth = Math.max(...lines.map((l) => l.bbox.x1));
    const colTol = Math.max(12, pageWidth * 0.03);
    const charWidth = median(lines.map((l) => (l.bbox.x1 - l.bbox.x0) / Math.max(1, l.text.length))) || bodySize * 0.6;
    const leftMost = Math.min(...lines.map((l) => l.bbox.x0));
    // Right edge of the text column a line belongs to, or null when the
    // column doesn't look like wrapped text (it must be reasonably wide and
    // at least two lines must reach its edge).
    const rightEdge = (line) => {
      const col = lines.filter((l) => Math.abs(l.bbox.x0 - line.bbox.x0) <= colTol && l.size < bodySize * 1.2);
      if (!col.length) return null;
      const edge = Math.max(...col.map((l) => l.bbox.x1));
      const reaching = col.filter((l) => l.bbox.x1 >= edge - charWidth * 8).length;
      if (reaching < 3 || (edge - line.bbox.x0) / charWidth < 24) return null;
      return edge;
    };

    // 1. Join lines that wrapped onto the next line into "logical" lines.
    const logical = [];
    lines.forEach((line, idx) => {
      const prev = idx > 0 ? lines[idx - 1] : null;
      const gap = prev ? line.bbox.y0 - prev.bbox.y1 : 0;
      const bigGap = !prev || line.pageBreak || gap > Math.max(typicalGap * 1.8, bodySize * 1.1) || gap < -bodySize * 2;
      const last = logical[logical.length - 1];
      const space = bodySize * 0.6;
      const wrapped = prev && !bigGap && last && !last.heading &&
        // (size estimates from only a couple of tall letters are unreliable)
        (line.size < prev.size + bodySize * 0.2 || (line.sizeSamples || 9) < 3 || (prev.sizeSamples || 9) < 3) &&
        !isAllCapsLabel(line.text) &&
        !isListMarker(line.text) && classifyLine(line.text).kind !== 'field' &&
        (Math.abs(line.bbox.x0 - prev.bbox.x0) <= colTol || (last.marker && line.bbox.x0 > last.x0) ||
          // starts further right but lowercase: its beginning was covered or cut off
          (line.bbox.x0 > prev.bbox.x0 && /^[a-z…]/.test(line.text))) &&
        (() => {
          // A bullet's continuation lines line up with the bullet's text, not the bullet.
          const edge = rightEdge(last.marker && line.bbox.x0 > prev.bbox.x0 && Math.abs(line.bbox.x0 - prev.bbox.x0) < charWidth * 6 ? line : prev);
          return edge !== null && edge - prev.bbox.x1 < line.firstWordWidth + space;
        })();
      // Two lines centered on each other (a button or title spread over two lines).
      const centered = prev && !bigGap && last && !last.marker && !isListMarker(line.text) &&
        Math.abs(line.size - prev.size) < bodySize * 0.25 &&
        prev.bbox.x0 > leftMost + charWidth * 3 && line.bbox.x0 > leftMost + charWidth * 3 &&
        Math.abs((line.bbox.x0 + line.bbox.x1) / 2 - (prev.bbox.x0 + prev.bbox.x1) / 2) < charWidth * 1.5 &&
        Math.abs(line.bbox.x0 - prev.bbox.x0) > charWidth * 1.5 &&
        line.bbox.y0 - prev.bbox.y1 < (prev.bbox.y1 - prev.bbox.y0) * 0.8;
      if (centered && !wrapped && last.heading) last.heading = 0;
      if (wrapped || centered) {
        last.text = joinText(last.text, line.text);
        last.lastY1 = line.bbox.y1;
        last.x1 = Math.max(last.x1, line.bbox.x1);
        last.lines += 1;
        return;
      }
      const ratio = line.size / bodySize;
      let heading = 0;
      if (classifyLine(line.text).kind === 'text' && line.text.length <= 90 && !/[.,;]$/.test(line.text)) {
        if (ratio >= 1.65) heading = 1;
        else if (ratio >= 1.25) heading = 2;
        else if (isAllCapsLabel(line.text) || (ratio >= 1.12 && isShortLabel(line.text))) heading = 3;
      }
      logical.push({
        text: line.text,
        heading,
        marker: isListMarker(line.text),
        x0: line.bbox.x0,
        x1: line.bbox.x1,
        lines: 1,
        bigGap,
        newBlock: prev && prev.blockId !== line.blockId && gap > typicalGap * 1.3,
        blockId: line.blockId,
        lastY1: line.bbox.y1,
      });
    });

    // 2. Turn logical lines into headings, lists, fields and paragraphs.
    const blocks = [];
    let current = null;
    let run = []; // consecutive plain lines, decided on when the run ends
    const flush = () => { if (current) blocks.push(current); current = null; };
    const flushRun = () => {
      if (!run.length) return;
      // A list: several lines that stop well short of the column's width
      // (a paragraph whose lines weren't joined would fill the width).
      const colRight = Math.max(...run.map((l) => l.x1));
      const short = run.filter((l) => l.text.length <= 80).length;
      const full = run.filter((l) => l.lines === 1 && l.x1 >= colRight - charWidth * 3).length;
      if (run.length >= 3 && short >= run.length * 0.66 && full < run.length * 0.6) {
        blocks.push({ type: 'list', style: 'bullet', items: run.map((l) => ({ text: l.text })) });
      } else {
        run.forEach((l) => blocks.push({ type: 'paragraph', text: l.text, _x0: l.x0 }));
      }
      run = [];
    };

    logical.forEach((l, idx) => {
      const prevL = idx > 0 ? logical[idx - 1] : null;
      const breakBefore = l.bigGap || l.newBlock;
      if (l.heading) {
        flush(); flushRun();
        const last = blocks[blocks.length - 1];
        if (last && last.type === 'heading' && last.level === l.heading && prevL && prevL.heading === l.heading && !breakBefore) {
          last.text = joinText(last.text, l.text); // heading wrapped onto 2 lines
        } else {
          blocks.push({ type: 'heading', level: l.heading, text: l.text });
        }
        return;
      }
      const c = classifyLine(l.text);
      if (c.kind === 'bullet' || c.kind === 'ordered' || c.kind === 'check') {
        flushRun();
        const listType = c.kind === 'check' ? 'checklist' : c.kind === 'ordered' ? 'ordered' : 'bullet';
        if (!current || current.type !== 'list' || current.style !== listType || l.bigGap) {
          flush();
          current = { type: 'list', style: listType, items: [] };
        }
        const item = { text: c.text };
        if (listType === 'checklist') item.checked = c.checked;
        if (listType === 'ordered') item.number = c.number;
        current.items.push(item);
        return;
      }
      if (c.kind === 'field') {
        flushRun();
        if (!current || current.type !== 'fields' || l.bigGap) {
          flush();
          current = { type: 'fields', items: [] };
        }
        current.items.push({ label: c.label, value: c.text });
        return;
      }
      flush();
      if (breakBefore) flushRun();
      run.push(l);
    });
    flush();
    flushRun();

    // 3. Paragraphs indented from the page's text margin, one after another,
    //    are list items whose bullet symbol wasn't readable.
    const margins = lines.map((l) => l.bbox.x0).sort((a, b) => a - b);
    const margin = margins.find((x) => margins.filter((y) => Math.abs(y - x) <= colTol).length >= 2);
    const indented = (b) => b.type === 'paragraph' && margin !== undefined && b._x0 > margin + charWidth * 2.5 && b._x0 < margin + charWidth * 12;
    const out = [];
    for (let i = 0; i < blocks.length; i++) {
      const prevOut = out[out.length - 1];
      // A bullet whose symbol wasn't read, right after the rest of its list.
      if (indented(blocks[i]) && prevOut && prevOut.type === 'list' && prevOut.style === 'bullet') {
        prevOut.items.push({ text: blocks[i].text });
        continue;
      }
      // Two pieces of the same kind of list with nothing between them.
      if (blocks[i].type === 'list' && prevOut && prevOut.type === 'list' && prevOut.style === blocks[i].style) {
        prevOut.items.push(...blocks[i].items);
        continue;
      }
      if (indented(blocks[i]) && indented(blocks[i + 1] || {})) {
        const list = { type: 'list', style: 'bullet', items: [] };
        while (i < blocks.length && indented(blocks[i])) list.items.push({ text: blocks[i++].text });
        i--;
        out.push(list);
      } else {
        out.push(blocks[i]);
      }
    }
    out.forEach((b) => { delete b._x0; });
    return out;
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
        /(?:[$€£¥]\s?\d[\d,]*(?:\.\d{1,2})?)(?!\d)(?:\s?(?:thousand|million|billion|trillion|[KMB]\b))?/gi,
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

  /**
   * Name a section: its first heading if it starts with one (the heading is
   * then removed from the body), otherwise the first sentence of its text.
   */
  function titleSection(section, index, fixedTitle) {
    const first = section.blocks[0];
    if (fixedTitle) {
      section.title = fixedTitle;
    } else if (first && first.type === 'heading') {
      section.title = truncate(first.text, 70);
      section.blocks = section.blocks.slice(1);
    } else {
      // Otherwise: if the text starts properly, its first sentence; if it starts
      // mid-sentence (the middle of a page), the first heading further down.
      const body = section.blocks.filter((b) => !b.byline && !b.note);
      const sentence = (t) => /^[\p{Lu}\d"']/u.test(t) && (t.match(/[\p{L}\p{N}]+/gu) || []).length >= 5;
      const firstText = blockTexts(body.slice(0, 1))[0] || '';
      const heading = section.blocks.find((b) => b.type === 'heading');
      const text = blockTexts(body).find(sentence);
      if (heading && !sentence(firstText)) section.title = truncate(heading.text, 70);
      else section.title = text ? shortTitle(text) : heading ? truncate(heading.text, 70) : 'Screenshot ' + (index + 1);
    }
  }

  /** First sentence of a text, cut at a word boundary to about 60 characters. */
  function shortTitle(text) {
    const sentences = text.split(/(?<=[.!?])\s/);
    let t = (sentences.find((x) => x.split(/\s+/).length >= 4) || text).replace(/[.!?:;,]+$/, '');
    // "Cheesy BBQ Chicken sliders are a super easy dinner" → "Cheesy BBQ Chicken sliders"
    const subject = t.match(/^(\S+(?:\s+\S+){1,6}?)\s+(?:is|are|was|were|makes?|has|have)\s/i);
    if (subject && /^\p{Lu}/u.test(subject[1]) && t.split(/\s+/).length >= 9) return subject[1];
    if (t.length > 60) t = t.slice(0, 60).replace(/\s+\S*$/, '') + '…';
    return t;
  }

  function recomputeSection(section) {
    section.wordCount = countWords(section.blocks);
    return section;
  }

  /**
   * Build a document from several OCR'd pages.
   * @param {Array} pages  [{ fileName, data, imageHeight, imageWidth }]
   * @param {object} opts  { title, layout: 'per-screenshot'|'combined',
   *                         removeOverlap, ignoreStatusBar, autoOrder }
   *   per-screenshot: one section per screenshot, except screenshots of the
   *                   same scrolled page, which are joined into one section.
   *   combined:       everything in one section.
   */
  function organize(pages, opts) {
    opts = Object.assign({ layout: 'per-screenshot', removeOverlap: true, ignoreStatusBar: true, autoOrder: true }, opts);
    const skipWords = opts.ignoreStatusBar !== false && pages.length > 1 ? findOverlayWords(pages) : null;
    const pageLines = pages.map((p, i) => extractLines(p.data, {
      imageHeight: p.imageHeight, imageWidth: p.imageWidth, ignoreStatusBar: opts.ignoreStatusBar, page: i, skipWords,
    }));

    let groups = pages.map((_, i) => [i]);
    let pos = pages.map(() => 0);
    const removed = pages.map(() => 0);
    if (opts.removeOverlap && pages.length > 1) {
      const found = findScrollGroups(pageLines, pages.map((p) => p.imageHeight), opts.autoOrder);
      groups = found.groups;
      pos = found.pos;
      if (opts.ignoreStatusBar !== false) {
        pageLines.forEach((lines, i) => { pageLines[i] = lines.filter((l) => !l.chrome); });
      }
      groups.forEach((g) => {
        const earlier = [];
        g.forEach((pi, k) => {
          const all = pageLines[pi];
          if (k > 0) {
            const r = removeRepeats(earlier, all, pos[pi]);
            pageLines[pi] = r.kept;
            removed[pi] = r.removed;
          }
          all.forEach((line) => earlier.push({ line, y: line.bbox.y0 + pos[pi] }));
        });
      });
    }

    // Lines of a group of pages, in reading order on one y scale.
    const groupLines = (g) => {
      const out = [];
      g.forEach((pi) => {
        pageLines[pi].forEach((l) => out.push(Object.assign({}, l, { bbox: Object.assign({}, l.bbox, { y0: l.bbox.y0 + pos[pi], y1: l.bbox.y1 + pos[pi] }) })));
      });
      // Joined screenshots: a line only readable in the later screenshot
      // (hidden by a pop-up in the earlier one) belongs in its place on the page.
      if (g.length > 1) {
        const order = new Map(out.map((l, i) => [l, i]));
        out.sort((a, b) => (Math.abs(a.bbox.y0 - b.bbox.y0) < (a.bbox.y1 - a.bbox.y0) * 0.5 ? order.get(a) - order.get(b) : a.bbox.y0 - b.bbox.y0));
      }
      return out;
    };

    const units = opts.layout === 'combined' ? [[].concat(...groups)] : groups;
    const sections = units.map((unit, idx) => {
      let lines;
      if (opts.layout === 'combined') {
        lines = [];
        groups.forEach((g) => {
          const gl = groupLines(g);
          if (gl.length && lines.length) gl[0].pageBreak = true;
          lines.push(...gl);
        });
      } else {
        lines = groupLines(unit);
      }
      const W = pages[unit[0]].imageWidth;
      const chat = looksLikeChat(lines, W) ? buildChatBlocks(lines, W, pages[unit[0]].imageHeight) : null;
      const s = {
        id: 's' + (idx + 1),
        sourceFiles: unit.map((i) => pages[i].fileName),
        blocks: chat ? chat.blocks : tidyBylines(buildBlocks(lines)),
        removedLines: unit.reduce((a, i) => a + removed[i], 0),
        confidence: lines.length ? Math.round(lines.reduce((a, l) => a + l.confidence, 0) / lines.length) : 0,
      };
      const chatTitle = chat ? (chat.name ? 'Conversation with ' + chat.name : 'Conversation') : null;
      titleSection(s, idx, opts.layout === 'combined' && opts.title ? opts.title : chatTitle);
      return recomputeSection(s);
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
    similarity,
    extractHighlights,
    refreshHighlights,
    toMarkup,
    parseMarkup,
    classifyLine,
    blockTexts,
  };
});
