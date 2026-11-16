// A chapter whose first paragraph is its own title (Wuxiaworld, many Royal Road chapters): the title is read once, not
// twice (bridge.js run() + titleRepeated). Plain Node + jsdom, like marks.js.
const fs = require('fs'), path = require('path');
const { JSDOM } = require('jsdom');
const root = path.join(__dirname, '..', '..');
const bundle = fs.readFileSync(path.join(root, 'app', 'assets', 'web', 'loudbook.js'), 'utf8');
const page = fs.readFileSync(path.join(__dirname, 'pages', 'wuxiaworld.html'), 'utf8');
let bad = 0;
const check = (what, ok) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) bad++; };
async function chunks(html, url) {
  const dom = new JSDOM(html, { url, runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  // (laid out as on a phone: every element has a box, so nothing counts as hidden)
  w.Element.prototype.getClientRects = function () { return [{ width: 100, height: 20 }]; };
  w.Element.prototype.getBoundingClientRect = function () { return { left: 0, top: 0, right: 100, bottom: 20, width: 100, height: 20 }; };
  w.CSS = { highlights: { set() { }, delete() { } }, escape: (s) => s };
  let ch = null;
  w.LoudbookNative = { onChapter: (j) => { ch = JSON.parse(j); }, onNoChapter: (j) => console.log('no chapter: ' + j) };
  w.eval(bundle);
  await w.eval("LB.run('', true)");
  const L = w.LB; w.close();
  return { ch, L };
}
(async () => {
  const { ch, L } = await chunks(page, 'https://www.wuxiaworld.com/novel/iron-lotus/il-chapter-7');
  const said = ch.chunks.map(c => c.text);
  check('Wuxiaworld: the title said once (' + JSON.stringify(said.slice(0, 2)) + ')', said.filter(t => /The Old Well/.test(t)).length === 1);
  check('Wuxiaworld: it is the page\'s own paragraph (marked as it is read)', ch.chunks[0].block === 0);
  check('Wuxiaworld: the story follows', said[1] === 'Wei Shan lowered the bucket into the old well and listened.');
  const plain = page.replace('<p><span>Chapter 7 - The Old Well</span></p>', '');
  const c2 = (await chunks(plain, 'https://www.wuxiaworld.com/novel/iron-lotus/il-chapter-7')).ch;
  check('no title paragraph: the title is still read first', c2.chunks[0].block === -1 && c2.chunks[0].text === 'Chapter 7 - The Old Well');
  check('same title, other punctuation/case', L.titleRepeated('Chapter 7 - The Old Well', 'CHAPTER 7: The Old Well.'));
  check('fancy letters', L.titleRepeated('Chapter 7', '𝐂𝐡𝐚𝐩𝐭𝐞𝐫 𝟕'));
  check('only part of the title: not the same', !L.titleRepeated('Chapter 7 - The Old Well', 'The Old Well'));
  check('a longer first line: not the same', !L.titleRepeated('The Old Well', 'The Old Well was dry.'));
  // the first paragraph only the chapter's number, the title "Chapter 12 - The Bridge": read once, in the title
  const rr = page.replace('<p><span>Chapter 7 - The Old Well</span></p>', '<p><span>Chapter 7</span></p>');
  const c3 = (await chunks(rr, 'https://www.wuxiaworld.com/novel/iron-lotus/il-chapter-7')).ch;
  check('number line: title read, "Chapter 7" not again', c3.chunks[0].block === -1 && !c3.chunks.some(c => c.text === 'Chapter 7')
    && c3.chunks[1].text === 'Wei Shan lowered the bucket into the old well and listened.');
  check('number lines', L.titleNumber('Chapter 12 - The Bridge', 'Chapter 12') && L.titleNumber('Chapter XII: The Bridge', 'CHAPTER XII')
    && L.titleNumber('12. The Bridge', '12') && L.titleNumber('Ch. 12 The Bridge', 'Ch. 12'));
  check('story lines are not number lines', !L.titleNumber('Run', 'Run!') && !L.titleNumber('Civil War', 'Civil')
    && !L.titleNumber('Chi Rises', 'Chi') && !L.titleNumber('The Bridge', 'The') && !L.titleNumber('Chapter 12', 'Chapter 12')
    && !L.titleNumber('Chapter 12 - The Bridge', 'Chapter 13'));
  check('no title / no text', !L.titleRepeated('', '') && !L.titleRepeated('x', undefined));
  console.log(bad ? bad + ' FAILED' : 'title: all right');
  process.exit(bad ? 1 : 0);
})();
