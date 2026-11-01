// Checks what the voice is given for tricky web-fiction text (test/say/cases.tsv: text, then what
// should be said). Runs the app's page script (app/assets/web/loudbook.js) in Node, no browser.
// Run before every release (build.yml). To add a case: append a line, run, check the output.
globalThis.window = globalThis;
globalThis.location = { href: 'about:blank' };
globalThis.document = { querySelector: () => null, querySelectorAll: () => [], createElement: () => ({}),
  head: { appendChild() {} }, documentElement: { appendChild() {} }, addEventListener() {} };
const fs = require('fs'), path = require('path');
eval(fs.readFileSync(path.join(__dirname, '../../app/assets/web/loudbook.js'), 'utf8'));
let bad = 0, n = 0;
for (const line of fs.readFileSync(path.join(__dirname, 'cases.tsv'), 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const [text, want] = line.split('\t');
  const got = window.LB.sayText(text, '', true);
  n++;
  if (got !== want) { bad++; console.log(`FAIL ${text}\n  want ${want}\n  got  ${got}`); }
}
console.log(`${n - bad} of ${n} pronunciation cases right`);
// Royal Road's hidden anti-theft lines (test/say/antitheft.tsv: skip or keep, then the line; or para,
// a paragraph, then what's left of it once the line is taken out): the
// skipped ones are never read, story lines that share their words still are.
const rr = window.LB_SITES && window.LB_SITES.SITES.find(s => s.id === 'royalroad');
let rrBad = 0, rrN = 0;
for (const line of fs.readFileSync(path.join(__dirname, 'antitheft.tsv'), 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const [want, text, left] = line.split('\t');
  rrN++;
  if (want === 'para') {                              // a paragraph with the line inside: what's left of it
    const got = rr ? window.LB_SITES.withoutSkipped(text, rr.skipText) : text;
    if (got !== left) { rrBad++; console.log(`FAIL anti-theft paragraph: ${text}\n  want ${left}\n  got  ${got}`); }
    continue;
  }
  const got = rr && rr.skipText.test(text) ? 'skip' : 'keep';
  if (got !== want) { rrBad++; console.log(`FAIL anti-theft: want ${want}, got ${got}: ${text}`); }
}
console.log(`${rrN - rrBad} of ${rrN} Royal Road anti-theft lines right`);
bad += rrBad;
// The word mark during the voice's pauses (LB.letterAt): at a comma or a full stop the fraction said sits exactly at a
// word's end, and the mark stays on that word until the voice goes on (floor(f * n) jumped to the next word).
{
  const t = 'Well, then he went. After that, nothing.';
  const p = window.LB.partsOf(t), n = p.total;
  const wordOf = (f) => p.wordsAt.findIndex(([a, b]) => window.LB.letterAt(f, n) < b);
  const sentOf = (f) => p.sentences.findIndex(([a, b]) => window.LB.letterAt(f, n) < b);
  const checks = [
    ['the start', wordOf(0), 0],
    ['the comma pause after "Well"', wordOf(4 / n), 0],
    ['the same, as Java\'s float prints it', wordOf(Math.fround(4 / n)), 0],
    ['a bit past it: "then"', wordOf(4.3 / n), 1],
    ['the full stop pause: still the first sentence', sentOf(14 / n), 0],
    ['the second sentence once it starts', sentOf(14.4 / n), 1],
    ['the end', wordOf(1), p.wordsAt.length - 1],
    ['past the end', wordOf(1.2), p.wordsAt.length - 1],
  ];
  let wb = 0;
  for (const [what, got, want] of checks) if (got !== want) { wb++; console.log(`FAIL word mark at ${what}: want ${want}, got ${got}`); }
  console.log(`${checks.length - wb} of ${checks.length} word-mark checks right`);
  bad += wb;
}
process.exit(bad ? 1 : 0);
