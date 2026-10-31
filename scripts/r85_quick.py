"""R85 quick trace: send a query and dump all events with timing."""
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
