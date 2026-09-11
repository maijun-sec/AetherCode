"""Trace the model's text + tool calls to see what it's actually doing."""
import json
import time
from websockets.sync.client import connect

prompt = 'say hi briefly, one word only'

with connect('ws://127.0.0.1:17888/ws') as ws:
    ws.recv()
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':prompt}}))
    end = time.time() + 60
    t0 = time.time()
    while time.time() < end:
        try:
            msg = ws.recv(timeout=2)
            d = json.loads(msg)
            if 'event' in d:
                ev = d['event']
                et = ev.get('type')
                if et == 'text_delta':
                    text = ev.get('text','')
                    print(f' [t={time.time()-t0:.1f}s] {text!r}', flush=True)
                elif et == 'tool_use_start':
                    name = ev.get('name')
                    inp = ev.get('input',{})
                    print(f' [t={time.time()-t0:.1f}s] TOOL {name} input={json.dumps(inp)[:120]!r}', flush=True)
                elif et == 'run_end':
                    print(f' [t={time.time()-t0:.1f}s] run_end reason={ev.get("stopReason")}', flush=True)
        except TimeoutError:
            continue
