"""R85 quick trace: send a query and dump all events with timing."""
import json
import time
from websockets.sync.client import connect

with connect('ws://127.0.0.1:17888/ws') as ws:
    print(f'[t=0] connected')
    welcome = ws.recv()
    print(f'[t=0] welcome len={len(welcome)}')
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'say hi briefly, one word only'}}))
    print(f'[t=0] query sent')
    end = time.time() + 25
    n = 0
    t0 = time.time()
    while time.time() < end:
        try:
            msg = ws.recv(timeout=3)
            n += 1
            elapsed = time.time() - t0
            d = json.loads(msg)
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et == 'text_delta':
                    print(f' [t={elapsed:.1f}s] text: {ev.get("text","")!r}')
                elif et == 'run_end':
                    print(f' [t={elapsed:.1f}s] run_end reason={ev.get("stopReason")}')
                elif et == 'side_note':
                    print(f' [t={elapsed:.1f}s] side_note: {ev.get("message","")!r}')
                else:
                    print(f' [t={elapsed:.1f}s] {et}')
            elif 'id' in d:
                print(f' [t={elapsed:.1f}s] RPC-OK {d.get("result")}')
        except TimeoutError:
            elapsed = time.time() - t0
            print(f' [t={elapsed:.1f}s] (no event in 3s)')
    print(f'== total frames: {n} ==')
