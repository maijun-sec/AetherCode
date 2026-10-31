"""Brute-force read everything from the WS."""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

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
