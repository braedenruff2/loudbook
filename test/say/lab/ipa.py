import sys, re, subprocess
K='/home/claude/loudbook-android/parts/kokoro-multi-lang-v1_0'
tok={}
for l in open(K+'/tokens.txt', encoding='utf-8'):
    l=l.rstrip('\n')
    if not l: continue
    sym, i = l.rsplit(' ',1) if l.count(' ')>=1 and l.rsplit(' ',1)[1].isdigit() else (' ', l.strip())
    tok[int(i)] = sym if sym else ' '
lines = sys.stdin.read().splitlines()
LIB='/tmp/bench/jni/sherpa-onnx-v1.13.8-linux-x64-jni/lib'
p = subprocess.run(['java','-Djava.library.path='+LIB,'-cp','/tmp/bench/out','b.Ipa',K], input='\n'.join(lines)+'\n', capture_output=True, text=True, env={'LD_LIBRARY_PATH':LIB,'PATH':'/usr/bin:/bin:/usr/local/bin'})
cur=None; out=[]
for l in p.stderr.splitlines():
    if l.startswith('@@CASE '): cur=l[7:]; ph=[]; continue
    if l.startswith('@@END'): print(cur); print('   ', ' | '.join(ph)); continue
    if cur is not None and re.fullmatch(r'0( \d+)+ 0', l.strip()):
        ph.append(''.join(tok.get(int(x),'?') for x in l.split()[1:-1]))
