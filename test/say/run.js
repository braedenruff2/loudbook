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
process.exit(bad ? 1 : 0);
