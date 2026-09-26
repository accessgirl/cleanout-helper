# Screenshot to Document

Upload one or many screenshots. The app reads the writing in them (OCR) and builds **one organized document** you can download as **Word (.docx), PDF, web page (.html), Markdown (.md) or plain text (.txt)**.

🔒 Everything runs in your browser. Screenshots are never uploaded to a server.

## What it does

1. **Add screenshots**: drag and drop them, pick them with the file chooser, or paste them (Ctrl/⌘ + V). Rearrange them by dragging or with the arrow buttons.
2. **Read the text**: each screenshot goes through [Tesseract.js](https://github.com/naptha/tesseract.js). Dark-mode screenshots are flipped to dark-on-light and small ones are enlarged first, which makes them easier to read.
3. **Organize it**: the app works out the structure from the text itself:
   - **Headings** come from larger text. The biggest heading becomes the section title.
   - **Bullet lists, numbered lists and checklists** (`•`, `1.`, `[ ]`, `[x]`, ☐ …).
   - **Paragraphs**: lines that wrap are joined back into full sentences.
   - **Labeled details** such as `Phone: 555-987-6543`.
   - **Repeated text is removed** when you took several screenshots while scrolling down one long page.
   - Phone **status bars** (clock, battery) are ignored.
4. **Review and download** the document. It is laid out like this:
   - Title, with a summary line (date, number of screenshots, word count)
   - **Contents**: a clickable list of sections
   - **Key Details at a Glance**: every date and time, phone number, email address, link and money amount found, with the screenshot it came from
   - One section per screenshot, or one continuous document if you choose that option
   - You can also include the original screenshots in the document

Use the **Edit text** tab to fix anything the OCR misread before you download. The preview and the key details update as you type.

## Running it on your computer

You need [Node.js](https://nodejs.org) 18 or newer.

```bash
npm install     # downloads the OCR engine and copies it into public/vendor
npm start       # then open http://localhost:3000
```

After `npm install`, the app works with no internet connection: the OCR engine, the English language data and the Word exporter are all served from `public/vendor`. Other languages (Spanish, French, …) are downloaded the first time you use them.

## Putting it online (free, with GitHub Pages)

This repository has a workflow in `.github/workflows/pages.yml` that publishes the app:

1. On GitHub, go to **Settings → Pages** and set **Source** to **GitHub Actions**.
2. Push to the `main` branch (or run the workflow from the **Actions** tab).
3. The app will be at `https://<your-username>.github.io/cleanout-helper/`. It works on phones too.

## Project layout

```
public/
  index.html        the page
  styles.css        the look (includes a dark theme)
  js/app.js         user interface: uploads, OCR, preview, editing, downloads
  js/organizer.js   turns raw OCR output into headings, lists, paragraphs and key details
  js/exporters.js   builds the Word / HTML / Markdown / text files
scripts/
  vendor.js         copies the libraries into public/vendor (runs on npm install)
  serve.js          small local web server (npm start)
test/
  unit/             tests for the organizer and exporters, plus real OCR on sample screenshots
  e2e/              full browser test (needs Playwright; skipped if it isn't installed)
  fixtures/         sample screenshots and the HTML used to make them
```

## Tests

```bash
npm test          # unit tests, including real OCR on the sample screenshots
npm run test:e2e  # full browser test (needs: npm i -D playwright && npx playwright install chromium)
```

## Tips for the best results

- Use real screenshots rather than photos of a screen. They are much sharper.
- Crop out parts you don't need, such as ads and menus.
- For a long page, take overlapping screenshots in order and pick **One continuous document**.
- Always double-check important numbers (phone numbers, amounts) against the originals. The Edit tab shows each screenshot next to its text.
