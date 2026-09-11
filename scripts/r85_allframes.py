"""R85 trace: dump ALL frames (including ping/pong) to see what's coming."""
import json
import time
from websockets.sync.client import connect

with connect('ws://127.0.0.1:17888/ws') as ws:
    print(f'[t=0] connected')
    welcome = ws.recv()
    print(f'[t=0] welcome len={len(welcome)} preview={welcome[:80]!r}')
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi briefly, one word only'}}))
    print(f'[t=0] query sent')
    end = time.time() + 25
    t0 = time.time()
    n = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=3)
            n += 1
            elapsed = time.time() - t0
            preview = msg[:120].replace('\n', ' ').encode('ascii', 'replace').decode('ascii')
            print(f' [t={elapsed:.1f}s] frame#{n} {len(msg)}b {preview!r}')
        except TimeoutError:
            elapsed = time.time() - t0
            print(f' [t={elapsed:.1f}s] (no frame in 3s)')
    print(f'== total frames: {n} ==')
