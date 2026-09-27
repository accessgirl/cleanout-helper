/*
 * app.js — the user interface.
 * 1. Collect screenshots (file picker, drag & drop, paste).
 * 2. Read their text with Tesseract.js (runs locally in the browser).
 * 3. Organize the text (organizer.js) and preview / edit / download it
 *    (exporters.js).
 */
(function () {
  'use strict';

  const TESSERACT_VERSION = '7.0.0';
  const DOCX_VERSION = '9';
  const CDN = {
    tesseract: 'https://cdn.jsdelivr.net/npm/tesseract.js@' + TESSERACT_VERSION + '/dist/tesseract.min.js',
    docx: 'https://cdn.jsdelivr.net/npm/docx@' + DOCX_VERSION + '/dist/index.iife.js',
  };
  const LOCAL = {
    tesseract: 'vendor/tesseract/tesseract.min.js',
    worker: 'vendor/tesseract/worker.min.js',
    core: 'vendor/tesseract-core',
    lang: 'vendor/lang',
    docx: 'vendor/docx/index.iife.js',
  };

  const $ = (sel) => document.querySelector(sel);
  const els = {
    dropzone: $('#dropzone'),
    fileInput: $('#file-input'),
    thumbs: $('#thumbs'),
    toolbar: $('#list-toolbar'),
    countLabel: $('#count-label'),
    orderHint: $('#order-hint'),
    sortName: $('#sort-name'),
    clearAll: $('#clear-all'),
    title: $('#opt-title'),
    layout: $('#opt-layout'),
    lang: $('#opt-lang'),
    overlap: $('#opt-overlap'),
    statusbar: $('#opt-statusbar'),
    images: $('#opt-images'),
    keepDetails: $('#keep-details'),
    keywords: $('#opt-keywords'),
    layoutField: $('#layout-field'),
    run: $('#run'),
    runHint: $('#run-hint'),
    progress: $('#progress'),
    progressFill: $('#progress-fill'),
    progressText: $('#progress-text'),
    result: $('#step-result'),
    stats: $('#stats'),
    preview: $('#preview'),
    tabPreview: $('#tab-preview'),
    tabEdit: $('#tab-edit'),
    panelPreview: $('#panel-preview'),
    panelEdit: $('#panel-edit'),
    editTitle: $('#edit-title'),
    editSections: $('#edit-sections'),
    copyText: $('#copy-text'),
    toast: $('#toast'),
  };

  /** @type {{id:number,file:File,name:string,url:string,status:string,note:string,ocr:Object}[]} */
  let items = [];
  let nextId = 1;
  let pasteCount = 0;
  let doc = null;
  let busy = false;

  // ------------------------------------------------------------ utilities

  function toast(message, isError) {
    els.toast.textContent = message;
    els.toast.classList.toggle('error', Boolean(isError));
    els.toast.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { els.toast.hidden = true; }, isError ? 6000 : 3000);
  }

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = src;
      s.onload = () => resolve(src);
      s.onerror = () => { s.remove(); reject(new Error('Could not load ' + src)); };
      document.head.appendChild(s);
    });
  }

  /** Load a library from the local copy, falling back to the CDN. */
  const libCache = {};
  function loadLib(name, globalName) {
    if (!libCache[name]) {
      libCache[name] = (async () => {
        if (window[globalName]) return { lib: window[globalName], local: true };
        try {
          await loadScript(LOCAL[name]);
          return { lib: window[globalName], local: true };
        } catch (e) {
          await loadScript(CDN[name]);
          return { lib: window[globalName], local: false };
        }
      })().catch((e) => { delete libCache[name]; throw e; });
    }
    return libCache[name];
  }

  function debounce(fn, ms) {
    let t;
    return function () {
      clearTimeout(t);
      const args = arguments;
      t = setTimeout(() => fn.apply(null, args), ms);
    };
  }

  function download(content, fileName, type) {
    const blob = content instanceof Blob ? content : new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  }

  // ------------------------------------------------------- adding images

  function addFiles(fileList) {
    const files = Array.from(fileList || []).filter((f) => f.type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(f.name));
    const skipped = (fileList ? fileList.length : 0) - files.length;
    files.forEach((file) => {
      let name = file.name;
      if (!name || name === 'image.png') name = 'Pasted image ' + (++pasteCount) + '.png';
      items.push({ id: nextId++, file, name, url: URL.createObjectURL(file), status: 'ready', note: 'Ready', ocr: {} });
    });
    if (skipped > 0) toast(skipped + ' file(s) skipped — only images can be read.', true);
    renderThumbs();
  }

  function removeItem(id) {
    const idx = items.findIndex((i) => i.id === id);
    if (idx < 0) return;
    URL.revokeObjectURL(items[idx].url);
    items.splice(idx, 1);
    renderThumbs();
  }

  function moveItem(from, to) {
    if (to < 0 || to >= items.length || from === to) return;
    const [it] = items.splice(from, 1);
    items.splice(to, 0, it);
    renderThumbs();
  }

  function renderThumbs() {
    els.thumbs.innerHTML = '';
    items.forEach((item, idx) => {
      const li = document.createElement('li');
      li.className = 'thumb';
      li.draggable = !busy;
      li.dataset.index = idx;
      li.innerHTML =
        '<div class="img-wrap"><img alt=""></div>' +
        '<span class="num"></span>' +
        '<div class="meta"><span class="name"></span><span class="status"></span>' +
        '<div class="ctrls">' +
        '<button type="button" data-act="left" title="Move earlier" aria-label="Move earlier">◀</button>' +
        '<button type="button" data-act="right" title="Move later" aria-label="Move later">▶</button>' +
        '<button type="button" data-act="remove" title="Remove" aria-label="Remove">✕</button>' +
        '</div></div>';
      li.querySelector('img').src = item.url;
      li.querySelector('img').alt = item.name;
      li.querySelector('.num').textContent = idx + 1;
      li.querySelector('.name').textContent = item.name;
      li.querySelector('.name').title = item.name;
      const st = li.querySelector('.status');
      st.textContent = item.note;
      st.classList.add(item.status);
      const [left, right, remove] = li.querySelectorAll('.ctrls button');
      left.disabled = busy || idx === 0;
      right.disabled = busy || idx === items.length - 1;
      remove.disabled = busy;
      left.onclick = () => moveItem(idx, idx - 1);
      right.onclick = () => moveItem(idx, idx + 1);
      remove.onclick = () => removeItem(item.id);

      li.addEventListener('dragstart', (e) => {
        e.dataTransfer.setData('text/x-thumb-index', String(idx));
        e.dataTransfer.effectAllowed = 'move';
        li.classList.add('dragging');
      });
      li.addEventListener('dragend', () => li.classList.remove('dragging'));
      li.addEventListener('dragover', (e) => {
        if (!e.dataTransfer.types.includes('text/x-thumb-index')) return;
        e.preventDefault();
        li.classList.add('drop-target');
      });
      li.addEventListener('dragleave', () => li.classList.remove('drop-target'));
      li.addEventListener('drop', (e) => {
        const from = e.dataTransfer.getData('text/x-thumb-index');
        if (from === '') return;
        e.preventDefault();
        e.stopPropagation();
        moveItem(Number(from), idx);
      });
      els.thumbs.appendChild(li);
    });

    const n = items.length;
    els.toolbar.hidden = n === 0;
    els.orderHint.hidden = n < 2;
    els.countLabel.textContent = n + ' screenshot' + (n === 1 ? '' : 's');
    els.run.disabled = busy || n === 0;
    els.runHint.textContent = n === 0 ? 'Add at least one screenshot to start.' :
      doc ? 'Changed something? Click again to rebuild the document.' :
        'Reading text takes a few seconds per screenshot.';
    els.run.textContent = doc ? 'Rebuild document' : 'Read text & create document';
  }

  // -------------------------------------------------------- OCR pipeline

  let ocrWorker = null;
  let ocrWorkerLang = null;
  let progressHandler = null;

  async function getWorker(lang) {
    if (ocrWorker && ocrWorkerLang === lang) return ocrWorker;
    if (ocrWorker) { await ocrWorker.terminate(); ocrWorker = null; }
    const { lib: Tesseract, local } = await loadLib('tesseract', 'Tesseract');
    const abs = (p) => new URL(p, location.href).href;
    const options = { logger: (m) => progressHandler && progressHandler(m) };
    if (local) {
      options.workerPath = abs(LOCAL.worker);
      options.corePath = abs(LOCAL.core);
      if (lang === 'eng') options.langPath = abs(LOCAL.lang);
    }
    ocrWorker = await Tesseract.createWorker(lang, 1, options);
    // Automatic page layout: finds separate text areas (chat bubbles,
    // captions, columns) instead of treating the screenshot as one block.
    await ocrWorker.setParameters({ tessedit_pageseg_mode: '3' });
    ocrWorkerLang = lang;
    return ocrWorker;
  }

  /**
   * Prepare an image for OCR: enlarge small screenshots and flip dark-mode
   * screenshots (light text on dark background) to dark-on-light.
   */
  async function prepareImage(file) {
    const bitmap = await createImageBitmap(file);
    let scale = 1;
    if (bitmap.width < 1000) scale = Math.min(2.5, 1600 / bitmap.width);
    const maxSide = Math.max(bitmap.width, bitmap.height) * scale;
    if (maxSide > 5000) scale *= 5000 / maxSide;
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.imageSmoothingQuality = 'high';
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    if (bitmap.close) bitmap.close();

    const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const px = img.data;
    let sum = 0;
    let count = 0;
    for (let i = 0; i < px.length; i += 4 * 16) {
      sum += 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];
      count++;
    }
    const dark = count && sum / count < 110;
    if (dark) {
      for (let i = 0; i < px.length; i += 4) {
        px[i] = 255 - px[i];
        px[i + 1] = 255 - px[i + 1];
        px[i + 2] = 255 - px[i + 2];
      }
      ctx.putImageData(img, 0, 0);
    }
    return { canvas, dark };
  }

  /** Small copy of the screenshot to embed in the exported document. */
  async function makeEmbedImage(file) {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, 1000 / bitmap.width);
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    if (bitmap.close) bitmap.close();
    return { dataUrl: canvas.toDataURL('image/jpeg', 0.85), width: canvas.width, height: canvas.height, type: 'jpg' };
  }

  // Keep only what the organizer needs (the full result is large).
  function slimOcr(data) {
    return {
      blocks: (data.blocks || []).map((b) => ({
        paragraphs: (b.paragraphs || []).map((p) => ({
          lines: (p.lines || []).map((l) => ({
            text: l.text,
            bbox: l.bbox,
            confidence: l.confidence,
            words: (l.words || []).map((w) => ({
              text: w.text,
              confidence: w.confidence,
              bbox: w.bbox,
              symbols: (w.symbols || []).map((s) => ({ text: s.text, bbox: s.bbox })),
            })),
          })),
        })),
      })),
    };
  }

  function setProgress(fraction, text) {
    els.progressFill.style.width = Math.round(Math.max(0, Math.min(1, fraction)) * 100) + '%';
    if (text) els.progressText.textContent = text;
  }

  function setBusy(value) {
    busy = value;
    [els.title, els.layout, els.lang, els.overlap, els.statusbar, els.images, els.sortName, els.clearAll, els.fileInput, els.keywords]
      .concat(Array.from(document.querySelectorAll('input[name="keep"], input[name="kind"]')))
      .forEach((el) => { el.disabled = value; });
    els.dropzone.classList.toggle('disabled', value);
    renderThumbs();
  }

  /** null for "everything", else { kinds, keywords } for "only certain details". */
  function keepSettings() {
    if (document.querySelector('input[name="keep"]:checked').value !== 'only') return null;
    return {
      kinds: Array.from(document.querySelectorAll('input[name="kind"]:checked')).map((c) => c.value),
      keywords: els.keywords.value.split(',').map((k) => k.trim()).filter(Boolean),
    };
  }

  function updateKeepUI() {
    const only = Boolean(keepSettings());
    els.keepDetails.hidden = !only;
    els.layoutField.hidden = only;
  }

  async function run() {
    if (busy || !items.length) return;
    const wanted = keepSettings();
    if (wanted && !wanted.kinds.length && !wanted.keywords.length) {
      toast('Tick at least one kind of detail, or type words to look for.', true);
      return;
    }
    const lang = els.lang.value;
    setBusy(true);
    els.progress.hidden = false;
    setProgress(0, 'Loading the text reader (first time can take a moment)…');

    const total = items.length;
    let current = 0;
    progressHandler = (m) => {
      if (m.status === 'recognizing text') {
        setProgress((current + m.progress) / total, 'Reading screenshot ' + (current + 1) + ' of ' + total + '…');
      } else if (/loading|initializ/i.test(m.status) && current === 0) {
        setProgress(0.02, 'Getting ready: ' + m.status + '…');
      }
    };

    try {
      await getWorker(lang);
      for (current = 0; current < total; current++) {
        const item = items[current];
        if (item.ocr[lang]) continue; // already read with this language
        item.status = 'working';
        item.note = 'Reading…';
        renderThumbs();
        try {
          const { canvas } = await prepareImage(item.file);
          const { data } = await ocrWorker.recognize(canvas, {}, { text: true, blocks: true });
          item.ocr[lang] = { data: slimOcr(data), imageHeight: canvas.height, imageWidth: canvas.width };
          item.status = 'done';
          const words = (data.text || '').split(/\s+/).filter(Boolean).length;
          item.note = words ? '✓ ' + words + ' words found' : 'No text found';
        } catch (err) {
          console.error(err);
          item.status = 'error';
          item.note = 'Could not read this image';
        }
        renderThumbs();
      }

      setProgress(1, 'Organizing the document…');
      const readable = items.filter((it) => it.ocr[lang]);
      if (!readable.length) throw new Error('None of the screenshots could be read.');

      const pages = readable.map((it) => ({ fileName: it.name, data: it.ocr[lang].data, imageHeight: it.ocr[lang].imageHeight, imageWidth: it.ocr[lang].imageWidth }));
      const organizeOpts = {
        title: els.title.value.trim(),
        layout: els.layout.value,
        removeOverlap: els.overlap.checked,
        ignoreStatusBar: els.statusbar.checked,
      };
      const wanted = keepSettings();
      doc = wanted ? Organizer.extractDetails(pages, Object.assign(organizeOpts, wanted)) : Organizer.organize(pages, organizeOpts);

      if (els.images.checked) {
        setProgress(1, 'Adding screenshots to the document…');
        const embeds = await Promise.all(readable.map((it) => makeEmbedImage(it.file)));
        const byName = new Map(readable.map((it, i) => [it.name, embeds[i]]));
        doc.sections.forEach((s) => { s.images = s.sourceFiles.map((f) => byName.get(f)).filter(Boolean); });
      }

      showResult();
      setProgress(1, 'Done! Your document is ready below.');
      els.result.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (err) {
      console.error(err);
      setProgress(0, 'Something went wrong: ' + err.message);
      toast(/load/i.test(err.message) ?
        'Could not load the text reader. Check your internet connection (needed the first time) and try again.' :
        err.message, true);
    } finally {
      progressHandler = null;
      setBusy(false);
    }
  }

  // --------------------------------------------------------------- result

  function renderStats() {
    const shots = doc.screenshotCount || doc.sections.reduce((a, s) => a + s.sourceFiles.length, 0);
    const removed = doc.sections.reduce((a, s) => a + (s.removedLines || 0), 0);
    const details = doc.mode === 'extract' ?
      doc.sections.reduce((a, s) => a + s.blocks.reduce((b, bl) => b + (bl.type === 'list' ? bl.items.length : 0), 0), 0) :
      (doc.highlights || []).reduce((a, h) => a + h.items.length, 0);
    const chips = doc.mode === 'extract' ? [
      shots + ' screenshot' + (shots === 1 ? '' : 's'),
      details + ' item' + (details === 1 ? '' : 's') + ' found',
    ] : [
      shots + ' screenshot' + (shots === 1 ? '' : 's'),
      doc.wordCount + ' words',
      doc.sections.length + ' section' + (doc.sections.length === 1 ? '' : 's'),
      details + ' key detail' + (details === 1 ? '' : 's') + ' found',
    ];
    if (removed) chips.push(removed + ' repeated line' + (removed === 1 ? '' : 's') + ' removed');
    els.stats.innerHTML = '';
    chips.forEach((c) => {
      const span = document.createElement('span');
      span.className = 'stat';
      span.textContent = c;
      els.stats.appendChild(span);
    });
    doc.sections.filter((s) => s.blocks.length && s.confidence && s.confidence < 70).forEach((s) => {
      const span = document.createElement('span');
      span.className = 'stat warn';
      span.textContent = '⚠ "' + s.title + '" was hard to read — please check it';
      els.stats.appendChild(span);
    });
  }

  function exportHtml() {
    return Exporters.toHtml(doc, { includeImages: els.images.checked });
  }

  function updatePreview() {
    const scrollY = els.preview.contentWindow ? els.preview.contentWindow.scrollY : 0;
    els.preview.onload = () => {
      const w = els.preview.contentWindow;
      const d = els.preview.contentDocument;
      d.querySelectorAll('a[href]').forEach((a) => {
        const href = a.getAttribute('href');
        if (href.startsWith('#')) {
          a.addEventListener('click', (e) => {
            e.preventDefault();
            const target = d.getElementById(href.slice(1));
            if (target) target.scrollIntoView({ behavior: 'smooth' });
          });
        } else {
          a.target = '_blank';
          a.rel = 'noopener';
        }
      });
      if (scrollY) w.scrollTo(0, scrollY);
    };
    els.preview.srcdoc = exportHtml();
  }

  const onEdited = debounce(() => {
    Organizer.refreshHighlights(doc);
    renderStats();
    updatePreview();
  }, 350);

  function renderEditor() {
    els.editTitle.value = doc.title;
    els.editSections.innerHTML = '';
    doc.sections.forEach((section, idx) => {
      const box = document.createElement('div');
      box.className = 'edit-section';
      box.innerHTML =
        '<header><span class="badge"></span><input type="text" aria-label="Section title"></header>' +
        '<div class="row"><textarea spellcheck="true" aria-label="Section text"></textarea><div class="src"></div></div>';
      box.querySelector('.badge').textContent = 'Section ' + (idx + 1);
      const titleInput = box.querySelector('input');
      titleInput.value = section.title;
      titleInput.addEventListener('input', () => { section.title = titleInput.value; onEdited(); });
      const ta = box.querySelector('textarea');
      ta.value = Organizer.toMarkup(section.blocks);
      ta.rows = Math.min(24, Math.max(8, ta.value.split('\n').length + 1));
      ta.addEventListener('input', () => { section.blocks = Organizer.parseMarkup(ta.value); onEdited(); });

      const src = box.querySelector('.src');
      section.sourceFiles.forEach((fileName) => {
        const item = items.find((it) => it.name === fileName);
        if (!item) return;
        const img = document.createElement('img');
        img.src = item.url;
        img.alt = 'Original: ' + fileName;
        img.title = 'Click to open the original screenshot';
        img.onclick = () => window.open(item.url, '_blank');
        src.appendChild(img);
      });
      els.editSections.appendChild(box);
    });
  }

  function showResult() {
    els.result.hidden = false;
    renderStats();
    renderEditor();
    updatePreview();
    renderThumbs();
  }

  function selectTab(which) {
    const edit = which === 'edit';
    els.tabPreview.classList.toggle('active', !edit);
    els.tabEdit.classList.toggle('active', edit);
    els.tabPreview.setAttribute('aria-selected', String(!edit));
    els.tabEdit.setAttribute('aria-selected', String(edit));
    els.panelPreview.hidden = edit;
    els.panelEdit.hidden = !edit;
  }

  function printDocument() {
    const frame = document.createElement('iframe');
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
    frame.setAttribute('aria-hidden', 'true');
    document.body.appendChild(frame);
    frame.onload = () => {
      setTimeout(() => {
        frame.contentWindow.focus();
        frame.contentWindow.print();
        setTimeout(() => frame.remove(), 60000);
      }, 250);
    };
    frame.srcdoc = exportHtml();
    toast('In the print window, choose "Save as PDF" as the printer.');
  }

  async function exportAs(kind) {
    if (!doc) return;
    try {
      switch (kind) {
        case 'docx': {
          toast('Preparing Word document…');
          const { lib } = await loadLib('docx', 'docx');
          const blob = await Exporters.toDocx(doc, lib, { includeImages: els.images.checked });
          download(blob, Exporters.safeFileName(doc.title, 'docx'));
          toast('Word document downloaded.');
          break;
        }
        case 'pdf': printDocument(); break;
        case 'html': download(exportHtml(), Exporters.safeFileName(doc.title, 'html'), 'text/html;charset=utf-8'); break;
        case 'md': download(Exporters.toMarkdown(doc), Exporters.safeFileName(doc.title, 'md'), 'text/markdown;charset=utf-8'); break;
        case 'txt': download(Exporters.toPlainText(doc), Exporters.safeFileName(doc.title, 'txt'), 'text/plain;charset=utf-8'); break;
        default: break;
      }
    } catch (err) {
      console.error(err);
      toast('Export failed: ' + err.message, true);
    }
  }

  // ---------------------------------------------------------------- wiring

  els.fileInput.addEventListener('change', () => { addFiles(els.fileInput.files); els.fileInput.value = ''; });
  els.dropzone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); if (!busy) els.fileInput.click(); }
  });
  els.dropzone.addEventListener('click', (e) => { if (busy) e.preventDefault(); });
  ['dragenter', 'dragover'].forEach((ev) => els.dropzone.addEventListener(ev, (e) => {
    if (!e.dataTransfer.types.includes('Files')) return;
    e.preventDefault();
    els.dropzone.classList.add('drag');
  }));
  ['dragleave', 'drop'].forEach((ev) => els.dropzone.addEventListener(ev, () => els.dropzone.classList.remove('drag')));
  // Files can be dropped anywhere on the page (and it shouldn't navigate away).
  window.addEventListener('dragover', (e) => { if (e.dataTransfer.types.includes('Files')) e.preventDefault(); });
  window.addEventListener('drop', (e) => {
    if (!e.dataTransfer.files.length) return;
    e.preventDefault();
    if (!busy) addFiles(e.dataTransfer.files);
  });
  document.addEventListener('paste', (e) => {
    if (busy || /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName)) return;
    const files = Array.from(e.clipboardData ? e.clipboardData.files : []).filter((f) => f.type.startsWith('image/'));
    if (files.length) { addFiles(files); toast('Pasted ' + files.length + ' image' + (files.length > 1 ? 's' : '') + '.'); }
  });

  els.sortName.addEventListener('click', () => {
    const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
    items.sort((a, b) => collator.compare(a.name, b.name));
    renderThumbs();
  });
  els.clearAll.addEventListener('click', () => {
    if (!confirm('Remove all screenshots?')) return;
    items.forEach((i) => URL.revokeObjectURL(i.url));
    items = [];
    renderThumbs();
  });

  els.run.addEventListener('click', () => {
    if (doc && !confirm('Rebuild the document? Any edits you made to the text will be replaced.')) return;
    run();
  });
  document.querySelectorAll('input[name="keep"]').forEach((r) => r.addEventListener('change', updateKeepUI));
  updateKeepUI();
  els.tabPreview.addEventListener('click', () => selectTab('preview'));
  els.tabEdit.addEventListener('click', () => selectTab('edit'));
  els.editTitle.addEventListener('input', () => { doc.title = els.editTitle.value || 'Untitled'; onEdited(); });
  document.querySelectorAll('[data-export]').forEach((btn) => btn.addEventListener('click', () => exportAs(btn.dataset.export)));
  els.images.addEventListener('change', () => {
    if (doc && els.images.checked && !doc.sections.some((s) => s.images)) toast('Click "Rebuild document" to add the screenshots.');
    else if (doc) updatePreview();
  });
  els.copyText.addEventListener('click', async () => {
    if (!doc) return;
    try {
      await navigator.clipboard.writeText(Exporters.toPlainText(doc));
      toast('Text copied to the clipboard.');
    } catch (e) {
      toast('Copying is blocked by the browser — use the Text download instead.', true);
    }
  });

  // Start loading the text reader in the background so the first run is faster.
  loadLib('tesseract', 'Tesseract').catch(() => {});

  // Exposed for debugging and automated tests.
  window.ScreenshotApp = { get doc() { return doc; }, get items() { return items; }, addFiles, run };

  renderThumbs();
})();
