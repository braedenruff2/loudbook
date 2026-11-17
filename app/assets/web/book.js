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
    /** "2 h 10 min left" (at the listening speed), or '' when not started or finished. */
    leftLabel(book, speed) {
      const total = (book.chapters || []).reduce((t, c) => t + (c.seconds || 0), 0);
      const p = LBBook.progress(book);
      if (!(total > 0) || p <= 0 || p >= 0.999) return '';
      const min = Math.max(1, Math.round(total * (1 - p) / Math.max(0.5, speed || 1) / 60));
      return (min >= 60 ? Math.floor(min / 60) + ' h ' + (min % 60 ? (min % 60) + ' min' : '').trim() : min + ' min').trim() + ' left';
    },
    /** A text chapter's line in the list: what it plays with ("PC voice", "made on this phone · Heart", or read live). */
    recLabel(c) {
      const t = '~' + LBBook.clock(c.seconds);
      if (c.rec === 'pc') return 'PC recording \u00b7 ' + t;
      if (c.rec === 'phone') return 'made on phone' + (c.voice ? ' \u00b7 ' + c.voice : '') + ' \u00b7 ' + t;
      return t + ' \u00b7 read live';
    },
    /** A text book's line: how many chapters have the PC's recording, how many were made on the phone. */
    recSummary(chapters) {
      const pc = chapters.filter(c => c.rec === 'pc').length, ph = chapters.filter(c => c.rec === 'phone').length;
      if (!pc && !ph) return 'read live by the phone\'s voice';
      const parts = [];
      if (pc) parts.push(pc === chapters.length ? 'all recorded by your PC' : pc + ' recorded by your PC');
      if (ph) parts.push(ph + ' made on this phone');
      const rest = chapters.length - pc - ph;
      if (rest) parts.push(rest + ' read live');
      return parts.join(', ');
    },
    /** Kokoro's English voices by speaker number (as VoiceNames.java). */
    KOKORO: { 3: 'Heart', 2: 'Bella', 6: 'Nicole', 21: 'Emma', 16: 'Michael', 14: 'Fenrir', 18: 'Puck', 1: 'Aoede', 5: 'Kore',
      9: 'Sarah', 26: 'George', 25: 'Fable', 22: 'Isabella', 7: 'Nova', 0: 'Alloy', 10: 'Sky', 27: 'Lewis', 13: 'Eric', 15: 'Liam',
      17: 'Onyx', 12: 'Echo', 24: 'Daniel', 20: 'Alice', 23: 'Lily', 11: 'Adam' },
    /** A recording's voice in words ("voice" in the PC's chapter .json): 17 -> "Onyx", "s17/e0.75" -> "Onyx (expressiveness
     *  0.75)", "builtin" -> "Chatterbox's own voice", a recorded voice's id -> "a voice recorded on your PC". */
    voiceName(v) {
      const k = n => (n in LBBook.KOKORO ? LBBook.KOKORO[n] : 'voice ' + n);
      if (v === undefined || v === null || String(v).trim() === '') return 'unknown';
      v = String(v).trim();
      if (v === 'phone') return 'the phone\'s voice';
      if (/^-?\d+(\.0)?$/.test(v)) return k(parseInt(v, 10));
      const parts = v.split('/');
      let head = parts[0], pitch = null, speed = null;
      const hat = head.indexOf('^'); if (hat >= 0) { pitch = head.slice(hat + 1); head = head.slice(0, hat); }
      const at = head.indexOf('@'); if (at >= 0) { speed = head.slice(at + 1); head = head.slice(0, at); }
      const base = /^s\d+$/.test(head) ? k(+head.slice(1)) : head === 'builtin' ? 'Chatterbox\'s own voice'
        : /^[0-9a-f]{12}$/.test(head) ? 'a voice recorded on your PC' : /^\d+$/.test(head) ? k(+head) : head;
      const how = [];
      if (speed) how.push('speed ' + speed);
      if (pitch) how.push('pitch ' + (/^[+-]/.test(pitch) ? pitch : '+' + pitch));
      for (const p of parts.slice(1)) {
        if (p.length < 2) continue;
        how.push(p[0] === 'e' ? 'expressiveness ' + p.slice(1) : p[0] === 'c' ? 'pace ' + p.slice(1) : p);
      }
      return how.length ? base + ' (' + how.join(', ') + ')' : base;
    },
    /** The voices of a chapter's sentences, most used first: [{name, n}]. names: the PC's own words for them, if it sent any. */
    voices(sentences, names) {
      const m = new Map();
      for (const x of sentences || []) {
        if (!('voice' in x)) continue;
        const nm = (names && names[String(x.voice)]) || LBBook.voiceName(x.voice);
        m.set(nm, (m.get(nm) || 0) + 1);
      }
      return [...m.entries()].map(([name, n]) => ({ name, n })).sort((a, b) => b.n - a.n);
    },
    /** The sleep timer's settings, in the order the moon button steps through them: [minutes, at the chapter's end]. */
    SLEEPS: [[0, false], [15, false], [30, false], [45, false], [60, false], [0, true]],
    /** The setting after the one running now (st: bookState's sleepLeft ms, sleepEnd). A timer part-way down counts as the
     *  setting it started from (12 minutes left: 15), so one tap goes on to 30. */
    sleepNext(st) {
      const S = LBBook.SLEEPS;
      let i = 0;
      if (st && st.sleepEnd) i = S.length - 1;
      else if (st && st.sleepLeft > 0) { const m = st.sleepLeft / 60000; i = S.findIndex(x => !x[1] && x[0] >= m - 0.01); if (i < 0) i = S.length - 2; }
      return S[(i + 1) % S.length];
    },
    /** The moon button's text: "☾" off, "☾ 12m" (minutes left, rounded up), "☾ end" (this chapter's end). */
    sleepLabel(st) {
      if (st && st.sleepEnd) return '\u263E end';
      if (st && st.sleepLeft > 0) return '\u263E ' + Math.ceil(st.sleepLeft / 60000) + 'm';
      return '\u263E';
    },
  };
  if (typeof module !== 'undefined') module.exports = LBBook;
  root.LBBook = LBBook;
})(typeof window !== 'undefined' ? window : globalThis);
