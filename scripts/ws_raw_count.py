"""Brute-force read everything from the WS."""
import json, time
from websockets.sync.client import connect

PORT = 17890
print(f'[{time.strftime("%H:%M:%S")}] starting', flush=True)
with connect(f'ws://127.0.0.1:{PORT}/ws') as ws:
    print(f'[{time.strftime("%H:%M:%S")}] connected, reading welcome', flush=True)
    welcome = ws.recv()
    print(f'[{time.strftime("%H:%M:%S")}] welcome len={len(welcome)}', flush=True)
    ws.send(json.dumps({'jsonrpc':'2.0','id':1,'method':'query','params':{'prompt':'hi again'}}))
    print(f'[{time.strftime("%H:%M:%S")}] query sent', flush=True)
    end = time.time() + 30
    n = 0
    while time.time() < end:
        try:
            msg = ws.recv(timeout=1)
            n += 1
            if n <= 5:
                d = json.loads(msg)
                t = d.get('event',{}).get('type','?') if 'event' in d else f'id={d.get("id")}'
                print(f'[{time.strftime("%H:%M:%S")}] frame#{n} {t} len={len(msg)}', flush=True)
        except TimeoutError:
            continue
    print(f'[{time.strftime("%H:%M:%S")}] total frames: {n}', flush=True)
