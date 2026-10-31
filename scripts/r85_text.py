"""Trace the model's text + tool calls to see what it's actually doing."""
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
