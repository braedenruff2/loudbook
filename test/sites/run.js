// Checks how sites.js reads chapter pages, without an emulator: each page in pages/ is a chapter laid
// out like a real site, and cases.json says what Loudbook must get from it (title, story, first and
// last lines read, lines that must NOT be read, next/previous chapter). Run: node test/sites/run.js
// (needs jsdom: npm i --no-save jsdom@24). Reads the app's bundled copy, app/assets/web/loudbook.js.
const fs = require('fs'), path = require('path');
let JSDOM;
try { ({ JSDOM } = require('jsdom')); } catch { console.error('jsdom missing: npm i --no-save jsdom@24'); process.exit(2); }
const root = path.join(__dirname, '..', '..');
const bundle = fs.readFileSync(path.join(root, 'app', 'assets', 'web', 'loudbook.js'), 'utf8');
const start = bundle.indexOf('(() => {\n  if (globalThis.LB_SITES) return;');
const endMark = bundle.indexOf('globalThis.LB_SITES = {', start);
const end = bundle.indexOf('})();', endMark) + 5;
if (start < 0 || endMark < 0) { console.error('sites.js not found in loudbook.js'); process.exit(2); }
const sitesSrc = bundle.slice(start, end);
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'cases.json'), 'utf8'));
let bad = 0;
const norm = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
for (const c of cases) {
  const html = fs.readFileSync(path.join(__dirname, 'pages', c.page), 'utf8');
  const dom = new JSDOM(html, { url: c.url, runScripts: 'outside-only' });
  dom.window.eval(sitesSrc);
  const r = dom.window.LB_SITES.extract(dom.window.document, c.url);
  const lines = r.blocks.map(b => norm(b.text));
  const fails = [];
  const want = (k, got) => { if (k in c && norm(c[k]) !== norm(got)) fails.push(`${k}: got "${got}", want "${c[k]}"`); };
  want('site', r.site); want('title', r.title); want('fiction', r.fiction);
  want('next', r.nextUrl); want('prev', r.prevUrl); want('count', lines.length);
  if (c.first && lines[0] !== norm(c.first)) fails.push(`first line: got "${lines[0]}", want "${c.first}"`);
  if (c.last && lines[lines.length - 1] !== norm(c.last)) fails.push(`last line: got "${lines[lines.length - 1]}", want "${c.last}"`);
  for (const s of c.has || []) if (!lines.some(l => l.includes(s))) fails.push(`missing line with "${s}"`);
  for (const s of c.not || []) { const l = lines.find(l => l.toLowerCase().includes(s.toLowerCase())); if (l) fails.push(`reads "${l}" (should skip "${s}")`); }
  if (fails.length) { bad++; console.log(`FAIL ${c.page}\n  ${fails.join('\n  ')}\n  lines: ${JSON.stringify(lines)}`); }
  else console.log(`ok   ${c.page} (${lines.length} lines, "${r.title}" / "${r.fiction}")`);
}
console.log(bad ? `${bad} of ${cases.length} pages read wrong` : `all ${cases.length} pages read right`);
process.exit(bad ? 1 : 0);
