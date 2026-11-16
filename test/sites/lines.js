// Marks in chapters written as lines with <br> (Blogger and forum posts: several paragraphs in one element) and in a
// paragraph taller than the screen (bridge.js highlight / rangeOf / sites.js lines "base"). Plain Node + jsdom.
//  - the second of two equal lines ("Ding!") is marked, also after fancy letters (they counted twice in the line's start, so
//    the search began past it and marked the first one, 2026-11-16)
//  - the mark doesn't take in the full stop at the end of the line before (across the <br>)
//  - a sentence already on screen in a tall paragraph doesn't scroll the page (it centred the paragraph on every piece,
//    which could move the sentence off the screen)
const fs = require('fs'), path = require('path');
const { JSDOM } = require('jsdom');
const bundle = fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'assets', 'web', 'loudbook.js'), 'utf8');
function page(body, url, rect, rangeRect) {
  const dom = new JSDOM(`<!doctype html><html><head><meta charset="utf-8"><title>T</title></head><body>${body}</body></html>`,
    { url, runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window; w.innerHeight = 800;
  w.Element.prototype.getClientRects = function () { return [{ width: 100, height: 20 }]; };
  w.Element.prototype.getBoundingClientRect = function () { return rect(this); };
  w.Range.prototype.getBoundingClientRect = () => rangeRect;
  const marks = new Map(); w.Highlight = function (r) { this.r = r; };
  w.CSS = { highlights: { set: (k, v) => marks.set(k, v), delete: (k) => marks.delete(k) }, escape: (s) => s };
  w.scrolls = 0; w.scrollBy = () => w.scrolls++; w.Element.prototype.scrollIntoView = () => w.scrolls++;
  w.LoudbookNative = { onChapter: (j) => { w.ch = JSON.parse(j); }, onNoChapter: () => {} };
  w.eval(bundle);
  return { w, marks };
}
const small = () => ({ left: 0, top: 100, right: 100, bottom: 120, width: 100, height: 20 });
function at(w, root, r) {                                   // where a range starts in the root's text
  const tw = w.document.createTreeWalker(root, 4); let n, off = 0;
  while ((n = tw.nextNode())) { if (n === r.startContainer) return off + r.startOffset; off += n.nodeValue.length; }
  return -1;
}
(async () => {
  let bad = 0;
  const check = (what, ok, got) => { console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok ? '' : ' (got ' + got + ')')); if (!ok) bad++; };
  for (const fancy of [false, true]) {
    const S = fancy ? '𝐒𝐭𝐚𝐭𝐮𝐬 𝐖𝐢𝐧𝐝𝐨𝐰' : 'Status Window', P = fancy ? '𝐒𝐤𝐢𝐥𝐥 𝐏𝐨𝐢𝐧𝐭𝐬' : 'Skill Points';
    const { w, marks } = page(`<div class="post-body">The ${S} appeared before him.<br>“Ding!”<br>[${P} +3] He looked at it for a long while.<br>“Ding!”<br>He sighed and closed the window and went to sleep for the night.</div>`,
      'https://someblog.example/2024/01/ch-1.html', small, small());
    await w.eval("LB.run('', true)");
    const dings = w.ch.chunks.filter((c) => c.text.includes('Ding'));
    const root = w.document.querySelector('.post-body'), all = root.textContent;
    w.LB.highlight(dings[1].block, dings[1].text, 0, w.ch.chunks.indexOf(dings[1]));
    const r = marks.get('loudbook-sentence').r;
    check(`the second "Ding!" line marked${fancy ? ' (fancy letters before it)' : ''}`, at(w, root, r) === all.lastIndexOf('“Ding'), at(w, root, r));
    w.LB.highlight(dings[0].block, dings[0].text, 0, w.ch.chunks.indexOf(dings[0]));
    const r0 = marks.get('loudbook-sentence').r;
    check(`the first "Ding!" mark starts at its quote, not the line before's full stop${fancy ? ' (fancy)' : ''}`, r0.toString().startsWith('“Ding'), JSON.stringify(r0.toString()));
  }
  {
    const words = ['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen'];
    const long = words.map((x) => `Sentence number ${x} goes on for a while so the paragraph is tall.`).join(' ');
    const { w } = page(`<article><p>${long}</p><p>Short one after it here.</p><p>And another short one.</p></article>`, 'https://example.com/story/chapter-1',
      (el) => (el.tagName === 'P' && el.textContent.length > 500 ? { left: 0, top: -300, right: 100, bottom: 1400, width: 100, height: 1700 } : small()),
      { left: 0, top: 300, right: 100, bottom: 340, width: 100, height: 40 });
    await w.eval("LB.run('', true)");
    const cs = w.ch.chunks.filter((c) => c.block === 0);
    let off = 0;
    cs.forEach((c, i) => { w.LB.highlight(0, c.text, off, i); off += Array.from(c.text.replace(/\s+/g, '')).length; });
    check(`a tall paragraph, its sentence on screen: no scrolling (${cs.length} pieces)`, w.scrolls === 0, w.scrolls + ' scrolls');
  }
  {
    // his own scrolling (a finger on the page): the sentence off screen isn't scrolled to for a while; later it is again
    const { w } = page('<article><p>One sentence here. Two sentences here.</p><p>Short one after it here.</p></article>', 'https://example.com/story/chapter-1',
      small, { left: 0, top: 1500, right: 100, bottom: 1520, width: 100, height: 20 });
    await w.eval("LB.run('', true)");
    const c = w.ch.chunks;
    w.dispatchEvent(new w.Event('touchmove'));
    w.LB.highlight(c[0].block, c[0].text, 0, 0);
    check('his own scrolling just now: the page left where he put it', w.scrolls === 0, w.scrolls + ' scrolls');
    w.__lbUserScrollAt = Date.now() - 9000;
    w.LB.highlight(c[1].block, c[1].text, 0, 1);
    check('a while after it: the sentence brought back on screen', w.scrolls === 1, w.scrolls + ' scrolls');
  }
  {
    // the site draws its paragraphs again after they were read (new nodes, same text): the marks find them again
    const { w, marks } = page('<article><p>First paragraph is here.</p><p>Second paragraph is here.</p><p>Third one.</p></article>', 'https://example.com/story/chapter-1',
      small, small());
    await w.eval("LB.run('', true)");
    const art = w.document.querySelector('article');
    art.innerHTML = art.innerHTML.replace(/ data-lb[^=]*="[^"]*"/g, '').replace(/ class="[^"]*"/g, '');
    const c = w.ch.chunks.find((x) => x.text.startsWith('Second'));
    const ok = w.LB.highlight(c.block, c.text, 0, w.ch.chunks.indexOf(c));
    const r = marks.get('loudbook-sentence');
    check('paragraphs drawn again: the sentence still marked', ok && r && r.r.toString().startsWith('Second'), String(ok) + ' ' + (r ? JSON.stringify(r.r.toString()) : 'no mark'));
  }
  console.log(bad ? `lines: ${bad} wrong` : 'lines: all right');
  process.exit(bad ? 1 : 0);
})();
