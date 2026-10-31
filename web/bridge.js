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
  // A number that starts a sentence is said with a capital ("He fell. Three men died."): lowercase, the voice ran the two
  // sentences together with no pause ("fell three men"). \u0001 marks where such a number starts until it is words.
  function speakNumbers(t) {
    return numbersToWords(t.replace(/((?<!\b(?:[A-Za-z]|[ivxlc]+|[Vv]er|[Cc]hapt|Cf|approx|pp|vs|ca|[Nn]os?|[Vv]ols?|[Cc]h|[Cc]hap|[Pp]g|[Ff]igs?|[Ee]qs?|[Ee]x|[Rr]ef|[Aa]rt|[Pp]ara|[Oo]p|cf|ft|lbs?|oz|est|[Tt]el|[Ee]xt|[Pp]t|[Ee]p|[Ll]vl?|[Rr]m|[Aa]pt|St|Dr|Mr|Mrs|Ms|Prof|Gen|Col|Capt|Sgt|Lt|Mt|Ave|Rd|[Gg]r))[.!?]["”’')\]]*\s+)(?=\$?\d)/g, '$1\u0001'))
      .replace(/\u0001([a-z])/g, (m, c) => c.toUpperCase()).replace(/\u0001/g, '');
  }
  function numbersToWords(t) {
    return t
      .replace(/(\d)\s?%/g, '$1 percent')                     // before the digits become words
      .replace(/\$(\d[\d,]*)(?:\.(\d{2}))?\b/g, (_, d, c) => { const n = +d.replace(/,/g, ''); if (n === 0 && c && +c) return words(+c) + (+c === 1 ? ' cent' : ' cents');   // $0.99 -> ninety-nine cents
        return words(n) + (n === 1 ? ' dollar' : ' dollars') + (c && +c ? ' and ' + words(+c) + ' cents' : ''); })
      // 9:00 -> nine o'clock, 15:00 -> fifteen hundred, but "10:00 o'clock" and "3:00 AM" don't get a second o'clock
      .replace(/\b(\d{1,2}):(\d{2})\b/g, (_, h, m, at, all) => (+h === 0 && +m === 0 ? (/(?:\b(?:at|past|after|until|till|before|by|around|about|since)\s+)$/i.test(all.slice(Math.max(0, at - 9), at)) ? 'midnight' : 'zero zero') : words(+h) + (+m === 0 && +h >= 13 && +h <= 24 ? ' hundred' : +m === 0 ? (/^\s*(o'clock|o’clock|[ap]\.?m\b|[AP]M\b)/i.test(all.slice(at + 3 + h.length)) ? '' : " o'clock") : +m < 10 ? ' oh ' + words(+m) : ' ' + words(+m))))
      .replace(/\b(\d+)(st|nd|rd|th)\b/gi, (_, d) => ordinal(+d))
      // years before 1100 or after 2099, where it's clearly a year: "the year 1066", "the year 2525", "in 1066.", "800 AD" -> ten sixty-six, eight hundred AD
      .replace(/\b([Yy]ear\s+(?:(?:was|is|of)\s+)?)(10\d\d|2[1-9]\d\d|[3-9]\d\d\d)\b(?![.,]?\d)|\b((?:[Ii]n|[Ss]ince|[Uu]ntil|[Cc]irca|[Bb]y)\s+)(10\d\d)(?=\s*(?:[,;:!?)]|\.(?!\d)|$)|\s+(?:the|a|an|and|or|he|she|they|it|we|I|his|her|their|there|when|after|before)\b)|(?<![\d,.])\b(\d{3,4})(?=\s?(?:AD|BC|BCE|CE|A\.D\.|B\.C\.)(?!\w))|((?:\bAD|\bA\.D\.)\s?)(\d{3,4})\b(?![.,]?\d)/g,
        (m, p1, y1, p2, y2, y3, p4, y4) => { const y = +(y1 || y2 || y3 || y4); return (p1 || p2 || p4 || '') + (y % 1000 === 0 || (y < 1000 && y % 100 === 0) ? words(y) : yearWords(y)); })
      .replace(/\b(1[1-9]\d\d|20\d\d)s?\b/g, (m, y) => { const w = yearWords(+y); return m.endsWith('s') ? (w.endsWith('y') ? w.slice(0, -1) + 'ies' : w + 's') : w; })
      .replace(/\b(\d)0['’]?s\b/g, (m, d) => (TENS[+d] || 'ten').replace(/y$/, 'ies') + (d === '1' ? 's' : ''))   // the 80s, the 70's
      .replace(/\b([1-9])s\b/g, (m, d) => ONES[+d] + (d === '6' ? 'es' : 's'))   // two 6s -> two sixes         // the 80s -> the eighties
      .replace(/\b\d{1,3}(,\d{3})+\b/g, (m) => words(+m.replace(/,/g, '')))
      .replace(/\b(\d+)\.(\d+)\b/g, (_, a, b) => words(+a) + ' point ' + b.split('').map(x => ONES[+x]).join(' '))
      .replace(/(?<![\d.,:\/$#-])\b0([1-9])([0-5]\d)\b(?![\d.,:\/]\d)/g, (m, h, n) =>   // 0800 -> oh eight hundred (military time)
        'oh ' + ONES[+h] + (n === '00' ? ' hundred' : n[0] === '0' ? ' oh ' + ONES[+n[1]] : ' ' + words(+n)))
      .replace(/\b0\d+\b/g, (m) => m.split('').map(x => (x === '0' ? 'oh' : ONES[+x])).join(' '))   // Agent 007
      .replace(/\b\d+\b/g, (m) => (m.length > 15 ? m.split('').map(x => ONES[+x]).join(' ') : words(+m)));
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

  // ---------- highlight: the paragraph gets a tint, the sentence being said a mark, and the word a stronger one ----------
  // The app hands over each piece of text as the voice starts it (a piece can hold a few short sentences), then, while it
  // plays, how far through it the voice is (LB.progress), worked out from the sound's own pauses: so the mark moves on
  // sentence by sentence, and word by word, with the voice. Text is matched on letters and digits only, so a stat table's
  // " — " or a quote mark the reader's copy has and the page doesn't (or the other way round) can't stop it.
  const canMark = typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight === 'function';
  const squash = (t) => t.replace(/[\s​-‍﻿]+/g, '');
  const isL = (c) => /[\p{L}\p{N}]/u.test(c);
  const lettersOf = (t) => Array.from(t).filter(isL).join('');
  let markedRange = null;
  // the piece of text marked now: its letters' places on the page, and where its sentences and words are (in letters)
  let cur = null;
  // two characters side by side on the page: next to each other in one text node, or the last of one node and the first of
  // the next ("<em>Longbourn</em>.": the full stop after an italic word is still the sentence's)
  const touch = ([n1, o1], [n2, o2]) => (n1 === n2 ? o2 === o1 + 1 : o1 === n1.nodeValue.length - 1 && o2 === 0);
  function rangeOf(p, a, b) {                                     // letters a..b-1 of the piece -> a DOM range
    const [sn, so] = p.pos[p.map[p.at + a]];
    let ei = p.map[p.at + b - 1];
    // take in the closing marks right after the last letter (a full stop, a quote) if nothing separates them
    while (ei + 1 < p.pos.length && !isL(p.pos[ei + 1][0].nodeValue[p.pos[ei + 1][1]]) && touch(p.pos[ei], p.pos[ei + 1])) ei++;
    let si = p.map[p.at + a];
    while (si > 0 && !isL(p.pos[si - 1][0].nodeValue[p.pos[si - 1][1]]) && touch(p.pos[si - 1], p.pos[si])) si--;
    const [en, eo] = p.pos[ei];
    const r = document.createRange();
    r.setStart(p.pos[si][0], p.pos[si][1]); r.setEnd(en, eo + 1);
    return r;
  }
  // [start, end) in letters of each sentence and each word of a piece of text
  const ABBR = /(?:\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|Mt|vs|etc|Vol|Ch|Lt|Col|Gen|Sgt|Capt|Rev|Hon|approx|lvl|Lv|Lvl)|(?:^|[\s"'“‘(])(?!I\.)\p{Lu})\.$/u;
  function partsOf(text) {
    const sentences = [], wordsAt = [];
    let n = 0;
    for (const w of text.split(/\s+/)) { const k = lettersOf(w).length; if (k) { wordsAt.push([n, n + k]); n += k; } }
    let m = 0;
    // ("Mr. Collins", "Dr. Kim", "J. Smith", "Lvl. 5": a short form's full stop doesn't end the sentence)
    const parts = [];
    for (const sn of text.split(/(?<=[.!?…]["'”’)\]]*)\s+(?=[^\s\p{Ll}])/u)) {
      if (parts.length && ABBR.test(parts[parts.length - 1])) parts[parts.length - 1] += ' ' + sn; else parts.push(sn);
    }
    for (const sn of parts) { const k = lettersOf(sn).length; if (k) { sentences.push([m, m + k]); m += k; } }
    return { sentences, wordsAt, total: n };
  }
  function markSentence(el, text, offset) {
    markedRange = null; cur = null;
    if (!canMark) return false;
    CSS.highlights.delete('loudbook-sentence'); CSS.highlights.delete('loudbook-word');
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
    const map = [];                                               // letter k of the paragraph -> its place in pos
    let flat = '';
    let from = 0;
    for (let i = 0; i < pos.length; i++) { const c = pos[i][0].nodeValue[pos[i][1]]; if (isL(c)) { if (i < (offset || 0)) from = map.length + 1; map.push(i); flat += c; } }
    const key = lettersOf(text);
    if (!key) return false;
    let at = flat.indexOf(key, Math.max(0, from - 8));
    if (at < 0) at = flat.indexOf(key);
    if (at < 0 && key.length >= 40) {
      // (the page has words in the middle the reader's copy left out, e.g. a site's hidden "stolen from" line shown
      // inside a paragraph: mark from where it starts to where it ends, as one piece, without the word mark)
      const head = key.slice(0, 20), tail = key.slice(-20);
      let a = flat.indexOf(head, Math.max(0, from - 8));
      if (a < 0) a = flat.indexOf(head);
      const b = a < 0 ? -1 : flat.indexOf(tail, a + 20);
      if (a >= 0 && b > a && b + 20 - a < key.length * 2) {
        const whole = { pos, map, at: a, len: b + 20 - a, sentences: [[0, b + 20 - a]], wordsAt: [], total: b + 20 - a, lit: -1, word: -1 };
        cur = whole;
        markedRange = rangeOf(whole, 0, whole.len);
        CSS.highlights.set('loudbook-sentence', new Highlight(markedRange));
        whole.lit = 0;
        return true;
      }
    }
    if (at < 0) return false;
    const p = { pos, map, at, len: key.length, ...partsOf(text), lit: -1, word: -1 };
    if (p.total !== key.length) return false;
    cur = p;
    markedRange = rangeOf(p, 0, key.length);
    showPart(0);
    return true;
  }
  // marks the sentence and the word at letter k of the piece being read
  function showPart(k) {
    const p = cur;
    if (!p || !canMark) return;
    let si = p.sentences.findIndex(([a, b]) => k < b);
    if (si < 0) si = p.sentences.length - 1;
    if (si !== p.lit) {
      p.lit = si;
      const [a, b] = p.sentences[si];
      const r = rangeOf(p, a, b);
      CSS.highlights.set('loudbook-sentence', new Highlight(r));
      markedRange = r;
    }
    if (!p.wordsAt.length) return;
    let wi = p.wordsAt.findIndex(([a, b]) => k < b);
    if (wi < 0) wi = p.wordsAt.length - 1;
    if (wi !== p.word && k >= 0) {
      p.word = wi;
      const [a, b] = p.wordsAt[wi];
      CSS.highlights.set('loudbook-word', new Highlight(rangeOf(p, a, b)));
    }
  }
  // how far through the piece being read the voice is (0..1): moves the marks on, and keeps them on screen
  function progress(f, chunk) {
    const p = cur;
    if (!p || (chunk !== undefined && p.chunk !== undefined && chunk !== p.chunk)) return;   // (for a piece not marked yet)
    const k = Math.max(0, Math.min(p.total - 1, Math.floor(f * p.total)));
    const before = p.lit;
    showPart(k);
    if (p.lit !== before && markedRange) {
      const r = markedRange.getBoundingClientRect();
      if (r.top < 60 || r.bottom > innerHeight - 60) window.scrollBy({ top: r.top - innerHeight / 3, behavior: 'smooth' });
    }
  }
  function unTint() { const p = blocks[activeIndex]; if (p && p.el && !p.shared) p.el.classList.remove('lb-active'); }
  // The paragraph that holds a sentence: the one numbered i, or, if this page was read on another
  // load of it (a site can hide a different line each time, so the numbering can differ by one),
  // the nearest one that has the sentence's text.
  function blockOf(i, text) {
    const key = squash(text || '');
    if (!key) return i;
    const has = (j) => { const b = blocks[j]; return !!b && squash(b.text || '').includes(key); };
    if (has(i)) return i;
    for (let d = 1; d <= 12; d++) { if (has(i + d)) return i + d; if (has(i - d)) return i - d; }
    for (let j = 0; j < blocks.length; j++) if (has(j)) return j;
    return i;
  }
  function highlight(i, text, offset, chunk) {
    unTint();
    const j = blockOf(i, text);
    if (j !== i) offset = 0;                                       // (found elsewhere: look for it from the paragraph's start)
    i = j;
    activeIndex = i;
    const b = blocks[i];
    const el = b && elOf(b);
    if (!el) {
      cur = null;
      if (canMark) { CSS.highlights.delete('loudbook-sentence'); CSS.highlights.delete('loudbook-word'); }
      if (b && b.pid) { const loaded = document.querySelectorAll('p[data-p-id]'); if (loaded.length) loaded[loaded.length - 1].scrollIntoView({ block: 'end' }); }
      return false;
    }
    if (!b.shared) el.classList.add('lb-active');
    const marked = markSentence(el, text, (offset || 0) + (b.shared && b.base > 0 ? b.base : 0));
    if (cur) cur.chunk = chunk;
    const r = (b.shared && markedRange ? markedRange : el).getBoundingClientRect();
    if (r.top < 60 || r.bottom > innerHeight - 60) {
      if (b.shared && markedRange) window.scrollBy({ top: r.top - innerHeight / 3, behavior: 'smooth' });
      else el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    return marked;
  }
  function clear() { unTint(); activeIndex = -1; cur = null; if (canMark) { CSS.highlights.delete('loudbook-sentence'); CSS.highlights.delete('loudbook-word'); } }

  // tap-and-hold a paragraph -> "read from here" is handled by the app; it asks which block is here
  function blockAt(x, y) {
    const el = document.elementFromPoint(x, y);
    if (!el) return -1;
    for (let i = 0; i < blocks.length; i++) { const e = elOf(blocks[i]); if (e && (e === el || e.contains(el))) return i; }
    return -1;
  }

  const style = document.createElement('style');
  style.textContent = `.lb-active{background:rgba(232,170,70,.13)!important;box-shadow:-5px 0 0 rgba(232,170,70,.85)!important;border-radius:3px}
    ::highlight(loudbook-sentence){background-color:rgba(232,170,70,.30)}
    ::highlight(loudbook-word){background-color:rgba(232,170,70,.55);text-decoration:underline rgba(200,130,30,.9) 2px}`;
  (document.head || document.documentElement).appendChild(style);

  // sayText: what the voice is given for a bit of text (for the pronunciation tests)
  const sayText = (t, fixes, tidy) => speakNumbers(LBSay.makeSayer(LBSay.parseFixes(fixes || ""), { tidy: tidy !== false })(t));
  // what's under a long press: the paragraph's number and its text (the app finds the sentence by
  // the text, in case its copy of the chapter came from another load of the page)
  function blockInfo(x, y) {
    const i = blockAt(x, y);
    return JSON.stringify({ i, text: i >= 0 && blocks[i] ? blocks[i].text : '' });
  }
  window.LB = { run, highlight, progress, clear, blockAt, blockInfo, speakNumbers, words, sayText };
})();
