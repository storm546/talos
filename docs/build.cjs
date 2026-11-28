// Renders the Bulgarian documents to print-ready A4 PDFs in docs/out/.
// Appendix 1 of the tournament rules: A4, Times New Roman 12 pt, line spacing 1.5.
// Usage: node docs/build.cjs [file.md|file.html ...]   (needs the npm packages `marked` and `playwright`)
const { readFileSync, writeFileSync, mkdirSync, readdirSync, copyFileSync } = require('node:fs');
const { join, basename } = require('node:path');
const { pathToFileURL } = require('node:url');
const { marked } = require('marked');
const { chromium } = require('playwright');

const dir = __dirname;
const out = join(dir, 'out');
// The three jury documents are also kept at the repository root under their Bulgarian names (linked from README.md).
const rootCopies = { dokumentacia: 'Talos-документация.pdf', slides: 'Talos-презентация.pdf', 'listovka-a4': 'Talos-диплянка.pdf' };
mkdirSync(out, { recursive: true });
// Markdown files get the shared print style; .html files (the flyer) carry their own layout.
const files = process.argv.slice(2).length ? process.argv.slice(2)
  : readdirSync(dir).filter(f => f.endsWith('.md') || f.endsWith('.html'));

// Liberation Serif has the same metrics as Times New Roman, so page breaks match on machines without it.
const css = `
@page { size: A4; margin: 2cm; }
body { font-family: "Times New Roman", "Liberation Serif", serif; font-size: 12pt; line-height: 1.5; color: #000; }
h1 { font-size: 16pt; margin: 0 0 6pt; } h2 { font-size: 13pt; margin: 10pt 0 2pt; } h3 { font-size: 12pt; margin: 8pt 0 2pt; }
p, ul, ol { margin: 0 0 6pt; } li { margin: 0; }
table { border-collapse: collapse; margin: 4pt 0 8pt; font-size: 11pt; line-height: 1.3; }
th, td { border: 1px solid #555; padding: 2pt 5pt; text-align: left; vertical-align: top; }
img { max-width: 100%; max-height: 9cm; display: block; margin: 4pt auto; }
.fig { display: flex; gap: 8pt; justify-content: center; align-items: flex-start; margin: 6pt 0 2pt; }
.fig img { margin: 0; min-width: 0; max-height: 7.5cm; border: 0.5pt solid #bbb; }
.fig img.phone { max-height: 7.5cm; }
.figcap { font-size: 10.5pt; text-align: center; margin: 0 0 8pt; }
code { font-family: "Liberation Mono", monospace; font-size: 10.5pt; }
pre { white-space: pre-wrap; font-size: 10pt; line-height: 1.3; }
`;

(async () => {
// CHROME may point at an installed browser when Playwright's own download is absent.
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
try {
  const page = await browser.newPage();
  for (const file of files) {
    const name = basename(file).replace(/\.(md|html)$/, '');
    if (file.endsWith('.html')) await page.goto(pathToFileURL(join(dir, basename(file))).href, { waitUntil: 'load' });
    else {
      const body = marked.parse(readFileSync(join(dir, basename(file)), 'utf8'));
      // Written next to the PDF and opened by file URL: Chrome loads local images only for file pages.
      // The base URL lets relative image paths such as img/app-editor.png resolve.
      const html = join(out, `${name}.html`);
      writeFileSync(html, `<!doctype html><html lang="bg"><head><meta charset="utf-8"><title>${name}</title><base href="${pathToFileURL(dir + '/')}"><style>${css}</style></head><body>${body}</body></html>`);
      await page.goto(pathToFileURL(html).href, { waitUntil: 'load' });
    }
    await page.pdf({ path: join(out, `${name}.pdf`), format: 'A4', preferCSSPageSize: true, printBackground: true });
    if (rootCopies[name]) copyFileSync(join(out, `${name}.pdf`), join(dir, '..', rootCopies[name]));
    console.log(`${name}.pdf`);
  }
} finally {
  await browser.close();
}
})();
