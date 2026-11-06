// The audiobook page (book.html): the PC's recorded chapters with their text, the sentence being said lit up.
// The pure parts (LBBook.*) are tested in node by test/book/run.js; the page part runs only in the app.
(function (root) {
  'use strict';
  const LBBook = {
    /** Sentences [{p, s, start, end, text}] -> paragraphs [[sentence index, ...], ...] in order. */
    paragraphs(sentences) {
      const out = []; let last = null;
      sentences.forEach((x, i) => {
        if (!out.length || x.p !== last) { out.push([]); last = x.p; }
        out[out.length - 1].push(i);
      });
      return out;
    },
    /** The sentence being said at t seconds: the last one started by then (-1 before the first). Starts are in order. */
    at(sentences, t) {
      let lo = 0, hi = sentences.length - 1, best = -1;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (sentences[mid].start <= t + 0.05) { best = mid; lo = mid + 1; } else hi = mid - 1;
      }
      return best;
    },
    /** 754.2 -> "12:34", 3725 -> "1:02:05". */
    clock(sec) {
      sec = Math.max(0, Math.floor(sec || 0));
      const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, s = sec % 60;
      return (h ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(s).padStart(2, '0');
    },
    /** "#k=abc&b=My%20Book&f=0001.json" -> {k, b, f}. */
    route(hash) {
      const o = {};
      for (const part of String(hash || '').replace(/^#/, '').split('&')) {
        const eq = part.indexOf('=');
        if (eq > 0) { try { o[part.slice(0, eq)] = decodeURIComponent(part.slice(eq + 1)); } catch (e) { } }
      }
      return o;
    },
    hash(k, b, f) {
      return '#k=' + k + (b ? '&b=' + encodeURIComponent(b) : '') + (f ? '&f=' + encodeURIComponent(f) : '');
    },
    /** How much of a book has been listened to: the chapters before the one it's at, plus the seconds into that one. */
    progress(book) {
      let total = 0, done = 0, seen = false;
      for (const c of book.chapters || []) {
        total += c.seconds || 0;
        if (book.at && c.file === book.at.file) { done += Math.min(book.at.sec || 0, c.seconds || 0); seen = true; }
        else if (book.at && !seen) done += c.seconds || 0;
      }
      if (!book.at || !seen) done = 0;
      return total > 0 ? done / total : 0;
    },
  };
  if (typeof module !== 'undefined') module.exports = LBBook;
  root.LBBook = LBBook;
})(typeof window !== 'undefined' ? window : globalThis);
