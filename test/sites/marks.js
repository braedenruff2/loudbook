// The sentence and word marks on a web page (bridge.js markSentence / rangeOf / progress), for text the page writes
// differently from the reader's copy: "fancy text" letters (𝐁𝐨𝐥𝐝, ＦＵＬＬＷＩＤＴＨ, ꜱᴍᴀʟʟ ᴄᴀᴘꜱ), the ﬁ ligature, letters
// outside the basic plane (two halves each in JavaScript). The page keeps them as written; the voice gets plain
// letters; the marks must still find the sentence and cover whole characters. Plain Node + jsdom, like run.js.
const fs = require('fs'), path = require('path');
const { JSDOM } = require('jsdom');
const root = path.join(__dirname, '..', '..');
const bundle = fs.readFileSync(path.join(root, 'app', 'assets', 'web', 'loudbook.js'), 'utf8');
const cases = [
  // page paragraph, piece the app reads (as run() sent it), the sentence mark at its start, then [fraction said, word mark, sentence mark]
  ['<p>The window opened. [𝐒𝐭𝐫𝐞𝐧𝐠𝐭𝐡 +5] He grinned.</p>', 0, 'The window opened.', [[0.6, '𝐒𝐭𝐫𝐞𝐧𝐠𝐭𝐡', '[𝐒𝐭𝐫𝐞𝐧𝐠𝐭𝐡 +5] He grinned.'], [0.9, 'grinned']]],
  ['<p>𝕿𝖍𝖊 𝕯𝖚𝖓𝖌𝖊𝖔𝖓 𝕮𝖔𝖗𝖊 woke.</p>', 0, '𝕿𝖍𝖊 𝕯𝖚𝖓𝖌𝖊𝖔𝖓 𝕮𝖔𝖗𝖊 woke.', [[0.1, '𝕿𝖍𝖊'], [0.5, '𝕯𝖚𝖓𝖌𝖊𝖔𝖓'], [1, 'woke']]],
  ['<p>ＳＴＡＴＵＳ　ＷＩＮＤＯＷ</p>', 0, 'ＳＴＡＴＵＳ　ＷＩＮＤＯＷ', [[0.9, 'ＷＩＮＤＯＷ']]],
  ['<p>ᴛʜᴇ sʏsᴛᴇᴍ spoke.</p>', 0, 'ᴛʜᴇ sʏsᴛᴇᴍ spoke.', [[0.3, 'sʏsᴛᴇᴍ']]],
  ['<p>The ﬁnal ﬂight left.</p>', 0, 'The ﬁnal ﬂight left.', [[0.3, 'ﬁnal'], [0.6, 'ﬂight']]],
  ['<p>She wrote 𠀀 and went on.</p>', 0, 'She wrote 𠀀 and went on.', [[0.5, '𠀀']]],
  ['<p>Plain text, <b>with</b> a tag.</p>', 0, 'Plain text, with a tag.', [[0.7, 'with']]],
  // (chapter-test ch1.html: an emoji and fancy letters earlier in the paragraph. The app reads it sentence by sentence (the
  //  pieces listed last, as Chapter.java has them) and hints where each starts, Chapter.offsetInBlock; the repeated sentence
  //  must be marked where the third piece is, not at the first)
  ['<p>Tom tried it anyway 🙄, because he always did. “It never opens,” his sister said. “Never.”</p>', 1, '“It never opens,” his sister said.', [[0.4, 'opens']],
    ['Tom tried it anyway 🙄, because he always did.', '“It never opens,” his sister said.', '“Never.”']],
  ['<p>The 𝐠𝐚𝐭𝐞 was ʟᴏᴄᴋᴇᴅ. It was ﬁne. The 𝐠𝐚𝐭𝐞 was ʟᴏᴄᴋᴇᴅ. Then the ﬁnal one.</p>', 2, 'The 𝐠𝐚𝐭𝐞 was ʟᴏᴄᴋᴇᴅ.', [[0.4, '𝐠𝐚𝐭𝐞']],
    ['The gate was locked.', 'It was fine.', 'The gate was locked.', 'Then the final one.'], 'third'],
];
(async () => {
  let bad = 0;
  for (const [html, si, want, words, pieces, which] of cases) {
    const dom = new JSDOM(`<!doctype html><html><head><meta charset="utf-8"><title>T</title></head><body><article>${html}</article></body></html>`,
      { url: 'https://example.com/story/chapter-1', runScripts: 'outside-only', pretendToBeVisual: true });
    const w = dom.window;
    w.Element.prototype.getClientRects = function () { return [{ width: 100, height: 20 }]; };
    w.Element.prototype.getBoundingClientRect = function () { return { left: 0, top: 0, right: 100, bottom: 20, width: 100, height: 20 }; };
    w.Range.prototype.getBoundingClientRect = function () { return { left: 0, top: 100, right: 100, bottom: 120, width: 100, height: 20 }; };
    const marks = new Map();
    w.Highlight = function (r) { this.r = r; };
    w.CSS = { highlights: { set: (k, v) => marks.set(k, v), delete: (k) => marks.delete(k) }, escape: (s) => s };
    w.scrollBy = () => {}; w.Element.prototype.scrollIntoView = () => {};
    let ch = null;
    w.LoudbookNative = { onChapter: (j) => { ch = JSON.parse(j); }, onNoChapter: () => {} };
    w.eval(bundle);
    await w.eval("LB.run('', true)");
    const fails = [];
    const chunks = ch ? ch.chunks.filter(c => c.block >= 0) : [];
    let c = chunks[si], off = 0;
    if (pieces && chunks[0]) {
      // (the hint as the app gives it, Chapter.offsetInBlock: the non-space characters of the paragraph's pieces before this one)
      c = { block: chunks[0].block, text: pieces[si] };
      off = pieces.slice(0, si).reduce((n, t) => n + Array.from(t.replace(/\s+/g, '')).length, 0);
    }
    if (!c) fails.push('no sentence ' + si + ': ' + JSON.stringify(ch && ch.chunks));
    else {
      if (!pieces && /[\u{1D400}-\u{1D7FF}！-～ᴀ-ᴢﬁﬂ]/u.test(c.text)) fails.push('the reader\'s copy kept fancy letters: ' + c.text);
      const ok = w.LB.highlight(c.block, c.text, off, 0);
      const s = marks.get('loudbook-sentence');
      if (!ok || !s) fails.push('sentence not marked: ' + c.text);
      else if (s.r.toString() !== want) fails.push(`sentence mark "${s.r.toString()}", want "${want}"`);
      else if (which === 'third' && s.r.startContainer.nodeValue.slice(0, s.r.startOffset).indexOf('ﬁne') < 0) fails.push('the repeated sentence was marked at its first place');
      for (const [f, wantWord, wantSent] of words) {
        w.LB.progress(f, 0);
        const m = marks.get('loudbook-word');
        const got = m ? m.r.toString().replace(/[.,!?\]\[+0-9 ”’"]+$/u, '').replace(/^\[/, '') : '(none)';
        if (got !== wantWord) fails.push(`word mark at ${f}: "${got}", want "${wantWord}"`);
        const sm = marks.get('loudbook-sentence');
        if (wantSent && (!sm || sm.r.toString() !== wantSent)) fails.push(`sentence mark at ${f}: "${sm && sm.r.toString()}", want "${wantSent}"`);
      }
    }
    if (fails.length) { bad++; console.log(`FAIL ${html}\n  ${fails.join('\n  ')}`); } else console.log(`ok   ${want}`);
    w.close();
  }
  console.log(bad ? `${bad} of ${cases.length} mark checks wrong` : `all ${cases.length} mark checks right`);
  process.exit(bad ? 1 : 0);
})();
