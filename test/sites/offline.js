// The app's copy of a chapter, shown when its page can't be loaded (no signal: MainActivity.showOffline):
// read from it, the chapter comes out the same as from its page (the same sentences, numbered the same),
// so reading and the highlight carry on. Plain Node + jsdom, like run.js.
const fs = require('fs'), path = require('path');
const { JSDOM } = require('jsdom');
const root = path.join(__dirname, '..', '..');
const bundle = fs.readFileSync(path.join(root, 'app', 'assets', 'web', 'loudbook.js'), 'utf8');
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'cases.json'), 'utf8'));
const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
async function read(html, url) {
  const dom = new JSDOM(html, { url, runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  // (jsdom lays nothing out: everything would look hidden)
  w.Element.prototype.getClientRects = function () { return [{ width: 100, height: 20 }]; };
  w.Element.prototype.getBoundingClientRect = function () { return { left: 0, top: 0, right: 100, bottom: 20, width: 100, height: 20 }; };
  let got = null;
  w.LoudbookNative = { onChapter: (j) => { got = JSON.parse(j); }, onNoChapter: (j) => { got = { none: JSON.parse(j) }; } };
  w.eval(bundle);
  await w.eval("LB.run('', true)");
  dom.window.close();
  return got;
}
// as MainActivity.showOffline writes it
function offline(ch) {
  let max = -1; for (const k of ch.chunks) max = Math.max(max, k.block);
  const para = []; for (const k of ch.chunks) if (k.block >= 0) para[k.block] = para[k.block] == null ? k.text : para[k.block] + ' ' + k.text;
  let h = `<!doctype html><html><head><meta charset="utf-8"><meta name="loudbook-offline" data-site="${esc(ch.site || '')}" data-fiction="${esc(ch.fiction || '')}" data-next="${esc(ch.nextUrl || '')}" data-prev="${esc(ch.prevUrl || '')}"><title>${esc(ch.title || '')}</title></head><body><p class="lb-note">No connection: this is the chapter as it was read ahead.</p><h1>${esc(ch.title || '')}</h1>`;
  for (let i = 0; i <= max; i++) h += `<p data-lb-block>${para[i] == null ? '' : esc(para[i])}</p>`;
  return h + '</body></html>';
}
(async () => {
  let bad = 0;
  for (const c of cases) {
    const ch = await read(fs.readFileSync(path.join(__dirname, 'pages', c.page), 'utf8'), c.url);
    if (!ch || !ch.chunks) { console.log(`skip ${c.page}: not read as a chapter`); continue; }
    const again = await read(offline(ch), c.url);
    const fails = [];
    if (!again || !again.chunks) fails.push('the offline copy is not read as a chapter');
    else {
      if (JSON.stringify(again.chunks) !== JSON.stringify(ch.chunks)) fails.push('its sentences differ: ' + JSON.stringify(again.chunks).slice(0, 200));
      for (const k of ['title', 'fiction', 'nextUrl', 'prevUrl', 'site']) if ((again[k] || null) !== (ch[k] || null)) fails.push(`${k}: ${again[k]} (page: ${ch[k]})`);
    }
    console.log((fails.length ? 'FAIL ' : 'ok   ') + `${c.page}: the offline copy reads as the page did` + (fails.length ? ': ' + fails.join('; ') : ''));
    if (fails.length) bad++;
  }
  process.exit(bad ? 1 : 0);
})();
