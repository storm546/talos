// Renders the Bulgarian documents to print-ready A4 PDFs in docs/out/.
// Appendix 1 of the tournament rules: A4, Times New Roman 12 pt, line spacing 1.5.
// Usage: node docs/build.cjs [name ...]   (needs the npm packages `marked` and `playwright`)
const { readFileSync, mkdirSync, readdirSync } = require('node:fs');
const { join, basename } = require('node:path');
const { pathToFileURL } = require('node:url');
const { marked } = require('marked');
const { chromium } = require('playwright');

const dir = __dirname;
const out = join(dir, 'out');
mkdirSync(out, { recursive: true });
const names = process.argv.slice(2).length ? process.argv.slice(2)
  : readdirSync(dir).filter(f => f.endsWith('.md')).map(f => basename(f, '.md'));

// Liberation Serif has the same metrics as Times New Roman, so page breaks match on machines without it.
const css = `
@page { size: A4; margin: 2cm; }
body { font-family: "Times New Roman", "Liberation Serif", serif; font-size: 12pt; line-height: 1.5; color: #000; }
h1 { font-size: 16pt; margin: 0 0 6pt; } h2 { font-size: 13pt; margin: 10pt 0 2pt; } h3 { font-size: 12pt; margin: 8pt 0 2pt; }
p, ul, ol { margin: 0 0 6pt; } li { margin: 0; }
table { border-collapse: collapse; margin: 4pt 0 8pt; font-size: 11pt; line-height: 1.3; }
th, td { border: 1px solid #555; padding: 2pt 5pt; text-align: left; vertical-align: top; }
img { max-width: 100%; max-height: 9cm; display: block; margin: 4pt auto; }
code { font-family: "Liberation Mono", monospace; font-size: 10.5pt; }
pre { white-space: pre-wrap; font-size: 10pt; line-height: 1.3; }
`;

(async () => {
// CHROME may point at an installed browser when Playwright's own download is absent.
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined });
try {
  const page = await browser.newPage();
  for (const name of names) {
    const body = marked.parse(readFileSync(join(dir, `${name}.md`), 'utf8'));
    // A base URL lets relative image paths such as img/app-result.png resolve.
    await page.setContent(`<!doctype html><html lang="bg"><head><meta charset="utf-8"><base href="${pathToFileURL(dir + '/')}"><style>${css}</style></head><body>${body}</body></html>`, { waitUntil: 'load' });
    await page.pdf({ path: join(out, `${name}.pdf`), format: 'A4', preferCSSPageSize: true });
    console.log(`${name}.pdf`);
  }
} finally {
  await browser.close();
}
})();
