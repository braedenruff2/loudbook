// The audiobook page (app/assets/web/book.html + book.js): the PC's recorded chapters with their text, the sentence being
// said lit up. Part 1: book.js's pure parts (plain node). Part 2 (needs jsdom, NODE_PATH as in sites-test): the page itself
// against a stand-in LoudbookNative: the library, opening a chapter plays it, the lit sentence follows the time, a tap on a
// sentence seeks there, the page goes on to the next chapter when the player does, nothing from other pages' keys.
const fs = require('fs'), path = require('path'), assert = require('assert');
const web = path.join(__dirname, '..', '..', 'app', 'assets', 'web');
const B = require(path.join(web, 'book.js'));
let fails = 0, passes = 0;
function check(name, f) {
  try { f(); passes++; } catch (e) { fails++; console.log('FAIL ' + name + ': ' + e.message); }
}

// ---------------------------------------------------------------- pure parts
const S = [
  { p: 0, s: 0, start: 0, end: 1.3, text: 'Chapter one' },
  { p: 1, s: 0, start: 2.1, end: 3.4, text: 'A.' },
  { p: 2, s: 0, start: 4.1, end: 9.3, text: 'B.' },
  { p: 2, s: 1, start: 9.9, end: 12, text: 'C.' },
  { p: 4, s: 0, start: 12.5, end: 14, text: 'D.' },
];
check('paragraphs', () => assert.deepStrictEqual(B.paragraphs(S), [[0], [1], [2, 3], [4]]));
check('paragraphs of nothing', () => assert.deepStrictEqual(B.paragraphs([]), []));
check('at: before the first', () => assert.strictEqual(B.at([{ start: 0.5 }], 0.1), -1));
check('at: 0', () => assert.strictEqual(B.at(S, 0), 0));
check('at: in the gap after a sentence it stays on it', () => assert.strictEqual(B.at(S, 1.8), 0));
check('at: just started (rounding)', () => assert.strictEqual(B.at(S, 2.07), 1));
check('at: middle', () => assert.strictEqual(B.at(S, 10), 3));
check('at: past the end', () => assert.strictEqual(B.at(S, 999), 4));
check('at: nothing', () => assert.strictEqual(B.at([], 3), -1));
check('at: every time over a long chapter', () => {
  const L = []; for (let i = 0; i < 2000; i++) L.push({ start: i * 3.1 });
  for (let t = 0; t < 6200; t += 1.7) { const i = B.at(L, t); assert.ok(L[i].start <= t + 0.05 && (i + 1 >= L.length || L[i + 1].start > t + 0.05), 't=' + t); }
});
check('clock', () => {
  assert.strictEqual(B.clock(0), '0:00'); assert.strictEqual(B.clock(754.9), '12:34');
  assert.strictEqual(B.clock(3725), '1:02:05'); assert.strictEqual(B.clock(-3), '0:00'); assert.strictEqual(B.clock(undefined), '0:00');
});
check('route + hash round trip (spaces, &, #, accents)', () => {
  const b = 'My Dungeon Life & Co #1 é', f = '0001 - Vol 1 - Chapter 1 - 3.json';
  const r = B.route(B.hash('ab12', b, f));
  assert.deepStrictEqual(r, { k: 'ab12', b, f });
  assert.deepStrictEqual(B.route(B.hash('ab12')), { k: 'ab12' });
  assert.deepStrictEqual(B.route('#k=ab&b=%E0%A4%A'), { k: 'ab' });     // (a broken escape is dropped, not thrown)
});
check('progress', () => {
  const book = { chapters: [{ file: 'a', seconds: 100 }, { file: 'b', seconds: 100 }], at: { file: 'b', sec: 50 } };
  assert.strictEqual(B.progress(book), 0.75);
  assert.strictEqual(B.progress({ chapters: book.chapters }), 0);
  assert.strictEqual(B.progress({ chapters: book.chapters, at: { file: 'gone', sec: 5 } }), 0);
  assert.strictEqual(B.progress({ chapters: [] }), 0);
});

// ---------------------------------------------------------------- the page
let JSDOM;
try { ({ JSDOM } = require('jsdom')); } catch (e) { JSDOM = null; }
async function page() {
  const KEY = 'feedbeef';
  const chapters = {
    '0001 - One.json': { title: 'One', seconds: 14, sentences: S },
    '0002 - Two.json': { title: 'Two', seconds: 6, sentences: [{ p: 0, s: 0, start: 0, end: 2, text: 'Chapter two' }, { p: 1, s: 0, start: 2.5, end: 6, text: 'E.' }] },
  };
  const player = { book: '', ch: -1, file: '', ms: 0, playing: false, speed: 1, calls: [] };
  const lib = () => [{ name: 'Test Book', chapters: Object.keys(chapters).map(f => ({ file: f, title: chapters[f].title, seconds: chapters[f].seconds, audio: true })),
    at: player.file ? { file: player.file, sec: player.ms / 1000 } : undefined }];
  const files = Object.keys(chapters);
  const wrongKey = [];
  const native = {
    books(k) { if (k !== KEY) wrongKey.push('books'); return JSON.stringify(lib()); },
    chapter(k, b, f) { if (k !== KEY) wrongKey.push('chapter'); return JSON.stringify(chapters[f] || null); },
    bookState(k) { return JSON.stringify(player.file ? { book: player.book, ch: player.ch, file: player.file, ms: player.ms, dur: chapters[player.file].seconds * 1000, playing: player.playing, speed: player.speed, error: '' } : { playing: false, speed: 1, error: '' }); },
    bookPlay(k, b, ch, sec) { player.calls.push(['play', ch, sec]); player.book = b; player.ch = ch; player.file = files[ch]; player.ms = Math.max(0, sec) * 1000; player.playing = true; },
    bookToggle() { player.calls.push(['toggle']); player.playing = !player.playing; },
    bookSeek(k, ms) { player.calls.push(['seek', ms]); player.ms = ms; },
    bookJump(k, ms) { player.calls.push(['jump', ms]); player.ms += ms; },
    bookSpeed(k, s) { player.speed = s; },
    bookCopy() { }, copyState() { return '{}'; }, kindleList() { }, kindleState() { return '{}'; }, kindleSend() { },
  };
  const html = fs.readFileSync(path.join(web, 'book.html'), 'utf8').replace('<script src="book.js"></script>', '<script>' + fs.readFileSync(path.join(web, 'book.js'), 'utf8') + '</script>');
  const dom = new JSDOM(html, { url: 'file:///android_asset/web/book.html#k=' + KEY, runScripts: 'dangerously', pretendToBeVisual: true,
    beforeParse(w) { w.LoudbookNative = native; w.scrollBy = () => { }; w.scrollTo = () => { }; } });
  const w = dom.window, d = w.document;
  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  await wait(50);
  check('page: library shows the book', () => {
    assert.ok(!d.getElementById('lib').hidden);
    assert.ok(d.getElementById('books').textContent.includes('Test Book'));
    assert.ok(d.getElementById('books').textContent.includes('Start listening'));
  });
  d.querySelector('.go').click();
  await wait(80);
  check('page: start listening opens chapter 1 and plays it', () => {
    assert.ok(d.getElementById('lib').hidden && !d.getElementById('reader').hidden, 'reader shown');
    assert.deepStrictEqual(player.calls[0], ['play', 0, -1]);
    assert.strictEqual(d.querySelectorAll('#text p').length, 4);
    assert.strictEqual(d.querySelectorAll('#text .s').length, 5);
    assert.strictEqual(d.getElementById('rTitle').textContent, 'One');
  });
  player.ms = 10000; await wait(450);
  check('page: the sentence said is lit', () => {
    const lit = d.querySelectorAll('#text .s.lit');
    assert.strictEqual(lit.length, 1); assert.strictEqual(lit[0].textContent, 'C.');
    assert.strictEqual(d.getElementById('tNow').textContent, '0:10');
  });
  d.querySelectorAll('#text .s')[1].click(); await wait(400);
  check('page: tapping a sentence goes there', () => {
    assert.deepStrictEqual(player.calls[player.calls.length - 1], ['seek', 2100]);
    assert.strictEqual(d.querySelector('#text .s.lit').textContent, 'A.');
  });
  // the player goes on to chapter 2 by itself
  player.ch = 1; player.file = files[1]; player.ms = 3000; await wait(900);
  check('page: follows the player to the next chapter', () => {
    assert.strictEqual(d.getElementById('rTitle').textContent, 'Two');
    assert.ok(w.location.hash.includes(encodeURIComponent(files[1])));
    assert.strictEqual(d.querySelector('#text .s.lit').textContent, 'E.');
    assert.ok(!player.calls.some((c, i) => i > 1 && c[0] === 'play'), 'no new play call: ' + JSON.stringify(player.calls));
  });
  d.getElementById('play').click(); await wait(250);
  check('page: pause', () => { assert.strictEqual(player.playing, false); assert.strictEqual(d.getElementById('play').getAttribute('aria-label'), 'Play'); });
  d.getElementById('back15').click(); await wait(50);
  check('page: back 15 s', () => assert.deepStrictEqual(player.calls[player.calls.length - 1], ['jump', -15000]));
  d.getElementById('prevCh').click(); await wait(250);
  check('page: previous chapter opens and plays from its start', () => {
    assert.strictEqual(d.getElementById('rTitle').textContent, 'One');
    assert.deepStrictEqual(player.calls[player.calls.length - 1], ['play', 0, 0]);
  });
  d.getElementById('backBtn').click(); await wait(100);
  check('page: back to the library, it says the book is playing', () => {
    assert.ok(!d.getElementById('lib').hidden);
    assert.ok(d.getElementById('books').textContent.includes('Playing now'));
  });
  player.playing = false; player.ms = 75000;
  d.dispatchEvent(new w.Event('visibilitychange')); await wait(50);
  check('page: paused, continue shows the chapter and where in it', () => {
    const t = d.querySelector('.go').textContent;
    assert.ok(t.includes('Continue listening') && t.includes('One') && t.includes('1:15'), t);
  });
  check('page: every call used the page key', () => assert.deepStrictEqual(wrongKey, []));
  w.close();
}

(async () => {
  if (JSDOM) await page(); else console.log('(jsdom not found: the page part skipped; NODE_PATH=<dir>/node_modules to run it)');
  console.log(`book: ${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
})();
