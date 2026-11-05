// A page that moves on without a reload (Wattpad's next part: pushState) while its text is still being fetched: the answer
// for the part before mustn't be sent with the new address (it was kept and read as the new part). Plain node, no jsdom.
const fs=require('fs');
const src=fs.readFileSync(require('path').join(__dirname,'../../web/bridge.js'),'utf8');
let href='https://www.wattpad.com/111111-part-1';
const sent=[];
let resolveFetch;
const g={
  window:{}, location:{get href(){return href}}, innerWidth:400, innerHeight:800,
  document:{querySelector:()=>null,querySelectorAll:()=>[],createElement:()=>({}),head:{appendChild(){}}},
  CSS:{highlights:new Map(),escape:s=>s}, Highlight:function(){},
  LoudbookNative:{onChapter:j=>sent.push(JSON.parse(j)),onNoChapter:j=>sent.push({no:j})},
  LBSay:{plainLetters:t=>t,makeSayer:()=>t=>t,parseFixes:()=>[]},
  LBChunk:{chunkBlocks:bs=>bs.map(b=>({block:b.i,text:b.text}))},
  fetch:(u)=>new Promise(r=>{resolveFetch=()=>r({ok:true,text:async()=>'API-of:'+u})}),
};
g.globalThis=g;
g.LB_SITES={profileFor:()=>({}),
  extract:(doc,url)=>({roots:[1],blocks:[{text:'text of '+url}],site:'wattpad',title:'T',textApi:'api?'+url}),
  blocksFromApiHtml:(h)=>[{text:h,pid:'1'}]};
const f=new Function(...Object.keys(g),src+'\nreturn window.LB;');
const LB=f(...Object.values(g));
(async()=>{
  const p=LB.run('',true);              // page load: part 1, waiting on the API
  href='https://www.wattpad.com/222222-part-2'; // site moves on with pushState
  resolveFetch(); await p;
  const bad = sent.filter(c => c.url && c.chunks && !c.chunks[1].text.includes(c.url.split('/').pop()));
  console.log((bad.length ? 'FAIL' : 'ok  ') + ' an answer from the part before, sent with the new address: ' + JSON.stringify(sent.map(c=>({url:c.url,text:c.chunks&&c.chunks[1].text}))));
  // and the new part's own run still answers, with its own text
  sent.length = 0;
  const q = LB.run('', true); resolveFetch(); await q;
  const ok2 = sent.length === 1 && sent[0].url.endsWith('part-2') && sent[0].chunks[1].text.includes('part-2');
  console.log((ok2 ? 'ok  ' : 'FAIL') + ' the new part answers with its own text');
  if (bad.length || !ok2) process.exit(1);
})();
