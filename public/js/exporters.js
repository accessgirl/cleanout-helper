/*
 * exporters.js
 * Turns the organized document model into downloadable files:
 * Word (.docx), web page (.html, also used for Print / Save as PDF),
 * Markdown (.md) and plain text (.txt).
 *
 * Document layout (same in every format):
 *   1. Title + summary line
 *   2. Contents (one line per section)
 *   3. Key details at a glance (dates, phones, emails, links, money)
 *   4. One section per screenshot, with headings, lists and paragraphs
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Exporters = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function formatDate(iso) {
    const d = iso ? new Date(iso) : new Date();
    return d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
  }

  function plural(n, word) {
    return n + ' ' + word + (n === 1 ? '' : 's');
  }

  function summaryLine(doc) {
    const shots = doc.sections.reduce((a, s) => a + (s.sourceFiles ? s.sourceFiles.length : 1), 0);
    return 'Created ' + formatDate(doc.createdAt) + ' from ' + plural(shots, 'screenshot') +
      ' · ' + plural(doc.wordCount || 0, 'word');
  }

  // Skip a section heading that would just repeat the document title.
  function showSectionTitle(doc, section) {
    return !(doc.sections.length === 1 && section.title === doc.title);
  }

  function sourceLabel(doc, idx) {
    const s = doc.sections[idx];
    return s ? s.title : '';
  }

  function slug(text, i) {
    return 'sec-' + (i + 1) + '-' + String(text).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
  }

  function safeFileName(title, ext) {
    const base = String(title || 'screenshot-notes').replace(/[\\/:*?"<>|]+/g, '').replace(/\s+/g, ' ').trim().slice(0, 80) || 'screenshot-notes';
    return base + '.' + ext;
  }

  // ------------------------------------------------------------ Markdown

  function mdEscape(text) {
    return String(text).replace(/([\\`*_{}\[\]<>|])/g, '\\$1');
  }

  function toMarkdown(doc) {
    const out = [];
    out.push('# ' + mdEscape(doc.title), '', '_' + summaryLine(doc) + '_', '');

    if (doc.sections.length > 1) {
      out.push('## Contents', '');
      doc.sections.forEach((s, i) => out.push((i + 1) + '. [' + mdEscape(s.title) + '](#' + slug(s.title, i) + ')'));
      out.push('');
    }

    if (doc.highlights && doc.highlights.length) {
      out.push('## Key Details at a Glance', '');
      doc.highlights.forEach((h) => {
        out.push('**' + h.label + '**', '');
        h.items.forEach((it) => {
          const where = doc.sections.length > 1 ? ' _(' + it.sources.map((i) => mdEscape(sourceLabel(doc, i))).join(', ') + ')_' : '';
          out.push('- ' + mdEscape(it.value) + where);
        });
        out.push('');
      });
    }

    doc.sections.forEach((s, i) => {
      if (showSectionTitle(doc, s)) out.push('<a id="' + slug(s.title, i) + '"></a>', '## ' + mdEscape(s.title), '');
      s.blocks.forEach((b) => {
        switch (b.type) {
          case 'heading': out.push('#'.repeat(Math.min(b.level + 2, 6)) + ' ' + mdEscape(b.text)); break;
          case 'paragraph': out.push(mdEscape(b.text)); break;
          case 'fields': b.items.forEach((f) => out.push('- **' + mdEscape(f.label) + ':** ' + mdEscape(f.value))); break;
          case 'list':
            b.items.forEach((it, n) => {
              if (b.style === 'checklist') out.push('- [' + (it.checked ? 'x' : ' ') + '] ' + mdEscape(it.text));
              else if (b.style === 'ordered') out.push((it.number || n + 1) + '. ' + mdEscape(it.text));
              else out.push('- ' + mdEscape(it.text));
            });
            break;
          default: break;
        }
        out.push('');
      });
      if (!s.blocks.length) out.push('_No text was found in this screenshot._', '');
      if (s.sourceFiles && s.sourceFiles.length) out.push('<sub>Source: ' + s.sourceFiles.map(mdEscape).join(', ') + '</sub>', '');
    });
    return out.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n';
  }

  // ---------------------------------------------------------- Plain text

  function underline(text, ch) {
    return text + '\n' + ch.repeat(Math.min(text.length, 70));
  }

  function wrap(text, width, indent) {
    indent = indent || '';
    const words = String(text).split(/\s+/);
    const lines = [];
    let line = '';
    words.forEach((w) => {
      if ((line + ' ' + w).trim().length > width - indent.length && line) {
        lines.push(line);
        line = w;
      } else {
        line = (line + ' ' + w).trim();
      }
    });
    if (line) lines.push(line);
    return lines.map((l, i) => (i === 0 ? '' : indent) + l).join('\n');
  }

  function toPlainText(doc) {
    const out = [];
    out.push(underline(doc.title.toUpperCase(), '='), summaryLine(doc), '');

    if (doc.sections.length > 1) {
      out.push(underline('CONTENTS', '-'));
      doc.sections.forEach((s, i) => out.push('  ' + (i + 1) + '. ' + s.title));
      out.push('');
    }

    if (doc.highlights && doc.highlights.length) {
      out.push(underline('KEY DETAILS AT A GLANCE', '-'));
      doc.highlights.forEach((h) => {
        out.push(h.label + ':');
        h.items.forEach((it) => out.push('  • ' + it.value));
      });
      out.push('');
    }

    doc.sections.forEach((s, i) => {
      if (showSectionTitle(doc, s)) out.push('', underline((doc.sections.length > 1 ? (i + 1) + '. ' : '') + s.title, '-'), '');
      s.blocks.forEach((b) => {
        switch (b.type) {
          case 'heading': out.push(b.level === 1 ? b.text.toUpperCase() : b.text, ''); return;
          case 'paragraph': out.push(wrap(b.text, 78)); break;
          case 'fields': b.items.forEach((f) => out.push(wrap(f.label + ': ' + f.value, 78, '    '))); break;
          case 'list':
            b.items.forEach((it, n) => {
              const mark = b.style === 'checklist' ? (it.checked ? '[x] ' : '[ ] ') : b.style === 'ordered' ? (it.number || n + 1) + '. ' : '• ';
              out.push('  ' + mark + wrap(it.text, 74, '    '));
            });
            break;
          default: break;
        }
        out.push('');
      });
      if (!s.blocks.length) out.push('(No text was found in this screenshot.)', '');
    });
    return out.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n';
  }

  // ---------------------------------------------------------------- HTML

  function esc(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function linkify(text) {
    return esc(text)
      .replace(/\b(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)])/gi, '<a href="$1">$1</a>')
      .replace(/(^|[\s(])(www\.[^\s<>"']+[^\s<>"'.,;:!?)])/gi, '$1<a href="https://$2">$2</a>')
      .replace(/\b([\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[A-Za-z]{2,})\b/g, '<a href="mailto:$1">$1</a>');
  }

  function blocksToHtml(blocks) {
    return blocks.map((b) => {
      switch (b.type) {
        case 'heading': return '<h' + (b.level + 2) + '>' + linkify(b.text) + '</h' + (b.level + 2) + '>';
        case 'paragraph': return '<p>' + linkify(b.text) + '</p>';
        case 'fields':
          return '<dl class="fields">' + b.items.map((f) => '<dt>' + esc(f.label) + '</dt><dd>' + linkify(f.value) + '</dd>').join('') + '</dl>';
        case 'list': {
          if (b.style === 'checklist') {
            return '<ul class="checklist">' + b.items.map((it) => '<li class="' + (it.checked ? 'done' : '') + '"><span class="box">' + (it.checked ? '☑' : '☐') + '</span> ' + linkify(it.text) + '</li>').join('') + '</ul>';
          }
          const tag = b.style === 'ordered' ? 'ol' : 'ul';
          const start = b.style === 'ordered' && b.items[0] && b.items[0].number > 1 ? ' start="' + b.items[0].number + '"' : '';
          return '<' + tag + start + '>' + b.items.map((it) => '<li>' + linkify(it.text) + '</li>').join('') + '</' + tag + '>';
        }
        default: return '';
      }
    }).join('\n');
  }

  const DOC_CSS = `
  :root { color-scheme: light; }
  body { font-family: "Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif; color: #1f2933; background: #fff; line-height: 1.55; margin: 0; }
  main { max-width: 780px; margin: 0 auto; padding: 40px 28px 60px; }
  h1 { font-size: 2rem; margin: 0 0 4px; color: #102a43; }
  .summary { color: #52606d; margin: 0 0 28px; }
  h2 { font-size: 1.35rem; color: #102a43; border-bottom: 2px solid #d9e2ec; padding-bottom: 4px; margin: 36px 0 12px; }
  h3 { font-size: 1.15rem; color: #243b53; margin: 22px 0 8px; }
  h4 { font-size: 1.02rem; color: #334e68; margin: 18px 0 6px; }
  h5 { font-size: .95rem; color: #334e68; text-transform: uppercase; letter-spacing: .04em; margin: 16px 0 6px; }
  a { color: #0b69a3; }
  .toc ol { padding-left: 22px; margin: 0; }
  .glance { background: #f0f4f8; border: 1px solid #d9e2ec; border-radius: 10px; padding: 14px 18px; }
  .glance h2 { border: 0; margin: 0 0 8px; padding: 0; }
  .glance table { width: 100%; border-collapse: collapse; }
  .glance th { text-align: left; vertical-align: top; width: 170px; padding: 6px 10px 6px 0; color: #334e68; font-weight: 600; }
  .glance td { padding: 6px 0; border-top: 1px solid #d9e2ec; }
  .glance th { border-top: 1px solid #d9e2ec; }
  .glance tr:first-child th, .glance tr:first-child td { border-top: 0; }
  .glance .val { display: block; }
  .glance .where { color: #829ab1; font-size: .85em; }
  dl.fields { display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px; margin: 10px 0; }
  dl.fields dt { font-weight: 600; color: #334e68; }
  dl.fields dd { margin: 0; }
  ul.checklist { list-style: none; padding-left: 4px; }
  ul.checklist li.done { color: #7b8794; text-decoration: line-through; }
  .box { text-decoration: none; display: inline-block; }
  .source { color: #9aa5b1; font-size: .8rem; margin-top: 10px; }
  .shot { max-width: 100%; max-height: 420px; border: 1px solid #d9e2ec; border-radius: 6px; margin: 8px 0; }
  .empty { color: #9aa5b1; font-style: italic; }
  section { break-inside: auto; }
  @media print { main { padding: 0; } h2 { break-after: avoid; } .glance { break-inside: avoid; } a { color: inherit; } }
  `;

  function toHtml(doc, opts) {
    opts = opts || {};
    const parts = [];
    parts.push('<h1>' + esc(doc.title) + '</h1>', '<p class="summary">' + esc(summaryLine(doc)) + '</p>');

    if (doc.sections.length > 1) {
      parts.push('<nav class="toc"><h2>Contents</h2><ol>' +
        doc.sections.map((s, i) => '<li><a href="#' + slug(s.title, i) + '">' + esc(s.title) + '</a></li>').join('') + '</ol></nav>');
    }

    if (doc.highlights && doc.highlights.length) {
      parts.push('<aside class="glance"><h2>Key Details at a Glance</h2><table>' +
        doc.highlights.map((h) => '<tr><th>' + esc(h.label) + '</th><td>' + h.items.map((it) => {
          const where = doc.sections.length > 1 ? ' <span class="where">— ' + it.sources.map((i) => esc(sourceLabel(doc, i))).join(', ') + '</span>' : '';
          return '<span class="val">' + linkify(it.value) + where + '</span>';
        }).join('') + '</td></tr>').join('') + '</table></aside>');
    }

    doc.sections.forEach((s, i) => {
      parts.push('<section id="' + slug(s.title, i) + '">');
      if (showSectionTitle(doc, s)) parts.push('<h2>' + esc(s.title) + '</h2>');
      parts.push(s.blocks.length ? blocksToHtml(s.blocks) : '<p class="empty">No text was found in this screenshot.</p>');
      if (opts.includeImages && s.images) s.images.forEach((img) => parts.push('<img class="shot" alt="Original screenshot" src="' + img.dataUrl + '">'));
      if (s.sourceFiles && s.sourceFiles.length) parts.push('<p class="source">Source: ' + s.sourceFiles.map(esc).join(', ') + '</p>');
      parts.push('</section>');
    });

    const body = parts.join('\n');
    if (opts.fragment) return body;
    return '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n<title>' +
      esc(doc.title) + '</title>\n<style>' + DOC_CSS + '</style>\n</head>\n<body>\n<main>\n' + body + '\n</main>\n</body>\n</html>\n';
  }

  // ---------------------------------------------------------------- Word

  function dataUrlToBytes(dataUrl) {
    const b64 = dataUrl.split(',')[1] || '';
    if (typeof atob === 'function') {
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return bytes;
    }
    return Uint8Array.from(Buffer.from(b64, 'base64'));
  }

  /** Split text into TextRuns / hyperlinks so links are clickable in Word. */
  function richRuns(D, text, runOpts) {
    runOpts = runOpts || {};
    const re = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)]|[\w.+-]+@[\w-]+(?:\.[\w-]+)*\.[A-Za-z]{2,})/g;
    const runs = [];
    let last = 0;
    let m;
    while ((m = re.exec(text))) {
      if (m.index > last) runs.push(new D.TextRun(Object.assign({ text: text.slice(last, m.index) }, runOpts)));
      const link = m[0].includes('@') && !/^https?:/i.test(m[0]) ? 'mailto:' + m[0] : m[0];
      runs.push(new D.ExternalHyperlink({ link, children: [new D.TextRun(Object.assign({ text: m[0], style: 'Hyperlink' }, runOpts))] }));
      last = m.index + m[0].length;
    }
    if (last < text.length) runs.push(new D.TextRun(Object.assign({ text: text.slice(last) }, runOpts)));
    return runs;
  }

  /**
   * Build a .docx Blob (browser) or Buffer (Node).
   * @param {object} doc
   * @param {object} D   the `docx` library
   */
  async function toDocx(doc, D, opts) {
    opts = opts || {};
    const H = [D.HeadingLevel.HEADING_1, D.HeadingLevel.HEADING_2, D.HeadingLevel.HEADING_3, D.HeadingLevel.HEADING_4];
    const children = [];
    const border = { style: D.BorderStyle.SINGLE, size: 4, color: 'D9E2EC' };
    let listInstance = 0; // restarts numbering for every numbered list

    children.push(new D.Paragraph({ text: doc.title, heading: D.HeadingLevel.TITLE }));
    children.push(new D.Paragraph({ children: [new D.TextRun({ text: summaryLine(doc), italics: true, color: '52606D' })], spacing: { after: 240 } }));

    if (doc.sections.length > 1) {
      children.push(new D.Paragraph({ text: 'Contents', heading: H[0] }));
      doc.sections.forEach((s, i) => children.push(new D.Paragraph({
        children: [new D.InternalHyperlink({ anchor: slug(s.title, i).replace(/-/g, '_'), children: [new D.TextRun({ text: (i + 1) + '.  ' + s.title, style: 'Hyperlink' })] })],
        spacing: { after: 60 },
      })));
    }

    if (doc.highlights && doc.highlights.length) {
      children.push(new D.Paragraph({ text: 'Key Details at a Glance', heading: H[0] }));
      const rows = doc.highlights.map((h) => new D.TableRow({
        children: [
          new D.TableCell({
            width: { size: 28, type: D.WidthType.PERCENTAGE },
            shading: { type: D.ShadingType.CLEAR, color: 'auto', fill: 'F0F4F8' },
            margins: { top: 80, bottom: 80, left: 120, right: 120 },
            children: [new D.Paragraph({ children: [new D.TextRun({ text: h.label, bold: true, color: '334E68' })] })],
          }),
          new D.TableCell({
            width: { size: 72, type: D.WidthType.PERCENTAGE },
            margins: { top: 80, bottom: 80, left: 120, right: 120 },
            children: h.items.map((it) => {
              const runs = richRuns(D, it.value);
              if (doc.sections.length > 1) runs.push(new D.TextRun({ text: '  — ' + it.sources.map((i) => sourceLabel(doc, i)).join(', '), color: '829AB1', size: 18 }));
              return new D.Paragraph({ children: runs });
            }),
          }),
        ],
      }));
      children.push(new D.Table({
        width: { size: 100, type: D.WidthType.PERCENTAGE },
        rows,
        borders: { top: border, bottom: border, left: border, right: border, insideHorizontal: border, insideVertical: border },
      }));
    }

    doc.sections.forEach((s, i) => {
      const anchor = slug(s.title, i).replace(/-/g, '_');
      if (showSectionTitle(doc, s)) {
        children.push(new D.Paragraph({
          heading: H[0],
          pageBreakBefore: opts.pageBreaks && i > 0,
          children: [new D.Bookmark({ id: anchor, children: [new D.TextRun(s.title)] })],
        }));
      }
      if (!s.blocks.length) children.push(new D.Paragraph({ children: [new D.TextRun({ text: 'No text was found in this screenshot.', italics: true, color: '9AA5B1' })] }));
      s.blocks.forEach((b) => {
        switch (b.type) {
          case 'heading':
            children.push(new D.Paragraph({ heading: H[Math.min(b.level, 3)], children: richRuns(D, b.text) }));
            break;
          case 'paragraph':
            children.push(new D.Paragraph({ children: richRuns(D, b.text), spacing: { after: 160 } }));
            break;
          case 'fields':
            b.items.forEach((f) => children.push(new D.Paragraph({
              children: [new D.TextRun({ text: f.label + ': ', bold: true, color: '334E68' })].concat(richRuns(D, f.value)),
              spacing: { after: 60 },
            })));
            break;
          case 'list': {
            if (b.style === 'ordered') listInstance += 1;
            b.items.forEach((it) => {
              if (b.style === 'checklist') {
                children.push(new D.Paragraph({
                  children: [new D.TextRun({ text: (it.checked ? '☑' : '☐') + '  ' })].concat(richRuns(D, it.text, it.checked ? { strike: true, color: '7B8794' } : {})),
                  indent: { left: 360 },
                  spacing: { after: 60 },
                }));
              } else if (b.style === 'ordered') {
                children.push(new D.Paragraph({ children: richRuns(D, it.text), numbering: { reference: 'ordered', level: 0, instance: listInstance } }));
              } else {
                children.push(new D.Paragraph({ children: richRuns(D, it.text), bullet: { level: 0 } }));
              }
            });
            break;
          }
          default: break;
        }
      });
      if (opts.includeImages && s.images) {
        s.images.forEach((img) => {
          const maxW = 460;
          const scale = Math.min(1, maxW / img.width, 560 / img.height);
          children.push(new D.Paragraph({
            children: [new D.ImageRun({ type: img.type || 'png', data: dataUrlToBytes(img.dataUrl), transformation: { width: Math.round(img.width * scale), height: Math.round(img.height * scale) } })],
            spacing: { before: 120, after: 60 },
          }));
        });
      }
      if (s.sourceFiles && s.sourceFiles.length) {
        children.push(new D.Paragraph({ children: [new D.TextRun({ text: 'Source: ' + s.sourceFiles.join(', '), color: '9AA5B1', size: 16 })], spacing: { after: 240 } }));
      }
    });

    const document = new D.Document({
      creator: 'Screenshot to Document',
      title: doc.title,
      description: summaryLine(doc),
      styles: {
        default: { document: { run: { font: 'Calibri', size: 22, color: '1F2933' } } },
        paragraphStyles: [
          { id: 'Title', name: 'Title', basedOn: 'Normal', next: 'Normal', run: { size: 48, bold: true, color: '102A43' }, paragraph: { spacing: { after: 60 } } },
          { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 32, bold: true, color: '102A43' }, paragraph: { spacing: { before: 360, after: 120 }, border: { bottom: { style: D.BorderStyle.SINGLE, size: 8, color: 'D9E2EC', space: 4 } } } },
          { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 27, bold: true, color: '243B53' }, paragraph: { spacing: { before: 240, after: 100 } } },
          { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 24, bold: true, color: '334E68' }, paragraph: { spacing: { before: 200, after: 80 } } },
          { id: 'Heading4', name: 'Heading 4', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 22, bold: true, allCaps: true, color: '334E68' }, paragraph: { spacing: { before: 160, after: 60 } } },
        ],
      },
      numbering: {
        config: [{
          reference: 'ordered',
          levels: [{ level: 0, format: D.LevelFormat.DECIMAL, text: '%1.', alignment: D.AlignmentType.START, style: { paragraph: { indent: { left: 720, hanging: 360 } } } }],
        }],
      },
      sections: [{ properties: {}, children }],
    });

    if (typeof Blob !== 'undefined' && !opts.asBuffer) return D.Packer.toBlob(document);
    return D.Packer.toBuffer(document);
  }

  return { toMarkdown, toPlainText, toHtml, toDocx, safeFileName, summaryLine };
});
