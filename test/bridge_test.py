# The app's page script (assets/web/loudbook.js) on every mock site: finds the chapter, splits
# it, applies pronunciation fixes + number words, and highlights - as the app will call it.
import sys, json
sys.path.insert(0, '/home/claude/loudbook-test')
from sites_mock import P, EXPECT
from playwright.sync_api import sync_playwright
JS = open('/home/claude/loudbook-android/app/assets/web/loudbook.js').read()
fails = []
def check(n, c, d=''):
    print(('PASS ' if c else 'FAIL ') + n + (('  ' + str(d)) if d != '' else ''), flush=True)
    if not c: fails.append(n)
def route(r):
    u = r.request.url.split('#')[0]
    b = P.get(u) or P.get(u.split('?')[0])
    r.fulfill(status=200 if b else 404, content_type='text/html; charset=utf-8', body=b or 'nope')
STUB = """window.__out=null; window.LoudbookNative={onChapter:(j)=>{window.__out=JSON.parse(j)}, onNoChapter:(j)=>{window.__out={none:JSON.parse(j)}}};"""
with sync_playwright() as pw:
    b = pw.chromium.launch(headless=True)
    ctx = b.new_context(viewport={'width': 412, 'height': 900})   # phone-sized
    ctx.route('https://**/*', route)
    for site, e in EXPECT.items():
        pg = ctx.new_page(); pg.goto(e['url']); pg.wait_for_timeout(300)
        pg.evaluate(STUB); pg.add_script_tag(content=JS)
        pg.evaluate("()=>LB.run('Ima = Eema', true)"); pg.wait_for_function("()=>window.__out", timeout=5000)
        o = pg.evaluate("()=>window.__out")
        ok = o and 'chunks' in o
        check(f'{site}: chapter handed to the app', ok, o if not ok else '')
        if not ok: continue
        check(f'{site}: title/story/next', (o['title'], o['fiction'], o['nextUrl']) == (e['title'], e['fiction'], e['next']), (o['title'], o['fiction'], o['nextUrl']))
        check(f'{site}: first sentence after the title', o['chunks'][0]['block'] == -1 and o['chunks'][1]['text'].startswith(e['first'][:20]), o['chunks'][:2])
        joined = ' '.join(c['text'] for c in o['chunks'])
        check(f'{site}: no clutter', not any(t in joined for t in e['traps']))
        pg.evaluate("(o)=>LB.highlight(o.chunks[2].block, o.chunks[2].text, 0)", o)
        mark = pg.evaluate("()=>{const h=CSS.highlights.get('loudbook-sentence'); return h?[...h][0].toString().replace(/\\s+/g,' ').trim():null}")
        check(f'{site}: highlight works on a phone-sized page', mark == o['chunks'][2]['text'].strip(), (mark, o['chunks'][2]['text']))
        if site == 'ao3':
            said = [c['say'] for c in o['chunks'] if 'Ima' in c['text']]
            check('pronunciation fixes reach the voice', said and 'Eema' in said[0], said)
        pg.close()
    # a page that isn't a chapter
    pg = ctx.new_page(); pg.goto('https://archiveofourown.org/works/111/chapters/99'); pg.evaluate(STUB); pg.add_script_tag(content=JS)
    pg.evaluate("()=>LB.run('', true)"); pg.wait_for_function("()=>window.__out", timeout=5000)
    check('non-chapter page reported as such', 'none' in pg.evaluate("()=>window.__out"))
    # numbers
    t = pg.evaluate("""()=>[LB.speakNumbers('He was 17 in 1998, had $1,250.50 and 3.5 swords; HP 120 of 150 at 10:05, the 2nd time, 2000s, 2005, 12,000 men.')]""")[0]
    check('numbers are said as words', t == "He was seventeen in nineteen ninety-eight, had one thousand two hundred and fifty dollars and fifty cents and three point five swords; HP one hundred and twenty of one hundred and fifty at ten oh five, the second time, two thousands, two thousand five, twelve thousand men.", t)
    b.close()
print('\nRESULT: %d failed %s' % (len(fails), fails or ''))
