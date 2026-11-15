// The audiobook studio page (pc-server/studio.html) in jsdom against a stand-in server: a re-recording finishing while you
// listen or type new words doesn't stop the sound or wipe the words (it's drawn once you pause and stop editing), and a
// quick one that's done before the page first asks still says "Done". Run: NODE_PATH=<jsdom's node_modules> node pc-server/test/studio_page.js
const {JSDOM} = require('jsdom');
const fs = require('fs'), path = require('path');
const html = fs.readFileSync(process.argv[2] || path.join(__dirname, '..', 'studio.html'), 'utf8');
let tasks = {}, renders = 0;
const sents = [0,1,2,3].map(i => ({i, p:i, text:'Sentence '+i+'.', start:i*2, end:i*2+1.5, voice:'Heart', redone:0, busy:''}));
const routes = {
  '/api/books': () => ({books:[{job:'a.job.json', title:'A', done:1,total:1,sample:false}], kokoro:[], recorded:[], samples:[], status:''}),
  '/api/chapters': () => ({job:'a.job.json', title:'A', voice:3, voiceName:'Heart', chapters:[{k:0,name:'One',recorded:true,seconds:8}]}),
  '/api/chapter': () => { renders++; return {job:'a.job.json', k:0, name:'One', recorded:true, seconds:8, sentences:sents, chapterBusy:''}; },
  '/api/tasks': () => ({tasks: JSON.parse(JSON.stringify(tasks))}),
  '/api/redo': () => { tasks['1'] = {state:'done', msg:'recorded again', job:'a.job.json', k:0, i:1}; return {task:'1'}; },
};
const dom = new JSDOM(html, {runScripts:'dangerously', url:'http://127.0.0.1:8772/', beforeParse(w) {
  w.fetch = async (u, o) => { const p = new URL(u, 'http://x').pathname; const d = routes[p](); return {ok:true, status:200, json: async () => d}; };
  w.confirm = () => true;
  w.HTMLMediaElement.prototype.play = function () { this._p = false; this.dispatchEvent(new w.Event('play')); return Promise.resolve(); };
  w.HTMLMediaElement.prototype.pause = function () { this._p = true; this.dispatchEvent(new w.Event('pause')); };
  Object.defineProperty(w.HTMLMediaElement.prototype, 'paused', {get() { return this._p !== false; }});
}});
const w = dom.window, $ = s => w.document.querySelector(s);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let fails = 0; const check = (n, ok) => { console.log((ok?'ok   ':'FAIL ')+n); if (!ok) fails++; };
(async () => {
  await sleep(200);
  $('tr.ch').click(); await sleep(200);
  check('chapter shown', w.document.querySelectorAll('.sent').length === 4);
  $('[data-p="0"]').click();                          // playing sentence 0
  check('playing', !$('#au').paused);
  $('[data-e="2"]').click(); $('textarea').value = 'typing new words';
  $('[data-a="1"]').click();                          // Again on sentence 1 (done at once: fast voice)
  await sleep(3500);                                  // watch ticks
  check('textarea kept while a task finished', $('textarea') && $('textarea').value === 'typing new words');
  check('audio element kept (still playing)', !$('#au').paused);
  check('done toast shown even though it finished at once', /Done: recorded again/.test($('#toast').textContent));
  const before = renders;
  $('#au').pause(); await sleep(100);
  check('not drawn again while editing', renders === before);
  [...$('textarea').closest('.t').querySelectorAll('button')][1].click(); await sleep(200);   // Cancel
  check('drawn after cancel + pause', renders === before + 1 && !$('textarea'));
  console.log(fails ? fails + ' FAILED' : 'STUDIO PAGE OK'); process.exit(fails ? 1 : 0);
})();
