// Loudbook for Android: the part that runs inside the chapter page (in the app's WebView).
// Built into assets/web/loudbook.js together with the extension's sites.js, lib/chunk.js and
// lib/say.js (see build.sh), so the phone reads pages exactly like the Chrome extension.
//
// Talks to the app through window.LoudbookNative (a Java object):
//   LoudbookNative.onChapter(json)   - the chapter, split into sentences, ready to speak
//   LoudbookNative.onNoChapter(json) - this page isn't a chapter
(() => {
  if (window.LB) return;
  const S = globalThis.LB_SITES;

  // ---------- visibility (same rules as the extension's content.js) ----------
  function hiddenEl(el) {
    if (!(el instanceof Element)) return false;
    if (el.getClientRects().length === 0) return true;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return true;
    if (parseFloat(cs.opacity) === 0) return true;
    if (parseFloat(cs.fontSize) === 0) return true;
    if (cs.clipPath && cs.clipPath !== 'none' && /inset\(\s*(50|100)%/.test(cs.clipPath)) return true;
    const r = el.getBoundingClientRect();
    if (cs.position === 'absolute' || cs.position === 'fixed') {
      if (r.right < -500 || r.bottom < -500 || r.left > innerWidth + 5000) return true;
      if (r.width <= 1 && r.height <= 1) return true;
    }
    return false;
  }
  const displayNone = (el) => el instanceof Element && el.getClientRects().length === 0;

  // ---------- numbers, said the way a narrator would ----------
  // The phone's Kokoro reads text through a dictionary, so digits are turned into words first.
  const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve',
    'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
  const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];
  function under1000(n) {
    const h = Math.floor(n / 100), r = n % 100;
    const parts = [];
    if (h) parts.push(ONES[h] + ' hundred');
    if (r) parts.push(r < 20 ? ONES[r] : TENS[Math.floor(r / 10)] + (r % 10 ? '-' + ONES[r % 10] : ''));
    return parts.join(' and ');
  }
  function words(n) {
    if (!Number.isFinite(n)) return String(n);
    if (n === 0) return 'zero';
    if (n < 0) return 'minus ' + words(-n);
    const scales = [[1e12, 'trillion'], [1e9, 'billion'], [1e6, 'million'], [1e3, 'thousand']];
    const out = [];
    for (const [v, name] of scales) if (n >= v) { out.push(under1000(Math.floor(n / v)) + ' ' + name); n %= v; }
    if (n) out.push(under1000(n));
    return out.join(' ').replace(/ and (\w+)$/, ' and $1');
  }
  function yearWords(y) {
    if (y % 1000 < 10 && y >= 2000) return words(y);              // 2005 -> two thousand five
    const a = Math.floor(y / 100), b = y % 100;
    return words(a) + ' ' + (b === 0 ? 'hundred' : b < 10 ? 'oh ' + words(b) : words(b));
  }
  const ORD = { one: 'first', two: 'second', three: 'third', five: 'fifth', eight: 'eighth', nine: 'ninth', twelve: 'twelfth' };
  function ordinal(n) {
    const w = words(n);
    return w.replace(/(\w+)$/, (m) => ORD[m] || (m.endsWith('y') ? m.slice(0, -1) + 'ieth' : m + 'th'));
  }
  function speakNumbers(t) {
    return t
      .replace(/\$(\d[\d,]*)(?:\.(\d{2}))?\b/g, (_, d, c) => { const n = +d.replace(/,/g, ''); return words(n) + (n === 1 ? ' dollar' : ' dollars') + (c && +c ? ' and ' + words(+c) + ' cents' : ''); })
      .replace(/\b(\d{1,2}):(\d{2})\b/g, (_, h, m) => words(+h) + (+m === 0 ? " o'clock" : +m < 10 ? ' oh ' + words(+m) : ' ' + words(+m)))
      .replace(/\b(\d+)(st|nd|rd|th)\b/gi, (_, d) => ordinal(+d))
      .replace(/\b(1[1-9]\d\d|20\d\d)s?\b/g, (m, y) => { const w = yearWords(+y); return m.endsWith('s') ? (w.endsWith('y') ? w.slice(0, -1) + 'ies' : w + 's') : w; })
      .replace(/\b(\d)0s\b/g, (m, d) => TENS[+d].replace(/y$/, 'ies'))         // the 80s -> the eighties
      .replace(/\b\d{1,3}(,\d{3})+\b/g, (m) => words(+m.replace(/,/g, '')))
      .replace(/\b(\d+)\.(\d+)\b/g, (_, a, b) => words(+a) + ' point ' + b.split('').map(x => ONES[+x]).join(' '))
      .replace(/\b\d+\b/g, (m) => (m.length > 15 ? m.split('').map(x => ONES[+x]).join(' ') : words(+m)))
      .replace(/(\d)%/g, '$1 percent');
  }

  // ---------- extracting ----------
  let blocks = [];
  let activeIndex = -1;

  async function extract() {
    let r = S.extract(document, location.href, hiddenEl);
    if (r.roots.length && !r.blocks.length) r = S.extract(document, location.href, displayNone);
    blocks = r.blocks;
    if (r.textApi) {
      try {
        const res = await fetch(r.textApi, { credentials: 'include' });
        if (res.ok) {
          const site = S.profileFor(location.href);
          const api = S.blocksFromApiHtml(await res.text(), site && site.skip);
          if (api.length >= blocks.length) blocks = api.map(b => ({ el: null, text: b.text, pid: b.pid }));
        }
      } catch { /* keep what's on the page */ }
    }
    return r;
  }

  function elOf(b) {
    if (b.el && b.el.isConnected) return b.el;
    if (b.pid) {
      const el = document.querySelector(`p[data-p-id="${CSS.escape(b.pid)}"]`);
      if (el) { b.el = el; return el; }
    }
    return null;
  }

  // Run by the app after every page load. fixes: the pronunciation list; tidy: web-fiction clean-ups.
  async function run(fixes, tidy) {
    try {
      const r = await extract();
      if (!blocks.length) {
        LoudbookNative.onNoChapter(JSON.stringify({ url: location.href, site: r.site, isStorySite: !!S.profileFor(location.href) }));
        return;
      }
      const say = LBSay.makeSayer(LBSay.parseFixes(fixes || ''), { tidy: tidy !== false });
      const chunks = [{ block: -1, text: r.title }, ...LBChunk.chunkBlocks(blocks.map((b, i) => ({ i, text: b.text })))]
        .map(c => ({ block: c.block, text: c.text, say: speakNumbers(say(c.text)) }))
        .filter(c => /[\p{L}\p{N}]/u.test(c.say));            // scene breaks ("* * *") aren't read
      LoudbookNative.onChapter(JSON.stringify({
        url: location.href, site: r.site, siteName: r.siteName, title: r.title, fiction: r.fiction,
        nextUrl: r.nextUrl, prevUrl: r.prevUrl, chunks,
      }));
    } catch (e) {
      LoudbookNative.onNoChapter(JSON.stringify({ url: location.href, error: String(e && e.stack || e) }));
    }
  }

  // ---------- highlight: the paragraph gets a tint, the sentence a stronger mark ----------
  const canMark = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight === 'function';
  const squash = (t) => t.replace(/[\s​-‍﻿]+/g, '');
  let markedRange = null;
  function markSentence(el, text, offset) {
    markedRange = null;
    if (!canMark) return false;
    CSS.highlights.delete('loudbook-sentence');
    if (!text) return false;
    const pos = [];
    const walk = (node) => {
      for (const n of node.childNodes) {
        if (n.nodeType === 3) { const v = n.nodeValue; for (let k = 0; k < v.length; k++) if (!/[\s​-‍﻿]/.test(v[k])) pos.push([n, k]); continue; }
        if (n.nodeType !== 1) continue;
        if (/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|BUTTON)$/.test(n.tagName) || hiddenEl(n)) continue;
        walk(n);
      }
    };
    walk(el);
    const flat = pos.map(([n, k]) => n.nodeValue[k]).join('');
    const key = squash(text);
    if (!key) return false;
    let at = flat.indexOf(key, Math.max(0, (offset || 0) - 8));
    if (at < 0) at = flat.indexOf(key);
    if (at < 0) return false;
    const [sn, so] = pos[at], [en, eo] = pos[at + key.length - 1];
    const r = document.createRange();
    r.setStart(sn, so); r.setEnd(en, eo + 1);
    CSS.highlights.set('loudbook-sentence', new Highlight(r));
    markedRange = r;
    return true;
  }
  function unTint() { const p = blocks[activeIndex]; if (p && p.el && !p.shared) p.el.classList.remove('lb-active'); }
  function highlight(i, text, offset) {
    unTint();
    activeIndex = i;
    const b = blocks[i];
    const el = b && elOf(b);
    if (!el) {
      if (canMark) CSS.highlights.delete('loudbook-sentence');
      if (b && b.pid) { const loaded = document.querySelectorAll('p[data-p-id]'); if (loaded.length) loaded[loaded.length - 1].scrollIntoView({ block: 'end' }); }
      return false;
    }
    if (!b.shared) el.classList.add('lb-active');
    const marked = markSentence(el, text, (offset || 0) + (b.shared && b.base > 0 ? b.base : 0));
    const r = (b.shared && markedRange ? markedRange : el).getBoundingClientRect();
    if (r.top < 60 || r.bottom > innerHeight - 60) {
      if (b.shared && markedRange) window.scrollBy({ top: r.top - innerHeight / 3, behavior: 'smooth' });
      else el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    return marked;
  }
  function clear() { unTint(); activeIndex = -1; if (canMark) CSS.highlights.delete('loudbook-sentence'); }

  // tap-and-hold a paragraph -> "read from here" is handled by the app; it asks which block is here
  function blockAt(x, y) {
    const el = document.elementFromPoint(x, y);
    if (!el) return -1;
    for (let i = 0; i < blocks.length; i++) { const e = elOf(blocks[i]); if (e && (e === el || e.contains(el))) return i; }
    return -1;
  }

  const style = document.createElement('style');
  style.textContent = `.lb-active{background:rgba(232,170,70,.13)!important;box-shadow:-5px 0 0 rgba(232,170,70,.85)!important;border-radius:3px}
    ::highlight(loudbook-sentence){background-color:rgba(232,170,70,.38)}`;
  (document.head || document.documentElement).appendChild(style);

  // sayText: what the voice is given for a bit of text (for the pronunciation tests)
  const sayText = (t, fixes, tidy) => speakNumbers(LBSay.makeSayer(LBSay.parseFixes(fixes || ""), { tidy: tidy !== false })(t));
  window.LB = { run, highlight, clear, blockAt, speakNumbers, words, sayText };
})();
